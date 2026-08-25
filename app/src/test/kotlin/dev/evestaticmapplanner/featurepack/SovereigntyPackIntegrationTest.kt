package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.CoreVersion
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackHostInfo
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.HostPlatform
import dev.evestaticmapplanner.feature.api.OverlayRegistry
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import dev.evestaticmapplanner.feature.api.SystemInfoRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SovereigntyPackIntegrationTest {
    @Test
    fun `external Pack appears in manager and enable-disable controls both providers`() =
        withTempDirectory { root ->
            val packRoot = root.resolve("feature-packs")
            val destination = packRoot.resolve("sovereignty.pack/pack.jar")
            destination.parent.createDirectories()
            Files.copy(sovereigntyPackJar, destination)

            val overlays = FeatureOverlayHost()
            val systemInfo = SystemInfoHost()
            val events = mutableListOf<String>()
            val manager = FeaturePackManager(
                packRoot = packRoot,
                registry = LocalFeaturePackRegistry(
                    packRoot,
                    PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties")),
                ),
                contextFactory = FeaturePackContextFactory { descriptor ->
                    TestPackContext(
                        root.resolve("storage/${descriptor.packId.value}"),
                        events,
                        overlays.scopedRegistry(descriptor.packId),
                        systemInfo.scopedRegistry(descriptor.packId),
                    )
                },
            )

            val installed = manager.refresh().packs.single()
            assertEquals(PackId("sovereignty.pack"), installed.packId)
            assertEquals("Sovereignty Pack", installed.displayName)
            assertEquals(FeaturePackRuntimeState.DISABLED, manager.state.value.packs.single().runtimeState)

            assertTrue(manager.setEnabled(installed.packId, true).isSuccess)
            assertEquals(FeaturePackRuntimeState.ENABLED, manager.state.value.packs.single().runtimeState)
            with(overlays.state.value.layers.single()) {
                assertEquals("Sovereignty", layer.name)
                assertEquals("Goonswarm Federation", entries.first { it.systemId == 30_004_759 }.title)
                assertEquals("ring-color:#CCF2C94C", entries.first { it.systemId == 30_004_759 }.value)
            }
            with(systemInfo.request(30_004_759).sections.single()) {
                assertEquals("Sovereignty", title)
                assertEquals("Goonswarm Federation", fields.first { it.key == "owner" }.value)
            }

            assertTrue(manager.setEnabled(installed.packId, false).isSuccess)
            assertEquals(FeaturePackRuntimeState.DISABLED, manager.state.value.packs.single().runtimeState)
            assertTrue(overlays.state.value.isEmpty)
            assertTrue(systemInfo.state.value.isEmpty)
            assertFalse(manager.state.value.packs.single().pack.enabled)
            assertEquals(listOf("INFO:Sovereignty Pack started", "INFO:Sovereignty Pack stopped"), events)
        }

    private class TestPackContext(
        private val storageRoot: Path,
        private val events: MutableList<String>,
        private val overlayRegistry: OverlayRegistry,
        private val systemInfoRegistry: SystemInfoRegistry,
    ) : FeaturePackContext {
        override fun hostInfo() = FeaturePackHostInfo(
            CoreVersion(0, 3, 0),
            FeatureApiVersions.current(),
            HostPlatform("windows", "x64"),
        )

        override fun storage(): PackStorage = object : PackStorage {
            override fun dataPath(relativePath: PackRelativePath): Path = storageRoot.resolve("data").resolve(relativePath.toPath())
            override fun configPath(relativePath: PackRelativePath): Path = storageRoot.resolve("config").resolve(relativePath.toPath())
            override fun cachePath(relativePath: PackRelativePath): Path = storageRoot.resolve("cache").resolve(relativePath.toPath())
        }

        override fun logger(): FeaturePackLogger = object : FeaturePackLogger {
            override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) {
                events += "${level.name}:$message"
            }
        }

        override fun overlays(): OverlayRegistry = overlayRegistry

        override fun systemInfo(): SystemInfoRegistry = systemInfoRegistry
    }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("sv-1-integration-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        val sovereigntyPackJar: Path
            get() = Path.of(requireNotNull(System.getProperty("sovereignty.pack.fixture.jar")))
    }
}
