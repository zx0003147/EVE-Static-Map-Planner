package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlaySnapshot
import dev.evestaticmapplanner.feature.api.PackId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicFeatureOverlayHostTest {
    @Test
    fun `dynamic registration publishes initial snapshot and refreshes only its provider`() {
        FeatureOverlayHost().use { host ->
            val staticCalls = AtomicInteger()
            val dynamicCalls = AtomicInteger()
            val static = host.scopedRegistry(PackId("static.pack"))
            val dynamic = host.scopedDynamicCapability(PackId("dynamic.pack"))
            static.register(provider("static.provider", "static", staticCalls) { 30_000_001 })
            val dynamicSystem = AtomicInteger(30_000_002)
            val registration = dynamic.register(
                provider("dynamic.provider", "dynamic", dynamicCalls, dynamicSystem::get),
            )

            assertEquals(1, staticCalls.get())
            assertEquals(1, dynamicCalls.get())
            dynamicSystem.set(30_000_003)
            registration.requestRefresh()

            await { host.systems("dynamic.provider") == listOf(30_000_003) }
            assertEquals(1, staticCalls.get(), "dynamic refresh must not re-snapshot a static provider")
            assertEquals(2, dynamicCalls.get())
        }
    }

    @Test
    fun `scheduled requests coalesce and a request during running produces one dirty refresh`() {
        FeatureOverlayHost().use { host ->
            val calls = AtomicInteger()
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            val provider = object : OverlayProvider {
                override fun descriptor() = OverlayProviderDescriptor("coalesce.provider", "Coalesce")
                override fun layers() = listOf(OverlayLayer("status", "Status"))
                override fun snapshot(): OverlaySnapshot {
                    val call = calls.incrementAndGet()
                    if (call == 2) {
                        running.countDown()
                        release.await(2, TimeUnit.SECONDS)
                    }
                    return OverlaySnapshot(listOf(OverlayEntry("status", 30_000_000 + call)))
                }
            }
            val registration = host.scopedDynamicCapability(PackId("test.pack")).register(provider)

            repeat(5) { registration.requestRefresh() }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            repeat(5) { registration.requestRefresh() }
            release.countDown()

            await { calls.get() == 3 }
            Thread.sleep(50)
            assertEquals(3, calls.get())
            assertEquals(listOf(30_000_003), host.systems("coalesce.provider"))
        }
    }

    @Test
    fun `failed and invalid refresh preserve last-good and do not affect another provider`() {
        val failures = mutableListOf<String>()
        FeatureOverlayHost { packId, providerId, operation, _ ->
            synchronized(failures) { failures += "$packId:$providerId:$operation" }
        }.use { host ->
            val mode = AtomicInteger(0)
            val failing = object : OverlayProvider {
                override fun descriptor() = OverlayProviderDescriptor("failing.provider", "Failing")
                override fun layers() = listOf(OverlayLayer("status", "Status"))
                override fun snapshot(): OverlaySnapshot = when (mode.get()) {
                    1 -> error("temporary failure")
                    2 -> OverlaySnapshot(listOf(OverlayEntry("undeclared", 30_000_099)))
                    else -> OverlaySnapshot(listOf(OverlayEntry("status", 30_000_001 + mode.get())))
                }
            }
            val capability = host.scopedDynamicCapability(PackId("test.pack"))
            val registration = capability.register(failing)
            capability.register(provider("healthy.provider", "healthy", AtomicInteger()) { 30_000_010 })

            mode.set(1)
            registration.requestRefresh()
            await { synchronized(failures) { failures.size == 1 } }
            assertEquals(listOf(30_000_001), host.systems("failing.provider"))
            assertEquals(listOf(30_000_010), host.systems("healthy.provider"))

            mode.set(2)
            registration.requestRefresh()
            await { synchronized(failures) { failures.size == 2 } }
            assertEquals(listOf(30_000_001), host.systems("failing.provider"))

            mode.set(3)
            registration.requestRefresh()
            await { host.systems("failing.provider") == listOf(30_000_004) }
        }
    }

    @Test
    fun `close removes contribution and ignores running or later refresh results`() {
        FeatureOverlayHost().use { host ->
            val calls = AtomicInteger()
            val running = CountDownLatch(1)
            val release = CountDownLatch(1)
            val provider = object : OverlayProvider {
                override fun descriptor() = OverlayProviderDescriptor("close.provider", "Close")
                override fun layers() = listOf(OverlayLayer("status", "Status"))
                override fun snapshot(): OverlaySnapshot {
                    val call = calls.incrementAndGet()
                    if (call == 2) {
                        running.countDown()
                        while (true) {
                            try {
                                release.await()
                                break
                            } catch (_: InterruptedException) {
                                // Simulate a badly behaved Pack long enough to prove late result isolation.
                            }
                        }
                    }
                    return OverlaySnapshot(listOf(OverlayEntry("status", 30_000_000 + call)))
                }
            }
            val registration = host.scopedDynamicCapability(PackId("test.pack")).register(provider)
            registration.requestRefresh()
            assertTrue(running.await(2, TimeUnit.SECONDS))
            val closeFailure = AtomicReference<Throwable?>()
            val closer = thread {
                runCatching(registration::close).onFailure(closeFailure::set)
            }
            await { host.state.value.isEmpty }
            release.countDown()
            closer.join(2_000)
            assertFalse(closer.isAlive)
            assertEquals(null, closeFailure.get())
            registration.requestRefresh()
            Thread.sleep(50)
            assertTrue(host.state.value.isEmpty)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `Pack capability close removes all dynamic providers and blocks new registrations`() {
        FeatureOverlayHost().use { host ->
            val capability = host.scopedDynamicCapability(PackId("test.pack"))
            capability.register(provider("one.provider", "one", AtomicInteger()) { 30_000_001 })
            capability.register(provider("two.provider", "two", AtomicInteger()) { 30_000_002 })

            capability.close()

            assertTrue(host.state.value.isEmpty)
            assertTrue(runCatching {
                capability.register(provider("three.provider", "three", AtomicInteger()) { 30_000_003 })
            }.isFailure)
        }
    }

    private fun provider(
        providerId: String,
        layerId: String,
        calls: AtomicInteger,
        systemId: () -> Int,
    ) = object : OverlayProvider {
        override fun descriptor() = OverlayProviderDescriptor(providerId, providerId)
        override fun layers() = listOf(OverlayLayer(layerId, layerId))
        override fun snapshot(): OverlaySnapshot {
            calls.incrementAndGet()
            return OverlaySnapshot(listOf(OverlayEntry(layerId, systemId())))
        }
    }

    private fun FeatureOverlayHost.systems(providerId: String): List<Int> = state.value.layers
        .filter { it.provider.id == providerId }
        .flatMap { it.entries }
        .map(OverlayEntry::systemId)

    private fun await(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Condition was not met within ${timeoutMillis}ms")
            Thread.sleep(5)
        }
    }
}
