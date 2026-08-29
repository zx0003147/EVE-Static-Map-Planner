package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.PackId
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalFeaturePacksIntegrationTest {
    @Test
    fun `Sovereignty and ESI Packs coexist with isolated capabilities and no disconnected ESI worker`() {
        val root = createTempDirectory("external-packs-integration-")
        try {
            val packRoot = root.resolve("feature-packs")
            copyPack("sovereignty.pack.jar", packRoot.resolve("sovereignty.pack/pack.jar"))
            copyPack("esi.pack.jar", packRoot.resolve("esi.pack/pack.jar"))
            val cache = root.resolve("feature-pack-storage/sovereignty.pack/cache/public-esi-lkg.json")
            cache.parent.createDirectories()
            Files.writeString(cache, VALID_PUBLIC_ESI_CACHE)
            PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties")).save(
                mapOf(
                    PackId("sovereignty.pack") to StoredFeaturePackState(enabled = true),
                    PackId("esi.pack") to StoredFeaturePackState(enabled = true),
                ),
            )
            val classLoaders = mutableListOf<TrackingClassLoader>()
            val events = mutableListOf<String>()
            val host = LocalFeaturePackHost(
                FeaturePackEntrypoint::class.java.classLoader,
                FeaturePackClassLoaderFactory { jarUrl, parent ->
                    TrackingClassLoader(arrayOf(jarUrl), parent).also(classLoaders::add)
                },
            )

            val runtime = ProductionFeaturePackRuntime.start(packRoot, root, events::add, host)
            try {
                assertEquals(
                    setOf(PackId("sovereignty.pack"), PackId("esi.pack")),
                    runtime.startReport.loadedPackIds.toSet(),
                )
                assertTrue(runtime.startReport.failures.isEmpty())
                assertEquals(2, classLoaders.size)
                assertEquals(
                    setOf("sovereignty.pack.overlay", "esi.character-location"),
                    runtime.overlayHost.state.value.layers.map { it.provider.id }.toSet(),
                )
                val fields = runtime.systemInfoHost.request(30004759).sections.single().fields.associate { it.key to it.value }
                assertEquals("Cached Alliance", fields["owner"])
                assertEquals(listOf(PackId("esi.pack")), runtime.packControlHost.state.value.map { it.packId })
                assertEquals("Not connected", runtime.packControlHost.state.value.single().secondaryText)
                assertEquals(setOf("esi.pack"), runtime.routeActionHost.state.value.map { it.key.packId.value }.toSet())
                assertFalse(liveThreads().any { it.name == "eve-esi-location" })
                assertFalse(events.any { it.contains("attempting one startup refresh", ignoreCase = true) })
            } finally {
                assertTrue(runtime.closeSafely().failures.isEmpty())
            }
            assertTrue(runtime.overlayHost.state.value.layers.isEmpty())
            assertTrue(runtime.packControlHost.state.value.isEmpty())
            assertTrue(classLoaders.all { it.closed })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun copyPack(property: String, destination: Path) {
        val source = Path.of(requireNotNull(System.getProperty(property))).toAbsolutePath().normalize()
        assertTrue(Files.isRegularFile(source), "External Pack artifact does not exist: $source")
        destination.parent.createDirectories()
        Files.copy(source, destination)
    }

    private fun liveThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter(Thread::isAlive).toSet()

    private class TrackingClassLoader(urls: Array<java.net.URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        var closed = false
            private set

        override fun close() {
            super.close()
            closed = true
        }
    }

    private companion object {
        val VALID_PUBLIC_ESI_CACHE = """
            {
              "formatVersion": 2,
              "source": "PUBLIC_ESI",
              "records": [
                {"systemId": 30004759, "allianceId": 99000001, "allianceName": "Cached Alliance", "corporationName": null, "sovereigntyStatus": "Claimed"}
              ]
            }
        """.trimIndent()
    }
}
