package dev.evestaticmapplanner.feature.api

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeaturePackLifecycleTest {
    @Test
    fun `entrypoint can start log use scoped storage and close`() {
        val events = mutableListOf<String>()
        val context = TestContext(events)
        val entrypoint = object : FeaturePackEntrypoint {
            override fun descriptor() = FeaturePackDescriptor(
                PackId("test.pack"),
                "Test Pack",
                PackVersion("1.0.0"),
                "Tests",
            )

            override fun start(context: FeaturePackContext): FeaturePackSession {
                context.logger().log(FeaturePackLogLevel.INFO, "started", null)
                assertEquals(Path.of("pack/config/settings.json"), context.storage().configPath(
                    PackRelativePath("settings.json"),
                ))
                return object : FeaturePackSession {
                    override fun close() {
                        events += "closed"
                    }
                }
            }
        }

        val session = entrypoint.start(context)
        session.close()

        assertEquals(listOf("INFO:started", "closed"), events)
        assertTrue(entrypoint.descriptor().packId == PackId("test.pack"))
        assertEquals(null, context.capabilities().find(StandardFeatureCapabilities.DYNAMIC_OVERLAY))
    }

    private class TestContext(private val events: MutableList<String>) : FeaturePackContext {
        override fun hostInfo() = FeaturePackHostInfo(
            CoreVersion(0, 3, 0),
            FeatureApiVersions.current(),
            HostPlatform("windows", "x64"),
        )

        override fun storage() = object : PackStorage {
            private val root = Path.of("pack")

            override fun dataPath(relativePath: PackRelativePath): Path = root.resolve("data").resolve(relativePath.toPath())

            override fun configPath(relativePath: PackRelativePath): Path =
                root.resolve("config").resolve(relativePath.toPath())

            override fun cachePath(relativePath: PackRelativePath): Path = root.resolve("cache").resolve(relativePath.toPath())
        }

        override fun logger() = object : FeaturePackLogger {
            override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) {
                events += "$level:$message"
            }
        }

        override fun overlays() = OverlayRegistry { NoOpOverlayRegistration }

        override fun systemInfo() = SystemInfoRegistry { NoOpSystemInfoRegistration }
    }

    private object NoOpOverlayRegistration : OverlayRegistration {
        override fun close() = Unit
    }

    private object NoOpSystemInfoRegistration : SystemInfoRegistration {
        override fun refresh() = Unit
        override fun close() = Unit
    }
}
