package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteWaypointPresentationTest {
    private val scene = MapSceneBuilder().build(
        StaticMapData((1..3).map(::waypointSystem), emptyList()),
        OfficialPosition2DProjection,
    )

    @Test
    fun `numbering is per calculated route and duplicate systems stack instead of disappearing`() {
        val presented = RouteWaypointPresentationBuilder.build(
            scene,
            listOf(
                RouteWaypointSource("manual", RouteWaypointKind.USER_NORMAL, listOf(2, 1, 2)),
                RouteWaypointSource("mission", RouteWaypointKind.MISSION_CAPITAL, listOf(2, 3)),
            ),
        )

        assertEquals(listOf(1, 2, 3, 1, 2), presented.map { it.sequenceNumber })
        assertEquals(listOf(0, 0, 1, 2, 0), presented.map { it.stackIndex })
        assertEquals(listOf(2, 1, 2, 2, 3), presented.map { it.systemId })
    }

    @Test
    fun `only route snapshot waypoints that exist in the current projection are presented`() {
        val presented = RouteWaypointPresentationBuilder.build(
            scene,
            listOf(RouteWaypointSource("manual", RouteWaypointKind.USER_NORMAL, listOf(2, 99))),
        )

        assertEquals(listOf(2), presented.map { it.systemId })
    }
}

private fun waypointSystem(id: Int) = SolarSystem(
    id = id,
    constellationId = 10,
    regionId = 1,
    name = "System $id",
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, 0.0),
    schematicPosition = SchematicPosition(id.toDouble(), id.toDouble()),
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)
