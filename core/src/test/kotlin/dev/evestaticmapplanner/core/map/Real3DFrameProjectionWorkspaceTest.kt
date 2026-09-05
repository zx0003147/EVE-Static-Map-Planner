package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

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
        val initialScreen = first.projectedBySystemId.getValue(1).screen

        val second = workspace.project(geometry, camera.rotated(35.0, 12.0), viewport)

        assertSame(nodeList, second.nodesFarToNear)
        assertNotEquals(initialScreen, second.projectedBySystemId.getValue(1).screen)
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
}
