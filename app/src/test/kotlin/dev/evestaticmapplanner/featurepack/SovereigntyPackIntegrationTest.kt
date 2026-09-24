package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.preferences.OverlayManagementUiStateBuilder
import dev.evestaticmapplanner.preferences.OverlayVisibilityPreferences
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SovereigntyPackIntegrationTest {
    @Test
    fun `supplied external artifact is the readable Sovereignty Pack for Feature API v2`() {
        val packJar = validatedSovereigntyPackJar()

        JarFile(packJar.toFile()).use { jar ->
            assertEquals(
                "sovereignty.pack",
                jar.manifest.mainAttributes.getValue(FeaturePackJarManifest.PACK_ID),
            )
            assertEquals(
                "2",
                jar.manifest.mainAttributes.getValue(FeaturePackJarManifest.FEATURE_API_VERSION),
            )
            assertEquals(
                FeatureApiVersions.current().identifier,
                jar.manifest.mainAttributes.getValue(FeaturePackJarManifest.FEATURE_API_VERSION),
            )
        }
    }

    @Test
    fun `external Pack appears in manager and declares exactly one public entrypoint`() =
        withTempDirectory { root ->
            val packJar = validatedSovereigntyPackJar()
            val packRoot = root.resolve("feature-packs")
            val destination = packRoot.resolve("sovereignty.pack/pack.jar")
            destination.parent.createDirectories()
            Files.copy(packJar, destination)
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
                val entrypoints = ServiceLoader.load(FeaturePackEntrypoint::class.java, classLoader).toList()
                assertEquals(1, entrypoints.size)
                val entrypoint = entrypoints.single()
                assertEquals(installed.packId, entrypoint.descriptor().packId)
                assertEquals(installed.displayName, entrypoint.descriptor().displayName)
            }
        }

    @Test
    fun `external Pack starts from fresh cache and registers then removes Host contributions`() =
        withTempDirectory { root ->
            val packJar = validatedSovereigntyPackJar()
            val packRoot = root.resolve("feature-packs")
            val destination = packRoot.resolve("sovereignty.pack/pack.jar")
            destination.parent.createDirectories()
            Files.copy(packJar, destination)
            val cache = root.resolve(
                "feature-pack-storage/sovereignty.pack/cache/public-esi-lkg.json",
            )
            cache.parent.createDirectories()
            Files.writeString(cache, VALID_PUBLIC_ESI_CACHE)
            Files.writeString(
                cache.parent.resolve("alliance-metadata-lkg.json"),
                VALID_ALLIANCE_METADATA_CACHE,
            )
            PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties")).save(
                mapOf(PackId("sovereignty.pack") to StoredFeaturePackState(enabled = true)),
            )
            val events = mutableListOf<String>()
            var classLoaderCreations = 0
            val host = LocalFeaturePackHost(
                FeaturePackEntrypoint::class.java.classLoader,
                FeaturePackClassLoaderFactory { jarUrl, parent ->
                    classLoaderCreations += 1
                    URLClassLoader(arrayOf(jarUrl), parent)
                },
            )

            val runtime = ProductionFeaturePackRuntime.start(packRoot, root, events::add, host)
            try {
                assertEquals(listOf(PackId("sovereignty.pack")), runtime.startReport.loadedPackIds)
                assertTrue(runtime.startReport.failures.isEmpty())
                assertEquals(1, classLoaderCreations)
                assertTrue(events.contains("INFO:sovereignty.pack:Sovereignty Pack started"))

                val overlay = runtime.overlayHost.state.value.layers.single()
                assertEquals("sovereignty.pack.overlay", overlay.provider.id)
                assertEquals("sovereignty", overlay.layer.id)
                assertEquals(30004759, overlay.entries.single().systemId)
                assertEquals("Cached Alliance", overlay.entries.single().title)
                assertTrue(
                    OverlayManagementUiStateBuilder.build(
                        runtime.overlayHost.state.value,
                        OverlayVisibilityPreferences.Defaults,
                    ).showSovereigntyLogoPreferences,
                )

                val systemInfo = runtime.systemInfoHost.request(30004759)
                val fields = systemInfo.sections.single().fields.associate { it.key to it.value }
                assertEquals("Cached Alliance", fields["owner"])
                assertEquals("Claimed", fields["status"])

                val directory = runtime.allianceDirectoryHost.state.value
                assertTrue(directory.providerAvailable)
                val alliance = directory.snapshot.alliancesById.getValue(99000001L)
                assertEquals("Cached Alliance", alliance.name)
                assertEquals("CACHE", alliance.ticker)
                assertEquals(
                    setOf("feature-pack:sovereignty.pack"),
                    directory.snapshot.entriesById.getValue(99000001L).sources,
                )
                val sovereignty = runtime.sovereigntyHost.state.value
                assertTrue(sovereignty.providerAvailable)
                assertEquals(SovereigntyFreshness.AVAILABLE, sovereignty.snapshot.freshness)
                assertEquals(99000001L, sovereignty.snapshot.getOwnership(30004759)?.allianceId)
                assertEquals("Cached Alliance", sovereignty.snapshot.getOwnership(30004759)?.allianceName)
                assertTrue(events.contains("INFO:sovereignty.pack:Using fresh cached PUBLIC_ESI sovereignty snapshot"))
                assertFalse(events.any { it.contains("attempting one startup refresh", ignoreCase = true) })
                assertFalse(events.any { it.contains("startup refresh succeeded", ignoreCase = true) })
            } finally {
                assertTrue(runtime.closeSafely().failures.isEmpty())
            }
            assertTrue(events.contains("INFO:sovereignty.pack:Sovereignty Pack stopped"))
            assertTrue(runtime.overlayHost.state.value.layers.isEmpty())
            assertTrue(runtime.systemInfoHost.request(30004759).sections.isEmpty())
            assertFalse(runtime.allianceDirectoryHost.state.value.providerAvailable)
            assertTrue(runtime.allianceDirectoryHost.state.value.snapshot.alliancesById.isEmpty())
            assertFalse(runtime.sovereigntyHost.state.value.providerAvailable)
            assertEquals(SovereigntyFreshness.UNAVAILABLE, runtime.sovereigntyHost.state.value.snapshot.freshness)
        }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("split-4-integration-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        fun validatedSovereigntyPackJar(): Path {
            val configured = requireNotNull(System.getProperty(SOVEREIGNTY_PACK_JAR_PROPERTY)) {
                "External Sovereignty Pack integration requires -PsovereigntyPackJar=<absolute pack.jar path>"
            }
            val packJar = Path.of(configured).toAbsolutePath().normalize()
            assertTrue(Files.isRegularFile(packJar), "External Sovereignty Pack JAR does not exist: $packJar")
            assertTrue(Files.isReadable(packJar), "External Sovereignty Pack JAR is not readable: $packJar")
            val metadata = FeaturePackJarManifest.read(packJar)
            assertEquals(PackId("sovereignty.pack"), metadata.packId)
            assertEquals("2", metadata.requiredFeatureApiVersion.identifier)
            return packJar
        }

        const val SOVEREIGNTY_PACK_JAR_PROPERTY = "sovereignty.pack.jar"
        val VALID_PUBLIC_ESI_CACHE = """
            {
              "formatVersion": 2,
              "source": "PUBLIC_ESI",
              "records": [
                {"systemId": 30004759, "allianceId": 99000001, "allianceName": "Cached Alliance", "corporationName": null, "sovereigntyStatus": "Claimed"}
              ]
            }
        """.trimIndent()
        val VALID_ALLIANCE_METADATA_CACHE = """
            {
              "formatVersion": 1,
              "source": "PUBLIC_ESI_ALLIANCE_METADATA",
              "records": [
                {
                  "allianceId": 99000001,
                  "name": "Cached Alliance",
                  "ticker": "CACHE",
                  "observedSovereigntyName": "Cached Alliance",
                  "etag": "fixture-etag",
                  "lastModified": "Wed, 23 Sep 2026 10:00:00 GMT",
                  "cacheControl": "max-age=315360000",
                  "freshUntilEpochMillis": 4102444800000,
                  "lastSuccessfulAtEpochMillis": 1790164800000
                }
              ]
            }
        """.trimIndent()
    }
}
