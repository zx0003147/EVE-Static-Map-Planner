package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.CoreVersion
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackHostInfo
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.HostPlatform
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import dev.evestaticmapplanner.feature.api.OverlayRegistry
import dev.evestaticmapplanner.feature.api.SystemInfoRegistration
import dev.evestaticmapplanner.feature.api.SystemInfoRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeaturePackManagerTest {
    @Test
    fun `registry discovers manifest metadata missing jars and stored state without loading Pack`() =
        withTempDirectory { root ->
            installFixture(root)
            root.resolve("feature-packs/missing-pack").createDirectories()
            val stateStore = stateStore(root)
            val registry = LocalFeaturePackRegistry(root.resolve("feature-packs"), stateStore)

            val initial = registry.discover()
            val fixture = initial.packs.first { it.packId == PackId("fixture.pack") }

            assertEquals("Minimal Fixture Pack", fixture.displayName)
            assertEquals("0.0.1-test", fixture.version?.value)
            assertEquals("EVE Static Map Planner Tests", fixture.publisher)
            assertFalse(fixture.enabled)
            assertEquals(FeaturePackInstallationState.INSTALLED, fixture.installationState)
            assertEquals(
                FeaturePackInstallationState.MISSING_JAR,
                initial.packs.first { it.packId == PackId("missing-pack") }.installationState,
            )

            registry.updateState(PackId("fixture.pack"), StoredFeaturePackState(true, "previous error"))
            val restored = registry.discover().packs.first { it.packId == PackId("fixture.pack") }
            assertTrue(restored.enabled)
            assertEquals("previous error", restored.lastError)
        }

    @Test
    fun `disabled Pack does not load while enable and disable coordinate its lifecycle`() = withTempDirectory { root ->
        installFixture(root)
        val events = mutableListOf<String>()
        val overlayHost = FeatureOverlayHost()
        val manager = manager(root, events, overlayHost)

        manager.refresh()
        assertTrue(events.isEmpty())
        assertTrue(overlayHost.state.value.isEmpty)
        assertEquals(FeaturePackRuntimeState.DISABLED, manager.state.value.packs.single().runtimeState)

        assertTrue(manager.setEnabled(PackId("fixture.pack"), true).isSuccess)
        assertEquals(listOf("INFO:Fixture Pack started"), events)
        assertEquals("fixture", overlayHost.state.value.layers.single().layer.id)
        assertEquals(30_000_142, overlayHost.state.value.layers.single().entries.single().systemId)
        assertEquals(FeaturePackRuntimeState.ENABLED, manager.state.value.packs.single().runtimeState)

        assertTrue(manager.setEnabled(PackId("fixture.pack"), false).isSuccess)
        assertEquals(listOf("INFO:Fixture Pack started", "INFO:Fixture Pack stopped"), events)
        assertTrue(overlayHost.state.value.isEmpty)
        assertEquals(FeaturePackRuntimeState.DISABLED, manager.state.value.packs.single().runtimeState)
        assertFalse(manager.state.value.packs.single().pack.enabled)
    }

    @Test
    fun `restart preserves enable state and loads only enabled Packs`() = withTempDirectory { root ->
        installFixture(root)
        val firstEvents = mutableListOf<String>()
        val first = manager(root, firstEvents)
        assertTrue(first.setEnabled(PackId("fixture.pack"), true).isSuccess)
        assertTrue(first.closeSafely().isEmpty())

        val secondEvents = mutableListOf<String>()
        val second = manager(root, secondEvents)
        val start = second.startEnabledPacks()

        assertEquals(listOf(PackId("fixture.pack")), start.loadedPackIds)
        assertEquals(listOf("INFO:Fixture Pack started"), secondEvents)
        assertTrue(second.state.value.packs.single().pack.enabled)
        second.closeSafely()
    }

    @Test
    fun `broken Pack is isolated and its error survives refresh and restart`() = withTempDirectory { root ->
        installFixture(root)
        rewriteFixtureJar(
            destination = root.resolve("feature-packs/fixture.startup-failure/pack.jar"),
            provider = STARTUP_FAILURE_PROVIDER,
            packId = "fixture.startup-failure",
            displayName = "Startup Failure Fixture Pack",
        )
        val events = mutableListOf<String>()
        val manager = manager(root, events)

        assertTrue(manager.setEnabled(PackId("fixture.pack"), true).isSuccess)
        val broken = manager.setEnabled(PackId("fixture.startup-failure"), true)

        assertTrue(broken.isFailure)
        assertEquals(FeaturePackRuntimeState.ENABLED, manager.state.value.packs.first {
            it.pack.packId == PackId("fixture.pack")
        }.runtimeState)
        val storedError = manager.state.value.packs.first {
            it.pack.packId == PackId("fixture.startup-failure")
        }.pack.lastError
        assertNotNull(storedError)
        assertTrue(storedError.contains("startup failed", ignoreCase = true))

        val restarted = manager(root, mutableListOf())
        restarted.refresh()
        assertEquals(storedError, restarted.state.value.packs.first {
            it.pack.packId == PackId("fixture.startup-failure")
        }.pack.lastError)
        manager.closeSafely()
    }

    @Test
    fun `registry and manager expose an understandable down-level Feature API state`() =
        withTempDirectory { root ->
            rewriteFixtureJar(
                destination = root.resolve("feature-packs/fixture.future/pack.jar"),
                provider = STARTUP_FAILURE_PROVIDER,
                packId = "fixture.future",
                displayName = "Future Fixture Pack",
                featureApiVersion = "1",
            )
            val manager = manager(root, mutableListOf())

            val snapshot = manager.refresh()
            val pack = snapshot.packs.single()

            assertEquals(FeaturePackInstallationState.INCOMPATIBLE, pack.installationState)
            assertEquals("1", pack.requiredFeatureApiVersion?.identifier)
            assertTrue(pack.lastError.orEmpty().contains("requires Feature API 1"))
            assertTrue(pack.lastError.orEmpty().contains("provides Feature API 2"))
            assertEquals(FeaturePackFailureKind.INCOMPATIBLE_FEATURE_API, snapshot.failures.single().kind)
            assertEquals(FeaturePackRuntimeState.DISABLED, manager.state.value.packs.single().runtimeState)
        }

    @Test
    fun `registry surfaces malformed Feature API metadata as an understandable invalid Pack`() =
        withTempDirectory { root ->
            rewriteFixtureJar(
                destination = root.resolve("feature-packs/fixture.invalid-metadata/pack.jar"),
                provider = STARTUP_FAILURE_PROVIDER,
                packId = "fixture.invalid-metadata",
                displayName = "Invalid Metadata Fixture Pack",
                featureApiVersion = "",
            )

            val item = manager(root, mutableListOf()).apply { refresh() }.state.value.packs.single()

            assertEquals(FeaturePackInstallationState.INVALID_PACK, item.pack.installationState)
            assertTrue(item.pack.lastError.orEmpty().contains(FeaturePackJarManifest.FEATURE_API_VERSION))
            assertTrue(item.pack.lastError.orEmpty().contains("positive decimal integer"))
            assertEquals(FeaturePackRuntimeState.DISABLED, item.runtimeState)
        }

    @Test
    fun `compatible Pack still loads when another enabled Pack is incompatible`() = withTempDirectory { root ->
        installFixture(root)
        rewriteFixtureJar(
            destination = root.resolve("feature-packs/fixture.future/pack.jar"),
            provider = STARTUP_FAILURE_PROVIDER,
            packId = "fixture.future",
            displayName = "Future Fixture Pack",
            featureApiVersion = "1",
        )
        stateStore(root).save(
            mapOf(
                PackId("fixture.pack") to StoredFeaturePackState(enabled = true),
                PackId("fixture.future") to StoredFeaturePackState(enabled = true),
            ),
        )
        val events = mutableListOf<String>()
        val manager = manager(root, events)

        val result = manager.startEnabledPacks()

        assertEquals(listOf(PackId("fixture.pack")), result.loadedPackIds)
        assertTrue(result.failures.any { it.kind == FeaturePackFailureKind.INCOMPATIBLE_FEATURE_API })
        assertEquals(listOf("INFO:Fixture Pack started"), events)
        assertEquals(
            FeaturePackRuntimeState.ENABLED,
            manager.state.value.packs.single { it.pack.packId == PackId("fixture.pack") }.runtimeState,
        )
        assertEquals(
            FeaturePackRuntimeState.DISABLED,
            manager.state.value.packs.single { it.pack.packId == PackId("fixture.future") }.runtimeState,
        )
        manager.closeSafely()
    }

    @Test
    fun `ViewModel exposes lazy state actions and removal deletes only the installed Pack`() =
        withTempDirectory { root ->
            val packDirectory = installFixture(root).parent
            val viewModel = FeaturePackManagerViewModel(manager(root, mutableListOf()))

            assertFalse(viewModel.state.value.initialized)
            viewModel.refresh()
            assertEquals(listOf("fixture.pack"), viewModel.state.value.packs.map { it.pack.packId.value })
            assertTrue(viewModel.setEnabled(PackId("fixture.pack"), true).isSuccess)
            assertEquals(FeaturePackRuntimeState.ENABLED, viewModel.state.value.packs.single().runtimeState)

            assertTrue(viewModel.remove(PackId("fixture.pack")).isSuccess)
            assertFalse(Files.exists(packDirectory))
            assertTrue(viewModel.state.value.packs.isEmpty())
            assertTrue(Files.isDirectory(root.resolve("feature-packs")))
        }

    private fun manager(
        root: Path,
        events: MutableList<String>,
        overlayHost: FeatureOverlayHost = FeatureOverlayHost(),
    ): FeaturePackManager {
        val packRoot = root.resolve("feature-packs")
        return FeaturePackManager(
            packRoot = packRoot,
            registry = LocalFeaturePackRegistry(packRoot, stateStore(root)),
            contextFactory = FeaturePackContextFactory { descriptor ->
                TestFeaturePackContext(
                    root.resolve("storage/${descriptor.packId.value}"),
                    events,
                    overlayHost.scopedRegistry(descriptor.packId),
                )
            },
        )
    }

    private fun stateStore(root: Path) =
        PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties"))

    private fun installFixture(root: Path): Path {
        val destination = root.resolve("feature-packs/fixture.pack/pack.jar")
        destination.parent.createDirectories()
        return Files.copy(fixtureJar, destination)
    }

    private fun rewriteFixtureJar(
        destination: Path,
        provider: String,
        packId: String,
        displayName: String,
        featureApiVersion: String = "2",
    ) {
        destination.parent.createDirectories()
        JarFile(fixtureJar.toFile()).use { source ->
            val manifest = source.manifest.apply {
                mainAttributes.putValue(FeaturePackJarManifest.PACK_ID, packId)
                mainAttributes.putValue(FeaturePackJarManifest.DISPLAY_NAME, displayName)
                mainAttributes.putValue(FeaturePackJarManifest.FEATURE_API_VERSION, featureApiVersion)
            }
            JarOutputStream(Files.newOutputStream(destination), manifest).use { output ->
                source.entries().asSequence()
                    .filterNot { it.name == SERVICE_ENTRY || it.name.equals("META-INF/MANIFEST.MF", true) }
                    .forEach { sourceEntry ->
                        output.putNextEntry(JarEntry(sourceEntry.name).apply { time = sourceEntry.time })
                        if (!sourceEntry.isDirectory) source.getInputStream(sourceEntry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                output.putNextEntry(JarEntry(SERVICE_ENTRY))
                output.write("$provider\n".toByteArray())
                output.closeEntry()
            }
        }
    }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("fp-3-manager-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class TestFeaturePackContext(
        private val storageRoot: Path,
        private val events: MutableList<String>,
        private val overlayRegistry: OverlayRegistry,
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

        override fun systemInfo(): SystemInfoRegistry = SystemInfoRegistry { NoOpSystemInfoRegistration }
    }

    private object NoOpSystemInfoRegistration : SystemInfoRegistration {
        override fun refresh() = Unit
        override fun close() = Unit
    }

    private companion object {
        val fixtureJar: Path
            get() = Path.of(requireNotNull(System.getProperty("feature.pack.fixture.jar")))
        const val SERVICE_ENTRY = "META-INF/services/dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint"
        const val STARTUP_FAILURE_PROVIDER =
            "dev.evestaticmapplanner.feature.fixture.StartupFailureFixturePack"
    }
}
