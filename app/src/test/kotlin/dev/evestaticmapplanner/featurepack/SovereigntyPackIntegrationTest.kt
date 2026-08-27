package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.PackId
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SovereigntyPackIntegrationTest {
    @Test
    fun `canonical Sovereignty Pack artifact declares Host-supported Feature API v1`() {
        JarFile(sovereigntyPackJar.toFile()).use { jar ->
            assertEquals(
                "1",
                jar.manifest.mainAttributes.getValue(FeaturePackJarManifest.FEATURE_API_VERSION),
            )
            assertEquals(
                dev.evestaticmapplanner.feature.api.FeatureApiVersions.current().identifier,
                jar.manifest.mainAttributes.getValue(FeaturePackJarManifest.FEATURE_API_VERSION),
            )
        }
    }

    @Test
    fun `external Pack appears in manager and ServiceLoader constructs its production entrypoint`() =
        withTempDirectory { root ->
            val packRoot = root.resolve("feature-packs")
            val destination = packRoot.resolve("sovereignty.pack/pack.jar")
            destination.parent.createDirectories()
            Files.copy(sovereigntyPackJar, destination)
            val manager = FeaturePackManager(
                packRoot = packRoot,
                registry = LocalFeaturePackRegistry(
                    packRoot,
                    PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties")),
                ),
                contextFactory = FeaturePackContextFactory {
                    error("Construction-only regression must not start the production Pack")
                },
            )

            val installed = manager.refresh().packs.single()
            assertEquals(PackId("sovereignty.pack"), installed.packId)
            assertEquals("Sovereignty Pack", installed.displayName)
            assertEquals(FeaturePackRuntimeState.DISABLED, manager.state.value.packs.single().runtimeState)

            URLClassLoader(
                arrayOf(destination.toUri().toURL()),
                FeaturePackEntrypoint::class.java.classLoader,
            ).use { classLoader ->
                val entrypoint = ServiceLoader.load(FeaturePackEntrypoint::class.java, classLoader).single()
                assertEquals(
                    "dev.evestaticmapplanner.sovereignty.SovereigntyFeaturePack",
                    entrypoint.javaClass.name,
                )
                assertEquals(installed.packId, entrypoint.descriptor().packId)
                assertEquals(installed.displayName, entrypoint.descriptor().displayName)
            }
        }

    @Test
    fun `external Pack starts production Public ESI client path from fresh cache without Internet`() =
        withTempDirectory { root ->
            val packRoot = root.resolve("feature-packs")
            val destination = packRoot.resolve("sovereignty.pack/pack.jar")
            destination.parent.createDirectories()
            Files.copy(sovereigntyPackJar, destination)
            val cache = root.resolve(
                "feature-pack-storage/sovereignty.pack/cache/public-esi-lkg.json",
            )
            cache.parent.createDirectories()
            Files.writeString(cache, VALID_PUBLIC_ESI_CACHE)
            PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties")).save(
                mapOf(PackId("sovereignty.pack") to StoredFeaturePackState(enabled = true)),
            )
            val events = mutableListOf<String>()

            val runtime = ProductionFeaturePackRuntime.start(packRoot, root, events::add)
            try {
                assertEquals(listOf(PackId("sovereignty.pack")), runtime.startReport.loadedPackIds)
                assertTrue(runtime.startReport.failures.isEmpty())
                assertTrue(events.contains("INFO:sovereignty.pack:Sovereignty Pack started"))
            } finally {
                assertTrue(runtime.closeSafely().failures.isEmpty())
            }
            assertTrue(events.contains("INFO:sovereignty.pack:Sovereignty Pack stopped"))
        }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("sv-3c-1-integration-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        val sovereigntyPackJar: Path
            get() = Path.of(requireNotNull(System.getProperty("sovereignty.pack.fixture.jar")))
        val VALID_PUBLIC_ESI_CACHE = """
            {
              "formatVersion": 1,
              "source": "PUBLIC_ESI",
              "records": [
                {"systemId": 30004759, "allianceName": "Cached Alliance", "corporationName": null, "sovereigntyStatus": "Claimed"}
              ]
            }
        """.trimIndent()
    }
}
