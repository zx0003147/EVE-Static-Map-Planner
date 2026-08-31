package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WormholeMapPresentationTest {
    @Test
    fun `empty store produces no layer content and one Wormhole projects visibly`() {
        val scene = scene()
        assertEquals(WormholeMapPresentation.Empty, WormholeMapPresentationBuilder.build(emptyList(), scene))

        val presentation = WormholeMapPresentationBuilder.build(
            listOf(WormholeConnection.between(1, 2)),
            scene,
        )

        assertEquals(listOf("wormhole:1:2"), presentation.connections.map { it.connection.id })
        assertEquals(0, presentation.omittedConnectionCount)
    }

    @Test
    fun `multiple Wormholes from one system stay separate and deterministic`() {
        val scene = scene()
        val presentation = WormholeMapPresentationBuilder.build(
            listOf(
                WormholeConnection.between(1, 4),
                WormholeConnection.between(1, 2),
                WormholeConnection.between(1, 3),
            ),
            scene,
        )

        assertEquals(
            listOf("wormhole:1:2", "wormhole:1:3", "wormhole:1:4"),
            presentation.connections.map { it.connection.id },
        )
    }

    @Test
    fun `offscreen Wormholes are culled while intersecting connections remain`() {
        val presentation = WormholeMapPresentationBuilder.build(
            listOf(WormholeConnection.between(1, 2), WormholeConnection.between(3, 4)),
            scene(),
        )

        val visible = visibleWormholeConnections(presentation, MapBounds(-10.0, -10.0, 80.0, 10.0))

        assertEquals(listOf("wormhole:1:2"), visible.map { it.connection.id })
    }

    @Test
    fun `mixed active route including Wormhole projects every leg in original order`() {
        val route = RouteResult(
            1,
            4,
            listOf(1, 2, 3, 4),
            listOf(
                edge(1, 2, RouteEdgeType.STARGATE),
                edge(2, 3, RouteEdgeType.WORMHOLE),
                edge(3, 4, RouteEdgeType.ANSIBLEX),
            ),
        )

        val overlay = ProjectedRouteOverlayBuilder.build(route, scene())

        assertSame(route, activeNormalRouteForRenderer(route))
        assertEquals(0, overlay.omittedLegCount)
        assertEquals(
            listOf(RouteEdgeType.STARGATE, RouteEdgeType.WORMHOLE, RouteEdgeType.ANSIBLEX),
            overlay.legs.map { it.edge.type },
        )
        assertTrue(overlay.legs.all { routeLegRenderStyle(it.edge.type).strokeWidth > 0f })
    }

    private fun scene() = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(system(1, 0.0), system(2, 50.0), system(3, 250.0), system(4, 320.0)),
            connections = emptyList(),
        ),
        OfficialPosition2DProjection,
    )

    private fun system(id: Int, x: Double) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 20,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(x * MAP_UNIT, 0.0, 0.0),
        schematicPosition = SchematicPosition(x * MAP_UNIT, 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private fun edge(from: Int, to: Int, type: RouteEdgeType) = RouteEdge(
        RouteEdgeId("${type.name.lowercase()}:$from:$to"),
        RouteConnectionId("${type.name.lowercase()}:$from:$to"),
        from,
        to,
        type,
    )

    private companion object {
        const val MAP_UNIT = 1_000_000_000_000_000.0
    }
}
