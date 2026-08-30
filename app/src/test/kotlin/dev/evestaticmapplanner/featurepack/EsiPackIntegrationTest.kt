package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSegment
import dev.evestaticmapplanner.feature.api.RouteSegmentKind
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import java.net.URL
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
            assertEquals("1.0.0", attributes.getValue(FeaturePackJarManifest.VERSION))
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
    fun `real Host loads multi-character capabilities disables untargeted actions and tears down cleanly`() =
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

                val actions = runtime.routeActionHost.state.value
                assertEquals(2, actions.size)
                assertEquals(setOf("esi.pack"), actions.map { it.key.packId.value }.toSet())
                assertEquals(
                    listOf("send-to-eve", "set-eve-destination"),
                    actions.map { it.key.actionId },
                )
                assertEquals(
                    listOf("Send Draft to EVE", "Set EVE Destination"),
                    actions.map { it.label },
                )
                assertTrue(actions.all { it.supportedRouteKinds == setOf(RouteKind.NORMAL) })
                assertEquals(2, actionsFor(runtime, RouteKind.NORMAL).size)
                assertTrue(actionsFor(runtime, RouteKind.CAPITAL).isEmpty())
                assertTrue(actions.all { it.targetSelector?.label == "EVE Character" })
                assertTrue(actions.all { it.targetSelector?.options?.isEmpty() == true })
                assertTrue(actions.none { it.enabled })

                val controls = runtime.packControlHost.state.value.single()
                assertEquals(PackId("esi.pack"), controls.packId)
                assertEquals("Connected Characters (0)", controls.primaryText)
                assertEquals("No connected characters", controls.secondaryText)
                assertEquals(listOf("add-character"), controls.actions.map { it.key.actionId })

                assertFalse(runtime.routeActionHost.invoke(actions.first().key, route(RouteKind.CAPITAL)))
                actions.forEach { action ->
                    assertFalse(runtime.routeActionHost.invoke(action.key, route(RouteKind.NORMAL)))
                }
                assertTrue(runtime.routeActionHost.state.value.all { it.lastStatus == null })

                assertFalse(Files.exists(root.resolve("feature-pack-storage/esi.pack")))
            } finally {
                assertTrue(runtime.closeSafely().failures.isEmpty())
            }

            assertTrue(events.contains("INFO:esi.pack:ESI Pack stopped"))
            assertTrue(runtime.overlayHost.state.value.layers.isEmpty())
            assertTrue(runtime.routeActionHost.state.value.isEmpty())
            assertTrue(runtime.packControlHost.state.value.isEmpty())
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
