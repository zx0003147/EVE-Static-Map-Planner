package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Real3DRouteProjectionTest {
    @Test
    fun `route follows true XYZ endpoints and responds to camera rotation`() {
        val data = StaticMapData(
            systems = listOf(
                testSystem(1, x = -2e15, y = -5e15, z = -1e15),
                testSystem(2, x = 3e15, y = 7e15, z = 4e15),
            ),
            connections = listOf(StargateConnection.between(1, 2)),
            regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
        )
        val geometry = Real3DStaticGeometry.from(MapSceneBuilder().build(data, Real3DCanonicalProjection))
        val viewport = MapSize(800.0, 600.0)
        val camera = Real3DCameraFitter.fit(geometry.fitPoints, viewport)
        val route = RouteResult(
            startSystemId = 1,
            destinationSystemId = 2,
            systems = listOf(1, 2),
            edges = listOf(
                RouteEdge(
                    id = RouteEdgeId("test:1:2"),
                    connectionId = RouteConnectionId("test:1:2"),
                    fromSystemId = 1,
                    toSystemId = 2,
                    type = RouteEdgeType.STARGATE,
                ),
            ),
        )

        val initial = Real3DRouteProjector.project(route, geometry, camera, viewport)
        val rotated = Real3DRouteProjector.project(route, geometry, camera.rotated(35.0, 18.0), viewport)

        assertEquals(1, initial.legs.size)
        assertEquals(0, initial.omittedLegCount)
        assertNotEquals(initial.legs.single().segment, rotated.legs.single().segment)
    }
}
