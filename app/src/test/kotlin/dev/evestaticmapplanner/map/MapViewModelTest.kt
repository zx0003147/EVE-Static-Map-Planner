package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

        viewModel.switchProjection(MapProjectionId.REAL_XZ)
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

        viewModel.switchProjection(MapProjectionId.REAL_XZ)
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
    fun `selection queries details once and context menu targets hit system`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        val state = viewModel.state.value
        val node = state.scene!!.nodesById.getValue(1)
        val screen = MapTransform(state.viewport!!, state.canvasSize).worldToScreen(node.position)

        viewModel.openContextMenuAt(screen)
        assertEquals(1, viewModel.state.value.contextMenu?.systemId)
        viewModel.selectContextMenuSystem()
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.selectedSystemId)
        assertEquals("One", viewModel.state.value.selectedSystemDetails?.system?.name)
        assertEquals(1, fixture.universe.detailsQueries)
        assertNull(viewModel.state.value.contextMenu)

        viewModel.selectAt(screen)
        advanceUntilIdle()
        assertEquals(1, fixture.universe.detailsQueries)
    }

    @Test
    fun `zoom and pan only replace viewport`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.onCanvasSizeChanged(MapSize(1000.0, 700.0))
        val before = viewModel.state.value

        viewModel.zoomAt(MapPoint(300.0, 200.0), -1.0)
        viewModel.panBy(MapPoint(25.0, -10.0))

        val after = viewModel.state.value
        assertTrue(before.scene === after.scene)
        assertNotEquals(before.viewport, after.viewport)
        assertEquals(before.performance.sceneBuildCount, after.performance.sceneBuildCount)
    }

    @Test
    fun `renderer detail level is zoom dependent`() {
        assertEquals(MapDetailLevel.OVERVIEW, MapRenderer.detailLevel(0.5))
        assertEquals(MapDetailLevel.NORMAL, MapRenderer.detailLevel(2.0))
        assertEquals(MapDetailLevel.DETAIL, MapRenderer.detailLevel(5.0))
    }
}

private class Fixture {
    private val one = system(1, "One", 0.0, 0.0, 0.0, 0.0)
    private val two = system(2, "Two", 10e15, 10e15, 10e15, 10e15)
    private val remote = system(3, "Remote", 8e18, -1e19, null, null)
    private val connection = StargateConnection.between(1, 2)
    private val mapData = StaticMapData(listOf(one, two, remote), listOf(connection))
    val universe = FakeUniverseRepository(listOf(one, two, remote))

    fun viewModel(
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        focusSystemName: String? = null,
    ) = MapViewModel(
        staticMapRepository = StaticMapRepository { mapData },
        universeRepository = universe,
        focusSystemName = focusSystemName,
        scope = scope,
        ioDispatcher = dispatcher,
        sceneDispatcher = dispatcher,
    )
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
