package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemSummaryDto
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
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
                releaseBusiness.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
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
        val busyAudited = CountDownLatch(1)
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
                if (event.resultCode == "APP_BUSY") {
                    busyAuditThread.set(Thread.currentThread().name)
                    busyAudited.countDown()
                }
            },
        )
        server.executorFactory = { instanceId -> BoundedHttpExecutor(instanceId, 1, 1, 1, 1) }
        val client = HttpClient.newBuilder().connectTimeout(TEST_TIMEOUT).build()
        server.start()
        val authorization = server.sessionCredentials().authorizationHeaderValue()
        val endpoint = URI.create("http://127.0.0.1:${server.port}${LocalControlOperation.SEARCH_SYSTEM.path}")
        try {
            val first = sendAsync(client, endpoint, authorization, "saturation-1")
            assertTrue(businessStarted.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
            val second = sendAsync(client, endpoint, authorization, "saturation-2")
            awaitServerSnapshot(server) { it.businessActiveCount == 1 && it.businessPendingCount == 1 }

            val busy = send(client, endpoint, authorization, "saturation-busy")
            assertEquals(503, busy.statusCode())
            assertTrue(busy.body().contains("APP_BUSY"))
            assertEquals(1, serviceCalls.get(), "Busy response must not call MapControlService")
            assertTrue(
                busyAudited.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "Busy audit event was not observed",
            )
            val busyAuditThreadName = assertNotNull(busyAuditThread.get())
            assertTrue(busyAuditThreadName.startsWith("local-control-busy-"))
            assertFalse(busyAuditThreadName.contains("HTTP-Dispatcher"))
            awaitBusyResponderIdle(server)

            val unauthorizedBusy = send(client, endpoint, null, "saturation-unauthorized")
            assertEquals(401, unauthorizedBusy.statusCode())
            awaitBusyResponderIdle(server)
            assertEquals(413, sendDeclaredOversizedHeaders(endpoint, authorization))
            awaitBusyResponderIdle(server)

            val saturated = assertNotNull(server.executorSnapshot())
            assertEquals(1, saturated.businessWorkerLimit)
            assertEquals(1, saturated.businessPendingLimit)
            assertEquals(1, saturated.businessLargestPoolSize)
            assertTrue(saturated.busyLargestPoolSize <= 1)
            assertTrue(saturated.businessPendingCount <= 1)
            assertTrue(saturated.busyPendingCount <= 1)

            releaseBusiness.countDown()
            assertEquals(200, first.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).statusCode())
            assertEquals(200, second.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).statusCode())
            awaitServerSnapshot(server) {
                it.businessActiveCount == 0 &&
                    it.businessPendingCount == 0 &&
                    it.businessAdmissionAvailable == it.businessWorkerLimit + it.businessPendingLimit
            }
            val recovered = send(client, endpoint, authorization, "saturation-recovered")
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
            try {
                server.stop()
                assertTrue(server.lastExecutorTerminated)
                val liveNames = Thread.getAllStackTraces().keys.filter(Thread::isAlive).map(Thread::getName).toSet()
                assertTrue(transportThreadNames.none { it in liveNames })
            } finally {
                client.close()
                assertTrue(client.awaitTermination(TEST_TIMEOUT), "HTTP client did not terminate")
            }
        }
    }

    private fun sendAsync(client: HttpClient, endpoint: URI, authorization: String, requestId: String) =
        client.sendAsync(request(endpoint, authorization, requestId), HttpResponse.BodyHandlers.ofString())

    private fun send(
        client: HttpClient,
        endpoint: URI,
        authorization: String?,
        requestId: String,
        body: String = "{\"requestId\":\"$requestId\",\"query\":\"Jita\"}",
    ): HttpResponse<String> = try {
        client.send(request(endpoint, authorization, requestId, body), HttpResponse.BodyHandlers.ofString())
    } catch (failure: IOException) {
        throw IOException("HTTP request $requestId failed", failure)
    }

    private fun sendDeclaredOversizedHeaders(endpoint: URI, authorization: String): Int = Socket().use { socket ->
        val timeoutMillis = TEST_TIMEOUT.toMillis().toInt()
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        val headers = buildString {
            append("POST ${endpoint.rawPath} HTTP/1.1\r\n")
            append("Host: ${endpoint.host}:${endpoint.port}\r\n")
            append("Authorization: $authorization\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${LocalControlProtocol.REQUEST_BODY_LIMIT_BYTES + 1}\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }

        socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).use { reader ->
            val statusLine = requireNotNull(reader.readLine()) { "Oversized request returned no HTTP status" }
            val status = requireNotNull(statusLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull()) {
                "Invalid HTTP status line: $statusLine"
            }
            var contentLength = 0
            while (true) {
                val header = requireNotNull(reader.readLine()) { "Oversized response headers ended unexpectedly" }
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(':').trim().toInt()
                }
            }
            val body = CharArray(contentLength)
            var offset = 0
            while (offset < body.size) {
                val read = reader.read(body, offset, body.size - offset)
                check(read >= 0) { "Oversized response body ended unexpectedly" }
                offset += read
            }
            status
        }
    }

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

    private fun awaitBusyResponderIdle(server: LocalControlServer) {
        awaitServerSnapshot(server) { it.busyActiveCount == 0 && it.busyPendingCount == 0 }
    }

    private fun <T> awaitCondition(read: () -> T?): T {
        val deadline = System.nanoTime() + TEST_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(10)
        }
        error("Condition was not reached")
    }

    private companion object {
        val TEST_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
