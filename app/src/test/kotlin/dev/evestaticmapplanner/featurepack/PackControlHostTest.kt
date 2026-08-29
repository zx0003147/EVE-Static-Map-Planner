package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackControlActionDescriptor
import dev.evestaticmapplanner.feature.api.PackControlActionResult
import dev.evestaticmapplanner.feature.api.PackControlActionStatus
import dev.evestaticmapplanner.feature.api.PackControlProvider
import dev.evestaticmapplanner.feature.api.PackControlSeverity
import dev.evestaticmapplanner.feature.api.PackControlSnapshot
import dev.evestaticmapplanner.feature.api.PackId
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

class PackControlHostTest {
    @Test
    fun `provider status is shown and action runs off caller thread with busy protection`() {
        PackControlHost().use { host ->
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbackThread = AtomicReference<String>()
            val calls = AtomicInteger()
            host.scopedCapability(PackId("test.pack")).register(provider(
                snapshot = { snapshot("Ready", "connect") },
                invoke = {
                    calls.incrementAndGet()
                    callbackThread.set(Thread.currentThread().name)
                    running.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    PackControlActionResult(PackControlActionStatus.SUCCEEDED, "Connected")
                },
            ))
            val key = host.state.value.single().actions.single().key

            assertTrue(host.invoke(key))
            assertTrue(running.await(2, TimeUnit.SECONDS))
            assertEquals("connect", host.state.value.single().busyActionId)
            assertFalse(host.state.value.single().actions.single().enabled)
            assertFalse(host.invoke(key))
            release.countDown()
            await { host.state.value.single().lastStatus == PackControlActionStatus.SUCCEEDED }

            assertEquals(1, calls.get())
            assertNotEquals(Thread.currentThread().name, callbackThread.get())
            assertTrue(callbackThread.get().startsWith("feature-pack-control"))
            assertEquals("Connected", host.state.value.single().lastMessage)
        }
    }

    @Test
    fun `request refresh targets one Pack and provider failure is isolated`() {
        val failures = mutableListOf<String>()
        PackControlHost { packId, _, operation, _ -> failures += "$packId:$operation" }.use { host ->
            val firstSnapshots = AtomicInteger()
            val secondSnapshots = AtomicInteger()
            val first = host.scopedCapability(PackId("first.pack")).register(provider(
                snapshot = { snapshot("First ${firstSnapshots.incrementAndGet()}") },
            ))
            host.scopedCapability(PackId("second.pack")).register(provider(
                snapshot = {
                    secondSnapshots.incrementAndGet()
                    error("broken")
                },
            ))

            first.requestRefresh()

            assertEquals(2, firstSnapshots.get())
            assertEquals(1, secondSnapshots.get())
            assertEquals("First 2", host.state.value.single { it.packId.value == "first.pack" }.primaryText)
            assertEquals(
                "Feature Pack controls unavailable",
                host.state.value.single { it.packId.value == "second.pack" }.primaryText,
            )
            assertEquals(listOf("second.pack:snapshot"), failures)
        }
    }

    @Test
    fun `close removes controls and ignores late action completion`() {
        PackControlHost().use { host ->
            val capability = host.scopedCapability(PackId("test.pack"))
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            capability.register(provider(
                snapshot = { snapshot("Ready", "slow") },
                invoke = {
                    running.countDown()
                    while (true) {
                        try {
                            release.await()
                            break
                        } catch (_: InterruptedException) {
                            // Deliberately finish late to prove the closed provider cannot republish.
                        }
                    }
                    PackControlActionResult(PackControlActionStatus.SUCCEEDED, "late")
                },
            ))
            assertTrue(host.invoke(host.state.value.single().actions.single().key))
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
    fun `empty Host and Pack without capability state remain inert`() {
        val host = PackControlHost()
        assertTrue(host.state.value.isEmpty())
        host.close()
        host.close()
        assertTrue(host.state.value.isEmpty())
    }

    private fun provider(
        snapshot: () -> PackControlSnapshot,
        invoke: (String) -> PackControlActionResult = {
            PackControlActionResult(PackControlActionStatus.SUCCEEDED, null)
        },
    ) = object : PackControlProvider {
        override fun snapshot(): PackControlSnapshot = snapshot.invoke()
        override fun invoke(actionId: String): PackControlActionResult = invoke.invoke(actionId)
    }

    private fun snapshot(primary: String, actionId: String? = null) = PackControlSnapshot(
        primary,
        null,
        PackControlSeverity.NORMAL,
        actionId?.let { listOf(PackControlActionDescriptor(it, "Action", null, true)) }.orEmpty(),
    )

    private fun await(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Condition was not met within ${timeoutMillis}ms")
            Thread.sleep(5)
        }
    }
}
