package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.RouteActionContext
import dev.evestaticmapplanner.feature.api.RouteActionDescriptor
import dev.evestaticmapplanner.feature.api.RouteActionProvider
import dev.evestaticmapplanner.feature.api.RouteActionResult
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.feature.api.RouteActionTargetId
import dev.evestaticmapplanner.feature.api.RouteActionTargetOption
import dev.evestaticmapplanner.feature.api.RouteActionTargetSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RouteActionHostTest {
    @Test
    fun `same action ID is rejected within a Pack and allowed across Packs`() {
        RouteActionHost().use { host ->
            val first = host.scopedCapability(PackId("first.pack"))
            val second = host.scopedCapability(PackId("second.pack"))
            first.register(provider("send", setOf(RouteKind.NORMAL)))

            assertTrue(runCatching { first.register(provider("send", setOf(RouteKind.NORMAL))) }.isFailure)
            second.register(provider("send", setOf(RouteKind.CAPITAL)))

            assertEquals(2, host.state.value.size)
            assertNotEquals(host.state.value[0].key.packId, host.state.value[1].key.packId)
        }
    }

    @Test
    fun `route kind filtering and invocation run callback off caller thread`() {
        RouteActionHost().use { host ->
            val callerThread = Thread.currentThread().name
            val callbackThread = AtomicReference<String>()
            val routeSeen = AtomicReference<RouteSnapshot>()
            val registration = host.scopedCapability(PackId("test.pack")).register(
                provider("send", setOf(RouteKind.NORMAL)) { context ->
                    callbackThread.set(Thread.currentThread().name)
                    routeSeen.set(context.route)
                    RouteActionResult(RouteActionStatus.SUCCEEDED, "Sent")
                },
            )
            val key = host.state.value.single().key

            assertFalse(host.invoke(key, snapshot(RouteKind.CAPITAL)))
            assertTrue(host.invoke(key, snapshot(RouteKind.NORMAL)))
            await { host.state.value.single().lastStatus == RouteActionStatus.SUCCEEDED }

            assertNotEquals(callerThread, callbackThread.get())
            assertTrue(callbackThread.get().startsWith("feature-route-action"))
            assertEquals(RouteKind.NORMAL, routeSeen.get().kind)
            assertEquals("Sent", host.state.value.single().lastMessage)
            registration.close()
            assertTrue(host.state.value.isEmpty())
        }
    }

    @Test
    fun `busy action rejects duplicate invocation and publishes rejected or failed results`() {
        RouteActionHost().use { host ->
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            host.scopedCapability(PackId("test.pack")).register(
                provider("busy", setOf(RouteKind.NORMAL)) {
                    calls.incrementAndGet()
                    running.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    RouteActionResult(RouteActionStatus.REJECTED, "Not available")
                },
            )
            val key = host.state.value.single().key
            val route = snapshot(RouteKind.NORMAL)
            assertTrue(host.invoke(key, route))
            assertTrue(running.await(2, TimeUnit.SECONDS))
            assertTrue(host.state.value.single().busy)
            assertFalse(host.invoke(key, route))
            release.countDown()
            await { host.state.value.single().lastStatus == RouteActionStatus.REJECTED }
            assertEquals(1, calls.get())
            assertEquals("Not available", host.state.value.single().lastMessage)
        }

        val failures = mutableListOf<String>()
        RouteActionHost { packId, actionId, operation, _ ->
            synchronized(failures) { failures += "$packId:$actionId:$operation" }
        }.use { host ->
            host.scopedCapability(PackId("broken.pack")).register(
                provider("throws", setOf(RouteKind.NORMAL)) { error("boom") },
            )
            assertTrue(host.invoke(host.state.value.single().key, snapshot(RouteKind.NORMAL)))
            await { host.state.value.single().lastStatus == RouteActionStatus.FAILED }
            assertEquals("Feature Pack action failed", host.state.value.single().lastMessage)
            assertEquals(listOf("broken.pack:throws:execute"), synchronized(failures) { failures.toList() })
        }

        RouteActionHost().use { host ->
            host.scopedCapability(PackId("failed.pack")).register(
                provider("failed", setOf(RouteKind.NORMAL)) {
                    RouteActionResult(RouteActionStatus.FAILED, "Provider refused")
                },
            )
            assertTrue(host.invoke(host.state.value.single().key, snapshot(RouteKind.NORMAL)))
            await { host.state.value.single().lastStatus == RouteActionStatus.FAILED }
            assertEquals("Provider refused", host.state.value.single().lastMessage)
        }
    }

    @Test
    fun `Pack close immediately unregisters actions and ignores a late running result`() {
        val failures = mutableListOf<String>()
        RouteActionHost { packId, actionId, operation, _ ->
            synchronized(failures) { failures += "$packId:$actionId:$operation" }
        }.use { host ->
            val capability = host.scopedCapability(PackId("test.pack"))
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            capability.register(provider("slow", setOf(RouteKind.NORMAL)) {
                running.countDown()
                while (true) {
                    try {
                        release.await()
                        break
                    } catch (_: InterruptedException) {
                        // Prove that a late result cannot republish after the bounded close path.
                    }
                }
                RouteActionResult(RouteActionStatus.SUCCEEDED, "late")
            })
            assertTrue(host.invoke(host.state.value.single().key, snapshot(RouteKind.NORMAL)))
            assertTrue(running.await(2, TimeUnit.SECONDS))

            val closer = thread { capability.close() }
            await { host.state.value.isEmpty() }
            release.countDown()
            closer.join(2_000)
            assertFalse(closer.isAlive)
            assertTrue(host.state.value.isEmpty())
        }
    }

    @Test
    fun `Pack close cancels its queued action before Pack code starts`() {
        RouteActionHost().use { host ->
            val blockerCapability = host.scopedCapability(PackId("blocker.pack"))
            val queuedCapability = host.scopedCapability(PackId("queued.pack"))
            val blockerRunning = CountDownLatch(1)
            val releaseBlocker = CountDownLatch(1)
            val queuedCalls = AtomicInteger()
            blockerCapability.register(provider("block", setOf(RouteKind.NORMAL)) {
                blockerRunning.countDown()
                releaseBlocker.await(2, TimeUnit.SECONDS)
                RouteActionResult(RouteActionStatus.SUCCEEDED, null)
            })
            queuedCapability.register(provider("queued", setOf(RouteKind.NORMAL)) {
                queuedCalls.incrementAndGet()
                RouteActionResult(RouteActionStatus.SUCCEEDED, null)
            })
            val blockerKey = host.state.value.single { it.key.packId.value == "blocker.pack" }.key
            val queuedKey = host.state.value.single { it.key.packId.value == "queued.pack" }.key
            assertTrue(host.invoke(blockerKey, snapshot(RouteKind.NORMAL)))
            assertTrue(blockerRunning.await(2, TimeUnit.SECONDS))
            assertTrue(host.invoke(queuedKey, snapshot(RouteKind.NORMAL)))

            queuedCapability.close()
            releaseBlocker.countDown()
            await { host.state.value.single().lastStatus == RouteActionStatus.SUCCEEDED }

            assertEquals(0, queuedCalls.get())
            assertEquals(listOf("blocker.pack"), host.state.value.map { it.key.packId.value })
        }
    }

    @Test
    fun `multiple Pack actions coexist and capability close affects only its Pack`() {
        RouteActionHost().use { host ->
            val first = host.scopedCapability(PackId("first.pack"))
            val second = host.scopedCapability(PackId("second.pack"))
            first.register(provider("first", setOf(RouteKind.NORMAL)))
            second.register(provider("second", setOf(RouteKind.NORMAL, RouteKind.CAPITAL)))

            first.close()

            assertEquals(listOf("second.pack"), host.state.value.map { it.key.packId.value })
            assertTrue(host.invoke(host.state.value.single().key, snapshot(RouteKind.CAPITAL)))
            await { host.state.value.single().lastStatus == RouteActionStatus.SUCCEEDED }
        }
    }

    @Test
    fun `empty Host has no actions and close is idempotent`() {
        val host = RouteActionHost()
        assertTrue(host.state.value.isEmpty())
        host.close()
        host.close()
        assertTrue(host.state.value.isEmpty())
    }

    @Test
    fun `shared generic target is validated passed and refreshable`() {
        RouteActionHost().use { host ->
            val selected = AtomicReference<RouteActionTargetId?>()
            val targets = AtomicReference(targets(available = true))
            val provider = object : RouteActionProvider {
                override fun descriptor() = RouteActionDescriptor(
                    "send",
                    "Send",
                    null,
                    setOf(RouteKind.NORMAL),
                    targetSelectorId = "send-target",
                )

                override fun targets(): RouteActionTargetSnapshot = targets.get()

                override fun execute(context: RouteActionContext): RouteActionResult {
                    selected.set(context.targetId)
                    return RouteActionResult(RouteActionStatus.SUCCEEDED, null)
                }
            }
            val registration = host.scopedCapability(PackId("test.pack")).register(provider)
            val key = host.state.value.single().key
            val targetId = RouteActionTargetId("42")

            assertFalse(host.invoke(key, snapshot(RouteKind.NORMAL)))
            assertTrue(host.invoke(key, snapshot(RouteKind.NORMAL), targetId))
            await { host.state.value.single().lastStatus == RouteActionStatus.SUCCEEDED }
            assertEquals(targetId, selected.get())

            targets.set(targets(available = false))
            registration.requestTargetRefresh()
            await { host.state.value.single().targetSelector?.options?.single()?.available == false }
            assertFalse(host.invoke(key, snapshot(RouteKind.NORMAL), targetId))
        }
    }

    private fun provider(
        id: String,
        kinds: Set<RouteKind>,
        execute: (RouteActionContext) -> RouteActionResult = {
            RouteActionResult(RouteActionStatus.SUCCEEDED, null)
        },
    ) = object : RouteActionProvider {
        override fun descriptor() = RouteActionDescriptor(id, id.replaceFirstChar(Char::uppercase), null, kinds)
        override fun execute(context: RouteActionContext): RouteActionResult = execute.invoke(context)
    }

    private fun snapshot(kind: RouteKind) = RouteSnapshot(
        RouteIdentity("route-${kind.name.lowercase()}"),
        kind,
        30_000_001,
        30_000_002,
        listOf(30_000_001, 30_000_002),
        listOf(dev.evestaticmapplanner.feature.api.RouteSegment(
            30_000_001,
            30_000_002,
            if (kind == RouteKind.CAPITAL || kind == RouteKind.MISSION_CAPITAL) {
                dev.evestaticmapplanner.feature.api.RouteSegmentKind.CAPITAL_JUMP
            } else {
                dev.evestaticmapplanner.feature.api.RouteSegmentKind.STARGATE
            },
            if (kind == RouteKind.CAPITAL || kind == RouteKind.MISSION_CAPITAL) 1.0 else null,
        )),
    )

    private fun targets(available: Boolean) = RouteActionTargetSnapshot(
        "send-target",
        "Target",
        listOf(RouteActionTargetOption(RouteActionTargetId("42"), "Primary", available = available)),
    )

    private fun await(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Condition was not met within ${timeoutMillis}ms")
            Thread.sleep(5)
        }
    }
}
