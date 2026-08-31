package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteGraphTest {
    @Test
    fun `one Wormhole creates two directed traversal edges`() {
        val graph = buildGraph(1, 2, wormholes = listOf(WormholeConnection.between(2, 1)))

        assertEquals(listOf(edgeSummary(1, 2, RouteEdgeType.WORMHOLE)), graph.neighbors(1).map(::summary))
        assertEquals(listOf(edgeSummary(2, 1, RouteEdgeType.WORMHOLE)), graph.neighbors(2).map(::summary))
        assertEquals(graph.neighbors(1).single().connectionId, graph.neighbors(2).single().connectionId)
    }

    @Test
    fun `one system can have multiple Wormhole neighbors and multiple Wormholes coexist`() {
        val graph = buildGraph(
            1, 2, 3, 4,
            wormholes = listOf(
                WormholeConnection.between(1, 4),
                WormholeConnection.between(1, 2),
                WormholeConnection.between(3, 1),
            ),
        )

        assertEquals(listOf(2, 3, 4), graph.neighbors(1).map(RouteEdge::toSystemId))
        assertEquals(listOf(1), graph.neighbors(2).map(RouteEdge::toSystemId))
        assertEquals(listOf(1), graph.neighbors(3).map(RouteEdge::toSystemId))
        assertEquals(listOf(1), graph.neighbors(4).map(RouteEdge::toSystemId))
    }

    @Test
    fun `parallel Stargate Ansiblex and Wormhole edges coexist without cross-type deduplication`() {
        val graph = buildGraph(
            1, 2,
            gates = listOf(1 to 2),
            ansiblex = listOf(ansiblex("parallel", 1, 2)),
            wormholes = listOf(WormholeConnection.between(1, 2)),
        )

        assertEquals(
            listOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX, RouteEdgeType.WORMHOLE),
            graph.neighbors(1).map(RouteEdge::type),
        )
        assertEquals(3, graph.neighbors(1).map(RouteEdge::id).distinct().size)
    }

    @Test
    fun `neighbor ordering preserves existing edges and stably appends Wormholes`() {
        val data = staticData(1, 2, 3, 4, 5, 6, gates = listOf(1 to 4, 1 to 2))
        val ansiblex = listOf(ansiblex("to-five", 1, 5), ansiblex("to-three", 1, 3))
        val wormholes = listOf(WormholeConnection.between(1, 6), WormholeConnection.between(1, 2))

        val expected = listOf(
            edgeSummary(1, 2, RouteEdgeType.STARGATE),
            edgeSummary(1, 3, RouteEdgeType.ANSIBLEX),
            edgeSummary(1, 4, RouteEdgeType.STARGATE),
            edgeSummary(1, 5, RouteEdgeType.ANSIBLEX),
            edgeSummary(1, 2, RouteEdgeType.WORMHOLE),
            edgeSummary(1, 6, RouteEdgeType.WORMHOLE),
        )

        repeat(20) {
            assertEquals(expected, RouteGraphBuilder.build(data, ansiblex, wormholes.reversed()).neighbors(1).map(::summary))
        }
    }

    @Test
    fun `empty Wormhole input leaves the existing graph unchanged`() {
        val data = staticData(1, 2, 3, gates = listOf(1 to 2, 2 to 3))
        val ansiblex = listOf(ansiblex("shortcut", 1, 3))

        val existingCall = RouteGraphBuilder.build(data, ansiblex)
        val explicitEmptyWormholes = RouteGraphBuilder.build(data, ansiblex, emptyList())

        data.systems.forEach { system ->
            assertEquals(existingCall.neighbors(system.id), explicitEmptyWormholes.neighbors(system.id))
        }
    }

    private fun buildGraph(
        vararg systemIds: Int,
        gates: List<Pair<Int, Int>> = emptyList(),
        ansiblex: List<AnsiblexConnection> = emptyList(),
        wormholes: List<WormholeConnection> = emptyList(),
    ) = RouteGraphBuilder.build(staticData(*systemIds, gates = gates), ansiblex, wormholes)

    private fun staticData(
        vararg systemIds: Int,
        gates: List<Pair<Int, Int>> = emptyList(),
    ) = StaticMapData(
        systems = systemIds.map(::system),
        connections = gates.map { StargateConnection.between(it.first, it.second) }.distinct(),
    )

    private fun ansiblex(id: String, first: Int, second: Int) = AnsiblexConnection(
        id = id,
        firstSystemId = minOf(first, second),
        secondSystemId = maxOf(first, second),
        direction = AnsiblexDirection.BIDIRECTIONAL,
        displayName = null,
        notes = null,
        source = AnsiblexSource.MANUAL,
        sourceBatchId = null,
        enabled = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun system(id: Int) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 1,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(id.toDouble(), 0.0, id.toDouble()),
        schematicPosition = SchematicPosition(id.toDouble(), id.toDouble()),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private fun summary(edge: RouteEdge) = edgeSummary(edge.fromSystemId, edge.toSystemId, edge.type)

    private fun edgeSummary(from: Int, to: Int, type: RouteEdgeType) = Triple(from, to, type)
}
