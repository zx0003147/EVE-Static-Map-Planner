package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSegment
import dev.evestaticmapplanner.feature.api.RouteSegmentKind
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EsiPackIntegrationTest {
    @Test
    fun `supplied external artifact has API 2 manifest and one entrypoint`() {
        val packJar = validatedEsiPackJar()

        JarFile(packJar.toFile()).use { jar ->
            val attributes = jar.manifest.mainAttributes
            assertEquals("esi.pack", attributes.getValue(FeaturePackJarManifest.PACK_ID))
            assertEquals("ESI Pack", attributes.getValue(FeaturePackJarManifest.DISPLAY_NAME))
            assertEquals("0.1.0", attributes.getValue(FeaturePackJarManifest.VERSION))
            assertEquals("EVE Static Map Planner", attributes.getValue(FeaturePackJarManifest.PUBLISHER))
            assertEquals(
                FeatureApiVersions.current().identifier,
                attributes.getValue(FeaturePackJarManifest.FEATURE_API_VERSION),
            )
        }

        URLClassLoader(
            arrayOf(packJar.toUri().toURL()),
            FeaturePackEntrypoint::class.java.classLoader,
        ).use { classLoader ->
            val entrypoints = ServiceLoader.load(FeaturePackEntrypoint::class.java, classLoader).toList()
            assertEquals(1, entrypoints.size)
            assertEquals(PackId("esi.pack"), entrypoints.single().descriptor().packId)
        }
    }

    @Test
    fun `real Host loads both capabilities rejects route action and tears down cleanly`() =
        withTempDirectory { root ->
            val packRoot = root.resolve("feature-packs")
            val destination = packRoot.resolve("esi.pack/pack.jar")
            destination.parent.createDirectories()
            Files.copy(validatedEsiPackJar(), destination)
            PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties")).save(
                mapOf(PackId("esi.pack") to StoredFeaturePackState(enabled = true)),
            )

            val events = mutableListOf<String>()
            var classLoader: TrackingClassLoader? = null
            val host = LocalFeaturePackHost(
                FeaturePackEntrypoint::class.java.classLoader,
                FeaturePackClassLoaderFactory { jarUrl, parent ->
                    TrackingClassLoader(arrayOf(jarUrl), parent).also { classLoader = it }
                },
            )
            val runtime = ProductionFeaturePackRuntime.start(packRoot, root, events::add, host)
            try {
                assertEquals(listOf(PackId("esi.pack")), runtime.startReport.loadedPackIds)
                assertTrue(runtime.startReport.failures.isEmpty())
                val activeClassLoader = assertNotNull(classLoader)
                assertFalse(activeClassLoader.closed)
                assertTrue(events.contains("INFO:esi.pack:ESI Pack starting"))
                assertTrue(events.contains("INFO:esi.pack:ESI Pack started"))

                val overlay = runtime.overlayHost.state.value.layers.single()
                assertEquals("esi.character-location", overlay.provider.id)
                assertEquals("current-location", overlay.layer.id)
                assertTrue(overlay.entries.isEmpty())

                val action = runtime.routeActionHost.state.value.single()
                assertEquals("esi.pack", action.key.packId.value)
                assertEquals("send-to-eve", action.key.actionId)
                assertEquals("Send to EVE", action.label)
                assertEquals(setOf(RouteKind.NORMAL), action.supportedRouteKinds)
                assertEquals(1, actionsFor(runtime, RouteKind.NORMAL).size)
                assertTrue(actionsFor(runtime, RouteKind.CAPITAL).isEmpty())

                assertFalse(runtime.routeActionHost.invoke(action.key, route(RouteKind.CAPITAL)))
                assertTrue(runtime.routeActionHost.invoke(action.key, route(RouteKind.NORMAL)))
                await {
                    runtime.routeActionHost.state.value.single().lastStatus == RouteActionStatus.REJECTED
                }
                val completed = runtime.routeActionHost.state.value.single()
                assertEquals("No EVE character is connected.", completed.lastMessage)

                assertFalse(Files.exists(root.resolve("feature-pack-storage/esi.pack")))
            } finally {
                assertTrue(runtime.closeSafely().failures.isEmpty())
            }

            assertTrue(events.contains("INFO:esi.pack:ESI Pack stopped"))
            assertTrue(runtime.overlayHost.state.value.layers.isEmpty())
            assertTrue(runtime.routeActionHost.state.value.isEmpty())
            assertTrue(checkNotNull(classLoader).closed)
        }

    private fun actionsFor(runtime: ProductionFeaturePackRuntime, kind: RouteKind) =
        runtime.routeActionHost.state.value.filter { kind in it.supportedRouteKinds }

    private fun route(kind: RouteKind): RouteSnapshot {
        val systems = listOf(30_000_001, 30_000_002, 30_000_003)
        val segments = when (kind) {
            RouteKind.NORMAL -> listOf(
                RouteSegment(systems[0], systems[1], RouteSegmentKind.STARGATE, null),
                RouteSegment(systems[1], systems[2], RouteSegmentKind.ANSIBLEX, null),
            )
            RouteKind.CAPITAL -> listOf(
                RouteSegment(systems[0], systems[1], RouteSegmentKind.CAPITAL_JUMP, 4.0),
                RouteSegment(systems[1], systems[2], RouteSegmentKind.CAPITAL_JUMP, 5.0),
            )
            else -> error("Integration fixture does not use mission routes")
        }
        return RouteSnapshot(
            identity = RouteIdentity("esi-pack-integration-${kind.name.lowercase()}"),
            kind = kind,
            sourceSystemId = systems.first(),
            destinationSystemId = systems.last(),
            orderedSystemIds = systems,
            orderedSegments = segments,
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for ESI Pack Route Action result" }
            Thread.sleep(10)
        }
    }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("esi-pack-integration-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class TrackingClassLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        var closed = false
            private set

        override fun close() {
            super.close()
            closed = true
        }
    }

    private companion object {
        fun validatedEsiPackJar(): Path {
            val configured = requireNotNull(System.getProperty(ESI_PACK_JAR_PROPERTY)) {
                "External ESI Pack integration requires -PesiPackJar=<absolute pack.jar path>"
            }
            val packJar = Path.of(configured).toAbsolutePath().normalize()
            assertTrue(Files.isRegularFile(packJar), "External ESI Pack JAR does not exist: $packJar")
            assertTrue(Files.isReadable(packJar), "External ESI Pack JAR is not readable: $packJar")
            val metadata = FeaturePackJarManifest.read(packJar)
            assertEquals(PackId("esi.pack"), metadata.packId)
            assertEquals("2", metadata.requiredFeatureApiVersion.identifier)
            return packJar
        }

        const val ESI_PACK_JAR_PROPERTY = "esi.pack.jar"
    }
}
