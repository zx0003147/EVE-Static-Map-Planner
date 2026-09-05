package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.route.NormalRouteEngine
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteGraphBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProjectedRouteOverlayTest {
    @Test
    fun `official projection omits only legs whose endpoint is unavailable`() {
        val data = StaticMapData(
            systems = listOf(
                testSystem(1),
                testSystem(2, x2d = null, y2d = null),
                testSystem(3),
            ),
            connections = listOf(
                StargateConnection.between(1, 2),
                StargateConnection.between(2, 3),
            ),
        )
        val route = assertIs<RouteCalculationOutcome.Found>(
            NormalRouteEngine().calculate(RouteGraphBuilder.build(data), 1, 3),
        ).route
        val official = MapSceneBuilder().build(data, OfficialPosition2DProjection)
        val real = MapSceneBuilder().build(data, Real3DCanonicalProjection)

        val officialOverlay = ProjectedRouteOverlayBuilder.build(route, official)
        val realOverlay = ProjectedRouteOverlayBuilder.build(route, real)

        assertEquals(setOf(2), officialOverlay.omittedSystemIds)
        assertEquals(2, officialOverlay.omittedLegCount)
        assertEquals(0, officialOverlay.legs.size)
        assertEquals(emptySet(), realOverlay.omittedSystemIds)
        assertEquals(0, realOverlay.omittedLegCount)
        assertEquals(2, realOverlay.legs.size)
    }
}
