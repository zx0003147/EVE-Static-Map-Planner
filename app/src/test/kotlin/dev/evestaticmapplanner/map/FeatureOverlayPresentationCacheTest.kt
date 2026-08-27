package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.map.RealXzProjection
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureOverlayPresentationCacheTest {
    @Test
    fun `coordinator rejects out of order publication and reuses completed projection cache`() = runTest {
        val officialScene = scene(OfficialPosition2DProjection)
        val realScene = scene(RealXzProjection)
        val overlay = overlay(owner = "alliance-a")
        val officialKey = FeatureOverlayGeometryKey.from(officialScene, overlay)
        val realKey = FeatureOverlayGeometryKey.from(realScene, overlay)
        val officialPresentation = presentation("Official")
        val realPresentation = presentation("Real")
        val officialGate = CompletableDeferred<FeatureOverlayPresentation>()
        val realGate = CompletableDeferred<FeatureOverlayPresentation>()
        val coordinator = FeatureOverlayPresentationCoordinator(
            scope = backgroundScope,
            computationDispatcher = StandardTestDispatcher(testScheduler),
            computer = { _, requestedScene ->
                if (requestedScene.projectionId == officialScene.projectionId) officialGate.await() else realGate.await()
            },
        )

        assertIs<FeatureOverlayPresentationRequest.Pending>(coordinator.request(officialKey, overlay, officialScene))
        assertIs<FeatureOverlayPresentationRequest.Pending>(coordinator.request(realKey, overlay, realScene))

        officialGate.complete(officialPresentation)
        runCurrent()
        assertFalse(coordinator.isCurrent(officialKey))
        assertSame(officialPresentation, coordinator.peek(officialKey))

        realGate.complete(realPresentation)
        runCurrent()
        assertTrue(coordinator.isCurrent(realKey))
        assertSame(realPresentation, coordinator.peek(realKey))

        val cachedOfficial = assertIs<FeatureOverlayPresentationRequest.Cached>(
            coordinator.request(officialKey, overlay, officialScene),
        )
        assertSame(officialPresentation, cachedOfficial.presentation)
    }

    @Test
    fun `stale projection result is cached but cannot replace the current projection`() {
        val officialScene = scene(OfficialPosition2DProjection)
        val realScene = scene(RealXzProjection)
        val overlay = overlay(owner = "alliance-a")
        val officialKey = FeatureOverlayGeometryKey.from(officialScene, overlay)
        val realKey = FeatureOverlayGeometryKey.from(realScene, overlay)
        val officialPresentation = presentation("Official")
        val realPresentation = presentation("Real")
        val cache = FeatureOverlayPresentationCache()

        assertNull(cache.request(officialKey))
        assertNull(cache.request(realKey))
        assertFalse(cache.complete(officialKey, officialPresentation))
        assertTrue(cache.complete(realKey, realPresentation))
        assertSame(realPresentation, cache.request(realKey))
        assertSame(officialPresentation, cache.request(officialKey))
        assertEquals(setOf(officialKey, realKey), cache.cachedKeys())
    }

    @Test
    fun `geometry key ignores new equivalent overlay objects and hidden entry details`() {
        val scene = scene(OfficialPosition2DProjection)
        val first = FeatureOverlayGeometryKey.from(scene, overlay(owner = "alliance-a", hiddenTitle = "Hidden one"))
        val equivalent = FeatureOverlayGeometryKey.from(scene, overlay(owner = "alliance-a", hiddenTitle = "Hidden two"))

        assertEquals(first, equivalent)
        assertEquals(first.hashCode(), equivalent.hashCode())
    }

    @Test
    fun `geometry key changes for projection scene layout or visible ownership`() {
        val officialScene = scene(OfficialPosition2DProjection)
        val rebuiltOfficialScene = scene(OfficialPosition2DProjection, coordinateOffset = 5.0)
        val realScene = scene(RealXzProjection)
        val firstOverlay = overlay(owner = "alliance-a")
        val changedOverlay = overlay(owner = "alliance-b")
        val first = FeatureOverlayGeometryKey.from(officialScene, firstOverlay)

        assertFalse(first == FeatureOverlayGeometryKey.from(realScene, firstOverlay))
        assertFalse(first == FeatureOverlayGeometryKey.from(rebuiltOfficialScene, firstOverlay))
        assertFalse(first == FeatureOverlayGeometryKey.from(officialScene, changedOverlay))
    }

    @Test
    fun `selection route marker viewport and zoom changes cannot invalidate territory cache`() {
        val scene = scene(OfficialPosition2DProjection)
        val overlay = overlay(owner = "alliance-a")
        val key = FeatureOverlayGeometryKey.from(scene, overlay)
        val presentation = presentation("Stable")
        val cache = FeatureOverlayPresentationCache()

        cache.request(key)
        assertTrue(cache.complete(key, presentation))

        // Those UI states are deliberately absent from FeatureOverlayGeometryKey inputs.
        repeat(6) {
            assertSame(presentation, cache.request(FeatureOverlayGeometryKey.from(scene, overlay(owner = "alliance-a"))))
        }
    }

    @Test
    fun `Sovereignty logo emphasis preference cannot invalidate territory geometry cache`() {
        val scene = scene(OfficialPosition2DProjection)
        val overlay = overlay(owner = "alliance-a")
        val key = FeatureOverlayGeometryKey.from(scene, overlay)
        val presentation = presentation("Stable geometry")
        val cache = FeatureOverlayPresentationCache()
        cache.request(key)
        assertTrue(cache.complete(key, presentation))

        listOf(0.55, 0.75, 0.85, 1.00).forEach { emphasisZoom ->
            val policy = FeatureOverlayEmblemZoomPolicy(emphasisZoom)
            FeatureOverlayEmblemLod.alpha(0.55, policy)
            assertSame(presentation, cache.request(FeatureOverlayGeometryKey.from(scene, overlay)))
        }
    }

    @Test
    fun `decoded emblem completion leaves expensive territory presentation cached`() = runTest {
        val scene = scene(OfficialPosition2DProjection)
        val overlay = overlay(owner = "alliance-a")
        val key = FeatureOverlayGeometryKey.from(scene, overlay)
        val presentation = presentation("Stable territory")
        val territoryCache = FeatureOverlayPresentationCache()
        territoryCache.request(key)
        assertTrue(territoryCache.complete(key, presentation))

        val emblemRepository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            loader = PresentationEmblemLoader<String> { "decoded" },
        )
        val request = emblemRepository.request(
            PresentationEmblemReference(
                "eve-alliance:42",
                "https://images.evetech.net/alliances/42/logo?size=256",
            ),
        )
        runCurrent()
        assertIs<PresentationEmblemAssetState.Ready<String>>(request.await())

        assertSame(presentation, territoryCache.request(FeatureOverlayGeometryKey.from(scene, overlay)))
    }

    private fun presentation(label: String) = FeatureOverlayPresentation(
        territories = emptyList(),
        legendSections = listOf(FeatureOverlayLegendSection(label, emptyList())),
    )

    private fun overlay(owner: String, hiddenTitle: String = "Hidden") = OverlayState(listOf(
        OverlayLayerState(
            provider = OverlayProviderDescriptor("test.provider", "Test Provider"),
            layer = OverlayLayer("sovereignty", "Sovereignty"),
            entries = listOf(
                OverlayEntry(
                    layerId = "sovereignty",
                    systemId = 1,
                    title = "Alliance",
                    value = "ownerKey=$owner;color=#5AA9FF",
                ),
                OverlayEntry(
                    layerId = "sovereignty",
                    systemId = 2,
                    title = hiddenTitle,
                    value = "ownerKey=hidden;color=#FFFFFF",
                    visibility = OverlayEntryVisibility.HIDDEN,
                ),
            ),
        ),
    ))

    private fun scene(
        projection: dev.evestaticmapplanner.core.map.MapProjection,
        coordinateOffset: Double = 0.0,
    ): ProjectedMapScene = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(
                system(1, coordinateOffset, coordinateOffset),
                system(2, 50.0 + coordinateOffset, 30.0 + coordinateOffset),
            ),
            connections = emptyList(),
        ),
        projection,
    )

    private fun system(id: Int, x: Double, y: Double) = SolarSystem(
        id = id,
        constellationId = 20_000_001,
        regionId = 10_000_001,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(x * COORDINATE_UNIT, 0.0, -y * COORDINATE_UNIT),
        schematicPosition = SchematicPosition(x * COORDINATE_UNIT, y * COORDINATE_UNIT),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private companion object {
        const val COORDINATE_UNIT = 1_000_000_000_000_000.0
    }
}
