package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Real3DFrameProjectionWorkspaceTest {
    @Test
    fun `workspace reuses frame collections while updating projected values`() {
        val data = StaticMapData(
            systems = listOf(
                testSystem(1, x = -4e15, y = -2e15, z = 0.0),
                testSystem(2, x = 5e15, y = 7e15, z = 3e15),
            ),
            connections = listOf(StargateConnection.between(1, 2)),
            regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
        )
        val geometry = Real3DStaticGeometry.from(MapSceneBuilder().build(data, Real3DCanonicalProjection))
        val viewport = MapSize(800.0, 600.0)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, viewport)
        val workspace = Real3DFrameProjectionWorkspace()
        val first = workspace.project(geometry, camera, viewport)
        val nodeList = first.nodesFarToNear
        val initialScreen = first.projectedBySystemId.getValue(1).snapshotScreenPoint()

        val second = workspace.project(geometry, camera.rotated(35.0, 12.0), viewport)

        assertSame(nodeList, second.nodesFarToNear)
        assertNotEquals(initialScreen, second.projectedBySystemId.getValue(1).snapshotScreenPoint())
    }

    @Test
    fun `workspace skips hidden edges before projection`() {
        val data = StaticMapData(
            systems = listOf(
                testSystem(1, x = -4e15, y = 0.0, z = 0.0),
                testSystem(2, x = 0.0, y = 0.0, z = 0.0),
                testSystem(3, x = 4e15, y = 0.0, z = 0.0),
            ),
            connections = listOf(StargateConnection.between(1, 2), StargateConnection.between(2, 3)),
            regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
        )
        val geometry = Real3DStaticGeometry.from(MapSceneBuilder().build(data, Real3DCanonicalProjection))
        val viewport = MapSize(800.0, 600.0)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, viewport)

        val frame = Real3DFrameProjectionWorkspace().project(geometry, camera, viewport) { edge ->
            edge.firstSystemId == 1 || edge.secondSystemId == 1
        }

        assertEquals(1, frame.edges.size)
        assertEquals(setOf(1, 2), setOf(frame.edges.single().edge.firstSystemId, frame.edges.single().edge.secondSystemId))
    }

    @Test
    fun `workspace reuses projected wrappers and updates primitive coordinates`() {
        val geometry = geometry(
            systems = listOf(
                testSystem(1, x = -4e15, y = -2e15, z = 0.0),
                testSystem(2, x = 5e15, y = 7e15, z = 3e15),
                testSystem(3, x = 9e15, y = -1e15, z = 2e15),
            ),
            connections = listOf(StargateConnection.between(1, 2)),
        )
        val viewport = MapSize(800.0, 600.0)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, viewport)
        val workspace = Real3DFrameProjectionWorkspace()
        val firstFrame = workspace.project(geometry, camera, viewport)
        val node = firstFrame.projectedBySystemId.getValue(1)
        val edge = firstFrame.edges.single()
        val region = firstFrame.regions.single()
        val constellation = firstFrame.constellations.single()
        val initialNodeX = node.screenX
        val rotatedCamera = camera.rotated(8.0, 4.0)

        val secondFrame = workspace.project(geometry, rotatedCamera, viewport)
        val expectedProjector = Real3DProjector(rotatedCamera, viewport)
        val expectedNode = expectedProjector.project(node.node.position)!!
        val expectedEdge = expectedProjector.projectSegment(edge.edge.first, edge.edge.second)!!
        val expectedRegion = expectedProjector.project(region.anchor.position)!!
        val expectedConstellation = expectedProjector.project(constellation.anchor.position)!!

        assertSame(node, secondFrame.projectedBySystemId.getValue(1))
        assertSame(edge, secondFrame.edges.single())
        assertSame(region, secondFrame.regions.single())
        assertSame(constellation, secondFrame.constellations.single())
        assertNotEquals(initialNodeX, node.screenX)
        assertEquals(expectedNode.screen.x, node.screenX, COORDINATE_EPSILON)
        assertEquals(expectedNode.screen.y, node.screenY, COORDINATE_EPSILON)
        assertEquals(expectedEdge.first.screen.x, edge.firstScreenX, COORDINATE_EPSILON)
        assertEquals(expectedEdge.first.screen.y, edge.firstScreenY, COORDINATE_EPSILON)
        assertEquals(expectedEdge.second.screen.x, edge.secondScreenX, COORDINATE_EPSILON)
        assertEquals(expectedEdge.second.screen.y, edge.secondScreenY, COORDINATE_EPSILON)
        assertEquals(expectedRegion.screen.x, region.screenX, COORDINATE_EPSILON)
        assertEquals(expectedRegion.screen.y, region.screenY, COORDINATE_EPSILON)
        assertEquals(expectedConstellation.screen.x, constellation.screenX, COORDINATE_EPSILON)
        assertEquals(expectedConstellation.screen.y, constellation.screenY, COORDINATE_EPSILON)
    }

    @Test
    fun `projected system map is a stable live view of current visibility`() {
        val geometry = geometry(
            systems = listOf(
                testSystem(1, x = -10e15, y = 0.0, z = 0.0),
                testSystem(2, x = 0.0, y = 0.0, z = 0.0),
                testSystem(3, x = 10e15, y = 0.0, z = 0.0),
            ),
            connections = listOf(StargateConnection.between(1, 2), StargateConnection.between(2, 3)),
        )
        val viewport = MapSize(800.0, 600.0)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, viewport)
        val workspace = Real3DFrameProjectionWorkspace()
        val frame = workspace.project(geometry, camera, viewport)
        val map = frame.projectedBySystemId
        val keys = map.keys
        val initiallyVisible = map.keys.toSet()

        val next = workspace.project(geometry, camera, MapSize(80.0, 600.0))
        val currentlyVisible = next.nodesFarToNear.map { it.node.system.id }.toSet()
        val hidden = initiallyVisible - currentlyVisible

        assertSame(frame, next)
        assertSame(map, next.projectedBySystemId)
        assertSame(keys, next.projectedBySystemId.keys)
        assertTrue(hidden.isNotEmpty())
        assertEquals(currentlyVisible.size, map.size)
        assertEquals(currentlyVisible, map.keys.toSet())
        assertEquals(currentlyVisible, map.values.map { it.node.system.id }.toSet())
        assertEquals(currentlyVisible, map.entries.map { it.key }.toSet())
        currentlyVisible.forEach { systemId ->
            assertTrue(systemId in map)
            assertEquals(systemId, map.getValue(systemId).node.system.id)
        }
        hidden.forEach { systemId ->
            assertTrue(systemId !in map)
            assertNull(map[systemId])
        }
    }

    @Test
    fun `depth sort keeps system id tie break`() {
        val geometry = geometry(
            systems = listOf(
                testSystem(3, x = 0.0, y = 0.0, z = 0.0),
                testSystem(1, x = 0.0, y = 0.0, z = 0.0),
                testSystem(2, x = 0.0, y = 0.0, z = 0.0),
            ),
            connections = emptyList(),
        )
        val viewport = MapSize(800.0, 600.0)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, viewport)

        val frame = Real3DFrameProjectionWorkspace().project(geometry, camera, viewport)

        assertEquals(listOf(1, 2, 3), frame.nodesFarToNear.map { it.node.system.id })
    }

    @Test
    fun `workspace rejects a different static geometry`() {
        val first = geometry(listOf(testSystem(1)), emptyList())
        val second = geometry(listOf(testSystem(1)), emptyList())
        val viewport = MapSize(800.0, 600.0)
        val workspace = Real3DFrameProjectionWorkspace()
        workspace.project(first, Real3DCameraFitter.fit(first.fitPoints, viewport), viewport)

        assertFailsWith<IllegalArgumentException> {
            workspace.project(second, Real3DCameraFitter.fit(second.fitPoints, viewport), viewport)
        }
    }

    private fun geometry(
        systems: List<dev.evestaticmapplanner.core.model.SolarSystem>,
        connections: List<StargateConnection>,
    ): Real3DStaticGeometry = Real3DStaticGeometry.from(
        MapSceneBuilder().build(
            StaticMapData(
                systems = systems,
                connections = connections,
                regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
                constellations = listOf(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
            ),
            Real3DCanonicalProjection,
        ),
    )

    private companion object {
        const val COORDINATE_EPSILON = 1e-9
    }
}
