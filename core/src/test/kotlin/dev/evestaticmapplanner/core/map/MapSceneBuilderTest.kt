package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MapSceneBuilderTest {
    private val connectedOne = testSystem(1, x = 0.0, z = 0.0, x2d = 0.0, y2d = 0.0)
    private val connectedTwo = testSystem(2, x = 10e15, z = 10e15, x2d = 10e15, y2d = 10e15)
    private val remote = testSystem(3, x = 8e18, z = -1e19, x2d = null, y2d = null)
    private val data = StaticMapData(
        systems = listOf(connectedOne, connectedTwo, remote),
        connections = listOf(StargateConnection.between(1, 2)),
    )

    @Test
    fun `official scene omits systems without position2d`() {
        val scene = MapSceneBuilder().build(data, OfficialPosition2DProjection)

        assertEquals(2, scene.nodes.size)
        assertEquals(setOf(3), scene.omittedSystemIds)
        assertEquals(1, scene.edges.size)
    }

    @Test
    fun `real scene retains remote systems but default fit uses connected systems`() {
        val scene = MapSceneBuilder().build(data, RealXzProjection)

        assertEquals(3, scene.nodes.size)
        assertTrue(scene.sceneBounds.contains(scene.nodesById.getValue(3).position))
        assertTrue(!scene.defaultFitBounds.contains(scene.nodesById.getValue(3).position))
        assertNotEquals(scene.sceneBounds, scene.defaultFitBounds)
    }

    @Test
    fun `scene edge keeps projected endpoints and bounds`() {
        val scene = MapSceneBuilder().build(data, RealXzProjection)
        val edge = scene.edges.single()

        assertEquals(1, edge.firstSystemId)
        assertEquals(2, edge.secondSystemId)
        assertTrue(edge.bounds.contains(edge.first))
        assertTrue(edge.bounds.contains(edge.second))
    }

    @Test
    fun `scene cache builds and retains independent projection instances`() {
        val cache = MapSceneCache(data)

        val official = cache.get(MapProjectionId.OFFICIAL_2D)
        val officialAgain = cache.get(MapProjectionId.OFFICIAL_2D)
        val real = cache.get(MapProjectionId.REAL_XZ)

        assertTrue(official === officialAgain)
        assertTrue(official !== real)
        assertEquals(setOf(MapProjectionId.OFFICIAL_2D, MapProjectionId.REAL_XZ), cache.cachedProjectionIds())
    }

    @Test
    fun `edge bounds remain visible when an edge crosses a viewport`() {
        val edge = ProjectedStargateEdge(1, 2, MapPoint(-10.0, 0.0), MapPoint(10.0, 0.0))

        assertTrue(edge.bounds.intersects(MapBounds(-1.0, -1.0, 1.0, 1.0)))
    }
}
