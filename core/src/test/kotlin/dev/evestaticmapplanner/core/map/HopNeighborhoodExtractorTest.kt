package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HopNeighborhoodExtractorTest {
    private val scene = MapSceneBuilder().build(
        StaticMapData(
            systems = (1..7).map { testSystem(it, constellationId = 1, regionId = 1) },
            connections = (1..6).map { StargateConnection.between(it, it + 1) },
            regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(1, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
        ),
        object : MapProjection {
            override val id = MapProjectionId.OFFICIAL_2D
            override fun project(system: SolarSystem) = MapPoint(system.id.toDouble(), 0.0)
        },
    )

    @Test
    fun `BFS includes exactly one through five Stargate hops`() {
        for (hops in 1..5) {
            val slice = HopNeighborhoodExtractor.extract(scene, 1, hops)
            assertEquals((1..hops + 1).toSet(), slice.includedSystemIds)
            assertEquals(hops, slice.distanceBySystem.getValue(hops + 1))
            assertEquals(hops, slice.edges.size)
        }
    }

    @Test
    fun `edge filtering excludes Ansiblex from default traversal`() {
        val ansiblex = RouteEdge(
            RouteEdgeId("ansiblex:1:1:7"),
            RouteConnectionId("ansiblex:1"),
            1,
            7,
            RouteEdgeType.ANSIBLEX,
        )
        val default = HopNeighborhoodExtractor.extract(scene, 1, 1, additionalEdges = listOf(ansiblex))
        val included = HopNeighborhoodExtractor.extract(
            scene,
            1,
            1,
            setOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX),
            listOf(ansiblex),
        )

        assertFalse(7 in default.includedSystemIds)
        assertTrue(7 in included.includedSystemIds)
        assertTrue(included.edges.any { it.type == RouteEdgeType.ANSIBLEX })
    }
}
