package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackSession
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackVersion
import dev.evestaticmapplanner.feature.api.RouteActionContext
import dev.evestaticmapplanner.feature.api.RouteActionDescriptor
import dev.evestaticmapplanner.feature.api.RouteActionProvider
import dev.evestaticmapplanner.feature.api.RouteActionResult
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSegment
import dev.evestaticmapplanner.feature.api.RouteSegmentKind
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import java.net.URLClassLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureCapabilityLifecycleTest {
    @Test
    fun `Loaded Pack closes session then capability callback before ClassLoader`() {
        RouteActionHost().use { host ->
            val capability = host.scopedCapability(PackId("lifecycle.pack"))
            val callbackRunning = AtomicBoolean(false)
            val callbackStarted = CountDownLatch(1)
            val events = mutableListOf<String>()
            capability.register(object : RouteActionProvider {
                override fun descriptor() = RouteActionDescriptor(
                    "slow",
                    "Slow",
                    null,
                    setOf(RouteKind.NORMAL),
                )

                override fun execute(context: RouteActionContext): RouteActionResult {
                    callbackRunning.set(true)
                    callbackStarted.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        // Expected bounded Pack shutdown signal.
                    } finally {
                        callbackRunning.set(false)
                    }
                    return RouteActionResult(RouteActionStatus.REJECTED, "closed")
                }
            })
            assertTrue(host.invoke(host.state.value.single().key, snapshot()))
            assertTrue(callbackStarted.await(2, TimeUnit.SECONDS))
            val loader = object : URLClassLoader(emptyArray()) {
                override fun close() {
                    check(!callbackRunning.get()) { "ClassLoader closed while Pack callback was still executing" }
                    events += "classloader"
                    super.close()
                }
            }
            val loaded = LoadedFeaturePack(
                descriptor = FeaturePackDescriptor(
                    PackId("lifecycle.pack"),
                    "Lifecycle Pack",
                    PackVersion("1.0.0"),
                    "Tests",
                ),
                session = object : FeaturePackSession {
                    override fun close() {
                        events += "session"
                    }
                },
                contextLifecycle = FeaturePackContextLifecycle {
                    capability.close()
                    events += "capabilities"
                },
                classLoader = loader,
            )

            val result = loaded.closeSafely()

            assertIs<FeaturePackCloseResult.Closed>(result)
            assertEquals(listOf("session", "capabilities", "classloader"), events)
            assertFalse(callbackRunning.get())
            assertTrue(host.state.value.isEmpty())
        }
    }

    private fun snapshot() = RouteSnapshot(
        RouteIdentity("lifecycle-route"),
        RouteKind.NORMAL,
        1,
        2,
        listOf(1, 2),
        listOf(RouteSegment(1, 2, RouteSegmentKind.STARGATE, null)),
    )
}
