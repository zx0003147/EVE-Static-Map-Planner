package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectedJumpOverlaysTest {
    private val systems = listOf(
        testSystem(1, x2d = 1.0, y2d = 1.0),
        testSystem(2, x2d = null, y2d = null),
        testSystem(3, x2d = 3.0, y2d = 3.0),
    )
    private val data = StaticMapData(systems, listOf(StargateConnection.between(1, 3)))

    @Test
    fun `OFFICIAL_2D reports omitted overlay systems while REAL_XZ projects all`() {
        val overlay = JumpRangeOverlay("A", 1, JumpProfile.manual(5.0), setOf(2, 3))
        val official = ProjectedJumpRangeOverlayBuilder.build(
            overlay,
            MapSceneBuilder().build(data, OfficialPosition2DProjection),
        )
        val real = ProjectedJumpRangeOverlayBuilder.build(
            overlay,
            MapSceneBuilder().build(data, RealXzProjection),
        )

        assertEquals(setOf(2), official.omittedSystemIds)
        assertEquals(1, official.reachableNodes.size)
        assertEquals(emptySet(), real.omittedSystemIds)
        assertEquals(2, real.reachableNodes.size)
    }

    @Test
    fun `capital projection preserves real jump distance and reports omitted legs`() {
        val profile = JumpProfile.manual(5.0)
        val route = CapitalRouteResult(
            1,
            3,
            profile,
            listOf(1, 2, 3),
            listOf(
                CapitalRouteLeg(1, 2, UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
                CapitalRouteLeg(2, 3, UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
            ),
        )
        val official = ProjectedCapitalRouteOverlayBuilder.build(
            route,
            MapSceneBuilder().build(data, OfficialPosition2DProjection),
        )
        val real = ProjectedCapitalRouteOverlayBuilder.build(
            route,
            MapSceneBuilder().build(data, RealXzProjection),
        )

        assertEquals(2, official.omittedLegCount)
        assertEquals(0, real.omittedLegCount)
        assertEquals(listOf(1.0, 1.0), real.legs.map(ProjectedCapitalRouteLeg::distanceLy))
    }
}
