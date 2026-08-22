package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemSummaryDto
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalControlExecutorSaturationTest {
    @Test
    fun `executor has fixed production bounds and overflow never runs on submitting thread`() {
        val production = BoundedHttpExecutor("production-limits")
        val productionLimits = production.snapshot()
        assertEquals(4, productionLimits.businessWorkerLimit)
        assertEquals(32, productionLimits.businessPendingLimit)
        assertEquals(1, productionLimits.busyResponderLimit)
        assertEquals(32, productionLimits.busyPendingLimit)
        production.shutdown()
        assertTrue(production.awaitTermination(Duration.ofSeconds(1)))

        val executor = BoundedHttpExecutor("unit", 1, 1, 1, 1)
        val businessStarted = CountDownLatch(1)
        val releaseBusiness = CountDownLatch(1)
        val queuedCompleted = CountDownLatch(1)
        val overflowCompleted = CountDownLatch(1)
        val overflowThread = AtomicReference<String>()
        val submitterThread = Thread.currentThread().name
        try {
            executor.execute {
                businessStarted.countDown()
                releaseBusiness.await(5, TimeUnit.SECONDS)
            }
            assertTrue(businessStarted.await(2, TimeUnit.SECONDS))
            executor.execute { queuedCompleted.countDown() }
            awaitSnapshot(executor) { it.businessActiveCount == 1 && it.businessPendingCount == 1 }

            executor.execute {
                overflowThread.set(Thread.currentThread().name)
                assertTrue(BoundedHttpExecutor.isBusyResponseTask())
                overflowCompleted.countDown()
            }
            assertTrue(overflowCompleted.await(2, TimeUnit.SECONDS))
            assertNotEquals(submitterThread, overflowThread.get())
            assertTrue(overflowThread.get().startsWith("local-control-busy-"))

            val saturated = executor.snapshot()
            assertEquals(1, saturated.businessLargestPoolSize)
            assertEquals(1, saturated.businessPendingCount)
            assertEquals(0, saturated.businessAdmissionAvailable)
            assertTrue(saturated.busyLargestPoolSize <= 1)
            assertTrue(saturated.businessPendingCount <= saturated.businessPendingLimit)
            assertTrue(saturated.busyPendingCount <= saturated.busyPendingLimit)

            releaseBusiness.countDown()
            assertTrue(queuedCompleted.await(2, TimeUnit.SECONDS))
        } finally {
            releaseBusiness.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(Duration.ofSeconds(2)))
            assertTrue(executor.isTerminated)
        }
    }

    @Test
    fun `HTTP saturation returns authenticated bounded busy response then recovers and stops cleanly`() {
        val businessStarted = CountDownLatch(1)
        val releaseBusiness = CountDownLatch(1)
        val serviceCalls = AtomicInteger()
        val serviceThreads = linkedSetOf<String>()
        val busyAuditThread = AtomicReference<String>()
        val service = object : StubMapControlService() {
            override suspend fun searchSystems(request: SearchSystemsRequest): ControlResult<List<SystemSummaryDto>> {
                synchronized(serviceThreads) { serviceThreads += Thread.currentThread().name }
                serviceCalls.incrementAndGet()
                businessStarted.countDown()
                releaseBusiness.await(5, TimeUnit.SECONDS)
                return ControlResult.Success(request.requestId, listOf(SystemSummaryDto(1, "Jita", 1, 1, 0.9)))
            }
        }
        val server = LocalControlServer(
            service,
            "0.1.2",
            auditSink = LocalControlAuditSink { event ->
                if (event.resultCode == "APP_BUSY") busyAuditThread.set(Thread.currentThread().name)
            },
        )
        server.executorFactory = { instanceId -> BoundedHttpExecutor(instanceId, 1, 1, 1, 1) }
        server.start()
        val authorization = server.sessionCredentials().authorizationHeaderValue()
        val endpoint = URI.create("http://127.0.0.1:${server.port}${LocalControlOperation.SEARCH_SYSTEM.path}")
        try {
            val first = sendAsync(endpoint, authorization, "saturation-1")
            assertTrue(businessStarted.await(2, TimeUnit.SECONDS))
            val second = sendAsync(endpoint, authorization, "saturation-2")
            awaitServerSnapshot(server) { it.businessActiveCount == 1 && it.businessPendingCount == 1 }

            val busy = send(endpoint, authorization, "saturation-busy")
            assertEquals(503, busy.statusCode())
            assertTrue(busy.body().contains("APP_BUSY"))
            assertEquals(1, serviceCalls.get(), "Busy response must not call MapControlService")
            assertTrue(busyAuditThread.get().startsWith("local-control-busy-"))
            assertFalse(busyAuditThread.get().contains("HTTP-Dispatcher"))

            val unauthorizedBusy = send(endpoint, null, "saturation-unauthorized")
            assertEquals(401, unauthorizedBusy.statusCode())
            val oversizedBusy = send(
                endpoint,
                authorization,
                "saturation-oversized",
                "x".repeat(LocalControlProtocol.REQUEST_BODY_LIMIT_BYTES + 1),
            )
            assertEquals(413, oversizedBusy.statusCode())

            val saturated = assertNotNull(server.executorSnapshot())
            assertEquals(1, saturated.businessWorkerLimit)
            assertEquals(1, saturated.businessPendingLimit)
            assertEquals(1, saturated.businessLargestPoolSize)
            assertTrue(saturated.busyLargestPoolSize <= 1)
            assertTrue(saturated.businessPendingCount <= 1)
            assertTrue(saturated.busyPendingCount <= 1)

            releaseBusiness.countDown()
            assertEquals(200, first.get(3, TimeUnit.SECONDS).statusCode())
            assertEquals(200, second.get(3, TimeUnit.SECONDS).statusCode())
            val recovered = send(endpoint, authorization, "saturation-recovered")
            assertEquals(200, recovered.statusCode())
            assertEquals(3, serviceCalls.get())
            synchronized(serviceThreads) {
                assertTrue(serviceThreads.all { it.startsWith("local-control-worker-") })
                assertTrue(serviceThreads.none { it.contains("HTTP-Dispatcher") })
            }
        } finally {
            releaseBusiness.countDown()
            val transportThreadNames = buildSet {
                synchronized(serviceThreads) { addAll(serviceThreads) }
                busyAuditThread.get()?.let(::add)
            }
            server.stop()
            assertTrue(server.lastExecutorTerminated)
            val liveNames = Thread.getAllStackTraces().keys.filter(Thread::isAlive).map(Thread::getName).toSet()
            assertTrue(transportThreadNames.none { it in liveNames })
        }
    }

    private fun sendAsync(endpoint: URI, authorization: String, requestId: String) =
        HttpClient.newHttpClient().sendAsync(request(endpoint, authorization, requestId), HttpResponse.BodyHandlers.ofString())

    private fun send(
        endpoint: URI,
        authorization: String?,
        requestId: String,
        body: String = "{\"requestId\":\"$requestId\",\"query\":\"Jita\"}",
    ): HttpResponse<String> = HttpClient.newHttpClient().send(
        request(endpoint, authorization, requestId, body),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun request(
        endpoint: URI,
        authorization: String?,
        requestId: String,
        body: String = "{\"requestId\":\"$requestId\",\"query\":\"Jita\"}",
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
        authorization?.let { builder.header("Authorization", it) }
        return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
    }

    private fun awaitSnapshot(
        executor: BoundedHttpExecutor,
        predicate: (BoundedHttpExecutorSnapshot) -> Boolean,
    ): BoundedHttpExecutorSnapshot = awaitCondition { executor.snapshot().takeIf(predicate) }

    private fun awaitServerSnapshot(
        server: LocalControlServer,
        predicate: (BoundedHttpExecutorSnapshot) -> Boolean,
    ): BoundedHttpExecutorSnapshot = awaitCondition { server.executorSnapshot()?.takeIf(predicate) }

    private fun <T> awaitCondition(read: () -> T?): T {
        repeat(200) {
            read()?.let { return it }
            Thread.sleep(10)
        }
        error("Condition was not reached")
    }
}
