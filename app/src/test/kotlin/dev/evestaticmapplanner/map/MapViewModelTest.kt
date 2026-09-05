package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapPoint3
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.model.Stargate
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.preferences.AppPreferences
import dev.evestaticmapplanner.preferences.AiControlPreferences
import dev.evestaticmapplanner.preferences.MapDisplayPreferences
import dev.evestaticmapplanner.preferences.MarkerPreferences
import dev.evestaticmapplanner.preferences.OverlayLayerKey
import dev.evestaticmapplanner.preferences.OverlayVisibilityPreferences
import dev.evestaticmapplanner.preferences.PreferencesStore
import dev.evestaticmapplanner.preferences.SavedMarkerAppearancePreferences
import dev.evestaticmapplanner.route.RoutePlannerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    @Test
    fun `loads official scene and focuses requested system`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher, focusSystemName = "Two")

        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(MapProjectionId.OFFICIAL_2D, state.projectionId)
        assertEquals(2, state.scene?.nodes?.size)
        assertEquals(1, state.scene?.edges?.size)
        assertEquals(2, state.selectedSystemId)
        assertEquals("Two", state.selectedSystemDetails?.system?.name)
        assertEquals(state.scene?.nodesById?.getValue(2)?.position, state.viewport?.center)
    }

    @Test
    fun `real scene keeps all systems and has separate scene and fit bounds`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))

        viewModel.switchProjection(MapProjectionId.REAL_3D)
        advanceUntilIdle()

        val scene = assertNotNull(viewModel.state.value.scene)
        assertEquals(3, scene.nodes.size)
        assertNotEquals(scene.sceneBounds, scene.defaultFitBounds)
        assertEquals(1, scene.edges.size)
    }

    @Test
    fun `each projection retains its own viewport`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        viewModel.panBy(MapPoint(50.0, 20.0))
        val officialViewport = viewModel.state.value.viewport

        viewModel.switchProjection(MapProjectionId.REAL_3D)
        advanceUntilIdle()
        viewModel.panBy(MapPoint(-30.0, 15.0))
        val realViewport = viewModel.state.value.viewport
        viewModel.switchProjection(MapProjectionId.OFFICIAL_2D)
        advanceUntilIdle()

        assertEquals(officialViewport, viewModel.state.value.viewport)
        assertNotEquals(officialViewport, realViewport)
        assertEquals(2, viewModel.state.value.performance.sceneBuildCount)
    }

    @Test
    fun `each projection retains its own semantic zoom mode`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        assertEquals(SemanticLabelMode.SYSTEM, viewModel.state.value.semanticLabelMode)

        viewModel.zoomAt(MapSize(1000.0, 700.0).center, 100.0)
        assertEquals(SemanticLabelMode.REGION_ONLY, viewModel.state.value.semanticLabelMode)

        viewModel.switchProjection(MapProjectionId.REAL_3D)
        advanceUntilIdle()
        assertEquals(SemanticLabelMode.REGION_ONLY, viewModel.state.value.semanticLabelMode)

        viewModel.switchProjection(MapProjectionId.OFFICIAL_2D)
        advanceUntilIdle()
        assertEquals(SemanticLabelMode.REGION_ONLY, viewModel.state.value.semanticLabelMode)
        assertEquals(
            SemanticLabelMode.REGION_ONLY,
            viewModel.state.value.semanticLabelModes.getValue(MapProjectionId.REAL_3D),
        )
    }

    @Test
    fun `hover uses spatial index without details query or scene rebuild`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        val state = viewModel.state.value
        val node = state.scene!!.nodesById.getValue(1)
        val screen = MapTransform(state.viewport!!, state.canvasSize).worldToScreen(node.position)
        val scene = state.scene
        val builds = state.performance.sceneBuildCount

        viewModel.hoverAt(screen)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.hoveredSystemId)
        assertEquals(0, fixture.universe.detailsQueries)
        assertTrue(scene === viewModel.state.value.scene)
        assertEquals(builds, viewModel.state.value.performance.sceneBuildCount)
    }

    @Test
    fun `left selection queries details once and context menu targets hit system`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        val state = viewModel.state.value
        val scene = state.scene
        val node = state.scene!!.nodesById.getValue(1)
        val screen = MapTransform(state.viewport!!, state.canvasSize).worldToScreen(node.position)

        viewModel.openContextMenuAt(screen)
        assertEquals(1, viewModel.state.value.contextMenu?.systemId)
        viewModel.dismissContextMenu()
        viewModel.selectAt(screen)
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.selectedSystemId)
        assertEquals("One", viewModel.state.value.selectedSystemDetails?.system?.name)
        assertEquals(1, fixture.universe.detailsQueries)
        assertNull(viewModel.state.value.contextMenu)
        assertTrue(scene === viewModel.state.value.scene)

        viewModel.selectAt(screen)
        advanceUntilIdle()
        assertEquals(1, fixture.universe.detailsQueries)
    }

    @Test
    fun `zoom and pan preserve scene and hierarchy anchors`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        val before = viewModel.state.value

        val regionAnchor = before.scene!!.regions.single()
        val constellationAnchor = before.scene.constellations.single()

        viewModel.zoomAt(MapPoint(300.0, 200.0), 100.0)
        viewModel.panBy(MapPoint(25.0, -10.0))

        val after = viewModel.state.value
        assertTrue(before.scene === after.scene)
        assertTrue(regionAnchor === after.scene.regions.single())
        assertTrue(constellationAnchor === after.scene.constellations.single())
        assertEquals(SemanticLabelMode.REGION_ONLY, after.semanticLabelMode)
        assertNotEquals(before.viewport, after.viewport)
        assertEquals(before.performance.sceneBuildCount, after.performance.sceneBuildCount)
    }

    @Test
    fun `renderer detail level is zoom dependent`() {
        assertEquals(MapDetailLevel.OVERVIEW, MapRenderer.detailLevel(0.5))
        assertEquals(MapDetailLevel.NORMAL, MapRenderer.detailLevel(2.0))
        assertEquals(MapDetailLevel.DETAIL, MapRenderer.detailLevel(5.0))
    }

    @Test
    fun `threshold changes immediately reclassify mode without rebuilding scene or anchors`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        val before = viewModel.state.value
        val zoom = assertNotNull(before.viewport).zoom
        val scene = assertNotNull(before.scene)
        val regionAnchor = scene.regions.single()
        val constellationAnchor = scene.constellations.single()

        viewModel.updateMapDisplayPreferences(
            before.appPreferences.mapDisplay.copy(
                constellationZoomThreshold = zoom * 2.0,
                systemZoomThreshold = zoom * 3.0,
            ),
        )

        val afterRegion = viewModel.state.value
        val afterScene = assertNotNull(afterRegion.scene)
        assertEquals(SemanticLabelMode.REGION_ONLY, afterRegion.semanticLabelMode)
        assertTrue(scene === afterScene)
        assertTrue(regionAnchor === afterScene.regions.single())
        assertTrue(constellationAnchor === afterScene.constellations.single())
        assertEquals(before.performance.sceneBuildCount, afterRegion.performance.sceneBuildCount)

        viewModel.updateMapDisplayPreferences(
            afterRegion.appPreferences.mapDisplay.copy(
                constellationZoomThreshold = zoom / 3.0,
                systemZoomThreshold = zoom / 2.0,
            ),
        )
        assertEquals(SemanticLabelMode.SYSTEM, viewModel.state.value.semanticLabelMode)
        assertTrue(scene === viewModel.state.value.scene)
    }

    @Test
    fun `projections share preferences while retaining modes for their own zoom`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        viewModel.zoomAt(MapSize(1000.0, 700.0).center, 100.0)
        assertEquals(0.01, viewModel.state.value.viewport?.zoom)

        viewModel.switchProjection(MapProjectionId.REAL_3D)
        advanceUntilIdle()
        val shared = MapDisplayPreferences(
            constellationZoomThreshold = 0.02,
            systemZoomThreshold = 0.03,
        )
        viewModel.updateMapDisplayPreferences(shared)

        assertEquals(shared, viewModel.state.value.appPreferences.mapDisplay)
        assertEquals(SemanticLabelMode.REGION_ONLY, viewModel.state.value.semanticLabelMode)
        assertEquals(
            SemanticLabelMode.REGION_ONLY,
            viewModel.state.value.semanticLabelModes.getValue(MapProjectionId.OFFICIAL_2D),
        )

        viewModel.switchProjection(MapProjectionId.OFFICIAL_2D)
        advanceUntilIdle()
        assertEquals(shared, viewModel.state.value.appPreferences.mapDisplay)
        assertEquals(SemanticLabelMode.REGION_ONLY, viewModel.state.value.semanticLabelMode)
    }

    @Test
    fun `preferences persist across view model restart and reset to defaults`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakePreferencesStore()
        val first = fixture.viewModel(this, dispatcher, preferencesStore = store)
        advanceUntilIdle()
        val customized = MapDisplayPreferences.Defaults.copy(
            constellationZoomThreshold = 3.0,
            systemZoomThreshold = 9.0,
            regionBackgroundAlpha = 0.14f,
        )

        first.updateMapDisplayPreferences(customized)
        advanceUntilIdle()
        assertEquals(AppPreferences(customized), store.stored)

        val restarted = fixture.viewModel(this, dispatcher, preferencesStore = store)
        advanceUntilIdle()
        assertEquals(customized, restarted.state.value.appPreferences.mapDisplay)

        restarted.resetMapDisplayPreferences()
        assertEquals(AppPreferences.Defaults, restarted.state.value.appPreferences)
        advanceUntilIdle()
        assertEquals(AppPreferences.Defaults, store.stored)
    }

    @Test
    fun `marker and map display resets are isolated and persisted`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakePreferencesStore()
        val viewModel = fixture.viewModel(this, dispatcher, preferencesStore = store)
        advanceUntilIdle()
        val mapDisplay = MapDisplayPreferences.Defaults.copy(regionPrimaryFontSizeSp = 24f)
        val marker = MarkerPreferences(
            showMarkers = false,
            showMarkerNames = false,
            savedMarkerAppearance = SavedMarkerAppearancePreferences(
                ringRadiusDp = 25f,
                lineWidthDp = 4f,
                glowEnabled = false,
                glowStrength = 0.75f,
            ),
        )

        viewModel.updateMapDisplayPreferences(mapDisplay)
        viewModel.updateMarkerPreferences(marker)
        assertEquals(marker, viewModel.state.value.appPreferences.marker)
        advanceUntilIdle()
        viewModel.resetMapDisplayPreferences()
        advanceUntilIdle()

        assertEquals(MapDisplayPreferences.Defaults, viewModel.state.value.appPreferences.mapDisplay)
        assertEquals(marker, viewModel.state.value.appPreferences.marker)
        assertEquals(marker, store.stored.marker)

        viewModel.updateMapDisplayPreferences(mapDisplay)
        viewModel.resetMarkerPreferences()
        advanceUntilIdle()

        assertEquals(mapDisplay, viewModel.state.value.appPreferences.mapDisplay)
        assertEquals(MarkerPreferences.Defaults, viewModel.state.value.appPreferences.marker)
        assertEquals(mapDisplay, store.stored.mapDisplay)
    }

    @Test
    fun `overlay visibility updates state and persists disable then enable`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakePreferencesStore()
        val viewModel = fixture.viewModel(this, dispatcher, preferencesStore = store)
        val key = OverlayLayerKey("fixture.provider", "status")
        advanceUntilIdle()

        viewModel.updateOverlayVisibilityPreferences(
            OverlayVisibilityPreferences.Defaults.withEnabled(key, false),
        )
        assertFalse(viewModel.state.value.appPreferences.overlayVisibility.isEnabled(key))
        advanceUntilIdle()
        assertFalse(store.stored.overlayVisibility.isEnabled(key))

        viewModel.updateOverlayVisibilityPreferences(
            viewModel.state.value.appPreferences.overlayVisibility.withEnabled(key, true),
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.appPreferences.overlayVisibility.isEnabled(key))
        assertTrue(store.stored.overlayVisibility.isEnabled(key))
    }

    @Test
    fun `AI Control category reset and Reset All persist disabled`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakePreferencesStore()
        val viewModel = fixture.viewModel(this, dispatcher, preferencesStore = store)
        advanceUntilIdle()

        assertTrue(
            viewModel.updateAiControlPreferences(
                AiControlPreferences(enabled = true, savedMarkerAccessEnabled = true),
            ).isSuccess,
        )
        assertTrue(viewModel.state.value.appPreferences.aiControl.enabled)
        assertTrue(viewModel.state.value.appPreferences.aiControl.savedMarkerAccessEnabled)
        assertTrue(store.stored.aiControl.enabled)
        assertTrue(store.stored.aiControl.savedMarkerAccessEnabled)

        assertTrue(viewModel.updateAiControlPreferences(AiControlPreferences.Defaults).isSuccess)
        assertFalse(viewModel.state.value.appPreferences.aiControl.enabled)
        assertFalse(viewModel.state.value.appPreferences.aiControl.savedMarkerAccessEnabled)
        assertFalse(store.stored.aiControl.enabled)
        assertFalse(store.stored.aiControl.savedMarkerAccessEnabled)

        assertTrue(viewModel.updateAiControlPreferences(AiControlPreferences(enabled = true)).isSuccess)
        assertTrue(viewModel.resetAllPreferences().isSuccess)
        assertEquals(AppPreferences.Defaults, viewModel.state.value.appPreferences)
        assertEquals(AppPreferences.Defaults, store.stored)
    }

    @Test
    fun `AI Control preference save failure leaves session preference disabled`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakePreferencesStore().apply { failSaves = true }
        val viewModel = fixture.viewModel(this, dispatcher, preferencesStore = store)
        advanceUntilIdle()

        val result = viewModel.updateAiControlPreferences(
            AiControlPreferences(enabled = true, savedMarkerAccessEnabled = true),
        )

        assertTrue(result.isFailure)
        assertFalse(viewModel.state.value.appPreferences.aiControl.enabled)
        assertFalse(viewModel.state.value.appPreferences.aiControl.savedMarkerAccessEnabled)
        assertFalse(store.stored.aiControl.enabled)
        assertFalse(store.stored.aiControl.savedMarkerAccessEnabled)
    }

    @Test
    fun `search focus selects target centers it and supplies compact card details`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        val scene = assertNotNull(viewModel.state.value.scene)
        val preferences = viewModel.state.value.appPreferences
        val routeState = RoutePlannerUiState()
        val jumpState = JumpOverlayUiState()

        viewModel.selectAndFocusSystem(2)
        advanceUntilIdle()

        val state = viewModel.state.value
        val target = scene.nodesById.getValue(2)
        val targetOnScreen = MapTransform(assertNotNull(state.viewport), state.canvasSize)
            .worldToScreen(target.position)
        assertEquals(2, state.selectedSystemId)
        assertEquals("Two", state.selectedSystemDetails?.system?.name)
        assertEquals(state.canvasSize.center, targetOnScreen)
        assertEquals("Two", CompactSystemInfoPresentationBuilder.build(state, routeState, jumpState)?.title)
        assertSame(scene, state.scene)
        assertSame(preferences, state.appPreferences)
        assertEquals(RoutePlannerUiState(), routeState)
        assertEquals(JumpOverlayUiState(), jumpState)
    }

    @Test
    fun `pointer focus selects the hit system and reuses official focus behavior`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        val before = viewModel.state.value
        val target = assertNotNull(before.scene).nodesById.getValue(2)
        val screenPosition = MapTransform(assertNotNull(before.viewport), before.canvasSize)
            .worldToScreen(target.position)

        viewModel.selectAndFocusAt(screenPosition)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.selectedSystemId)
        assertEquals(target.position, state.viewport?.center)
    }

    @Test
    fun `search focus raises distant zoom from current custom system threshold`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        viewModel.zoomAt(MapSize(1000.0, 700.0).center, 20.0)
        val firstPreferences = MapDisplayPreferences(
            constellationZoomThreshold = 5.0,
            systemZoomThreshold = 10.0,
        )
        viewModel.updateMapDisplayPreferences(firstPreferences)
        assertTrue(assertNotNull(viewModel.state.value.viewport).zoom < firstPreferences.systemZoomThreshold)

        viewModel.selectAndFocusSystem(2)
        assertEquals(10.5, viewModel.state.value.viewport?.zoom)
        assertEquals(SemanticLabelMode.SYSTEM, viewModel.state.value.semanticLabelMode)

        viewModel.zoomAt(MapSize(1000.0, 700.0).center, 20.0)
        val updatedPreferences = firstPreferences.copy(
            constellationZoomThreshold = 8.0,
            systemZoomThreshold = 20.0,
        )
        viewModel.updateMapDisplayPreferences(updatedPreferences)
        viewModel.selectAndFocusSystem(1)

        assertEquals(21.0, viewModel.state.value.viewport?.zoom)
        assertEquals(updatedPreferences, viewModel.state.value.appPreferences.mapDisplay)
    }

    @Test
    fun `search focus preserves an already readable zoom`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        viewModel.updateMapDisplayPreferences(
            MapDisplayPreferences(
                constellationZoomThreshold = 2.0,
                systemZoomThreshold = 6.0,
            ),
        )
        val beforeZoom = assertNotNull(viewModel.state.value.viewport).zoom
        assertTrue(beforeZoom >= 6.0)

        viewModel.selectAndFocusSystem(2)

        assertEquals(beforeZoom, viewModel.state.value.viewport?.zoom)
        assertEquals(viewModel.state.value.scene?.nodesById?.getValue(2)?.position, viewModel.state.value.viewport?.center)
    }

    @Test
    fun `real 3D focus moves only camera target without rebuilding its scene`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        viewModel.switchProjection(MapProjectionId.REAL_3D)
        advanceUntilIdle()
        val scene = assertNotNull(viewModel.state.value.scene)
        val builds = viewModel.state.value.performance.sceneBuildCount
        viewModel.rotateBy(MapPoint(30.0, -15.0))
        viewModel.zoomAt(MapPoint(500.0, 350.0), 1.0)
        val cameraBeforeFocus = assertNotNull(viewModel.state.value.real3DCamera)

        viewModel.selectAndFocusSystem(3)
        advanceUntilIdle()

        val state = viewModel.state.value
        val expectedTarget = MapPoint3.fromUniverse(scene.nodesById.getValue(3).system.position)
        assertEquals(MapProjectionId.REAL_3D, state.projectionId)
        assertEquals(cameraBeforeFocus.copy(target = expectedTarget), state.real3DCamera)
        assertSame(scene, state.scene)
        assertEquals(builds, state.performance.sceneBuildCount)
        assertNull(state.focusNotice)
    }

    @Test
    fun `official missing search target switches to cached real projection without fake coordinates`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        viewModel.switchProjection(MapProjectionId.REAL_3D)
        advanceUntilIdle()
        val realScene = assertNotNull(viewModel.state.value.scene)
        val realCameraBefore = assertNotNull(viewModel.state.value.real3DCamera)
        val builds = viewModel.state.value.performance.sceneBuildCount
        viewModel.switchProjection(MapProjectionId.OFFICIAL_2D)
        advanceUntilIdle()
        assertTrue(3 in assertNotNull(viewModel.state.value.scene).omittedSystemIds)

        viewModel.selectAndFocusSystem(3)
        advanceUntilIdle()

        val state = viewModel.state.value
        val expectedPosition = MapPoint3.fromUniverse(realScene.nodesById.getValue(3).system.position)
        assertEquals(MapProjectionId.REAL_3D, state.projectionId)
        assertEquals(3, state.selectedSystemId)
        assertEquals("Remote", state.selectedSystemDetails?.system?.name)
        assertEquals(expectedPosition, state.real3DCamera?.target)
        assertNotEquals(MapPoint3.Zero, state.real3DCamera?.target)
        assertEquals(realCameraBefore.distance, state.real3DCamera?.distance)
        assertTrue(state.focusNotice?.contains("switched to Real 3D") == true)
        assertSame(realScene, state.scene)
        assertEquals(builds, state.performance.sceneBuildCount)
    }

    @Test
    fun `control focus is completion-aware across official projection fallback`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))

        val completion = async { viewModel.focusSystemForControl(3) }
        advanceUntilIdle()

        assertTrue(completion.await())
        val state = viewModel.state.value
        assertEquals(MapProjectionId.REAL_3D, state.projectionId)
        assertEquals(3, state.selectedSystemId)
        assertEquals(
            state.scene?.nodesById?.getValue(3)?.system?.position?.let(MapPoint3::fromUniverse),
            state.real3DCamera?.target,
        )
    }

    @Test
    fun `control visual fit includes generated coverage and falls back without rebuilding cached scene`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        viewModel.switchProjection(MapProjectionId.REAL_3D)
        advanceUntilIdle()
        val realScene = assertNotNull(viewModel.state.value.scene)
        val buildCount = viewModel.state.value.performance.sceneBuildCount
        viewModel.switchProjection(MapProjectionId.OFFICIAL_2D)
        advanceUntilIdle()

        val completion = async { viewModel.fitSystemsForControl(setOf(1, 3)) }
        advanceUntilIdle()

        assertTrue(completion.await())
        val state = viewModel.state.value
        val first = realScene.nodesById.getValue(1).position
        val third = realScene.nodesById.getValue(3).position
        assertEquals(MapProjectionId.REAL_3D, state.projectionId)
        assertEquals(MapPoint((first.x + third.x) / 2.0, (first.y + third.y) / 2.0), state.viewport?.center)
        assertSame(realScene, state.scene)
        assertEquals(buildCount, state.performance.sceneBuildCount)
    }

    @Test
    fun `real 3D controls mutate only camera state and reset restores default orientation`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        viewModel.switchProjection(MapProjectionId.REAL_3D)
        advanceUntilIdle()
        val initial = assertNotNull(viewModel.state.value.real3DCamera)
        val canonicalViewport = viewModel.state.value.viewport

        viewModel.panBy(MapPoint(30.0, -20.0))
        val panned = assertNotNull(viewModel.state.value.real3DCamera)
        viewModel.rotateBy(MapPoint(25.0, -10.0))
        val rotated = assertNotNull(viewModel.state.value.real3DCamera)
        viewModel.zoomAt(MapPoint(500.0, 350.0), 1.0)
        val dolly = assertNotNull(viewModel.state.value.real3DCamera)

        assertNotEquals(initial.target, panned.target)
        assertNotEquals(panned.yawDegrees, rotated.yawDegrees)
        assertNotEquals(rotated.pitchDegrees, initial.pitchDegrees)
        assertTrue(dolly.distance > rotated.distance)
        assertEquals(canonicalViewport, viewModel.state.value.viewport)

        viewModel.resetView()
        val reset = assertNotNull(viewModel.state.value.real3DCamera)
        assertEquals(0.0, reset.yawDegrees)
        assertEquals(0.0, reset.pitchDegrees)
        assertEquals(dolly.copy(yawDegrees = 0.0, pitchDegrees = 0.0), reset)
        assertNotEquals(initial.target, reset.target)
        assertEquals(canonicalViewport, viewModel.state.value.viewport)
    }
}

private class Fixture {
    private val one = system(1, "One", 0.0, 0.0, 0.0, 0.0)
    private val two = system(2, "Two", 10e15, 10e15, 10e15, 10e15)
    private val remote = system(3, "Remote", 8e18, -1e19, null, null)
    private val connection = StargateConnection.between(1, 2)
    private val mapData = StaticMapData(
        systems = listOf(one, two, remote),
        connections = listOf(connection),
        regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
        constellations = listOf(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
    )
    val universe = FakeUniverseRepository(listOf(one, two, remote))

    fun viewModel(
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        focusSystemName: String? = null,
        preferencesStore: PreferencesStore = FakePreferencesStore(),
    ) = MapViewModel(
        staticMapRepository = StaticMapRepository { mapData },
        universeRepository = universe,
        focusSystemName = focusSystemName,
        scope = scope,
        ioDispatcher = dispatcher,
        sceneDispatcher = dispatcher,
        preferencesStore = preferencesStore,
    )
}

private class FakePreferencesStore(
    initial: AppPreferences = AppPreferences.Defaults,
) : PreferencesStore {
    var stored: AppPreferences = initial
    var failSaves: Boolean = false

    override fun load(): AppPreferences = stored

    override fun save(preferences: AppPreferences) {
        if (failSaves) error("simulated settings failure")
        stored = preferences
    }
}

private class FakeUniverseRepository(
    systems: List<SolarSystem>,
) : UniverseRepository {
    private val systems = systems.associateBy { it.id }
    var detailsQueries = 0

    override fun getRegion(id: Int) = Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)
    override fun getConstellation(id: Int) = Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)
    override fun getSystem(id: Int) = systems[id]
    override fun findSystemByName(name: String) = systems.values.singleOrNull { it.name == name }
    override fun getSystemDetails(id: Int): SolarSystemDetails? {
        detailsQueries++
        val system = systems[id] ?: return null
        val gates = if (id == 1) {
            listOf(Stargate(100, 1, 2, 200, 1, UniversePosition(0.0, 0.0, 0.0)))
        } else {
            emptyList()
        }
        return SolarSystemDetails(system, getRegion(1), getConstellation(10), gates)
    }
}

private fun system(
    id: Int,
    name: String,
    x: Double,
    z: Double,
    x2d: Double?,
    y2d: Double?,
) = SolarSystem(
    id = id,
    constellationId = 10,
    regionId = 1,
    name = name,
    securityStatus = 0.25,
    securityClass = null,
    position = UniversePosition(x, 0.0, z),
    schematicPosition = if (x2d != null && y2d != null) SchematicPosition(x2d, y2d) else null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)
