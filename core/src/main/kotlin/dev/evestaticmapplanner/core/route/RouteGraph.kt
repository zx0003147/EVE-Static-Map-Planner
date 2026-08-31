package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.wormhole.WormholeConnection

enum class RouteEdgeType {
    STARGATE,
    ANSIBLEX,
    WORMHOLE,
}

@JvmInline
value class RouteEdgeId(val value: String) {
    init {
        require(value.isNotBlank()) { "Route edge ID must not be blank" }
    }
}

@JvmInline
value class RouteConnectionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Route connection ID must not be blank" }
    }
}

data class RouteEdge(
    val id: RouteEdgeId,
    val connectionId: RouteConnectionId,
    val fromSystemId: Int,
    val toSystemId: Int,
    val type: RouteEdgeType,
) {
    init {
        require(fromSystemId > 0 && toSystemId > 0) { "Solar system IDs must be positive" }
        require(fromSystemId != toSystemId) { "Route edge cannot be a self-loop" }
    }
}

class RouteGraph(
    val systemIds: Set<Int>,
    adjacency: Map<Int, List<RouteEdge>>,
) {
    private val adjacency = adjacency.mapValues { (_, edges) -> edges.sortedWith(EDGE_ORDER) }

    init {
        require(systemIds.all { it > 0 }) { "Route graph system IDs must be positive" }
        this.adjacency.forEach { (systemId, edges) ->
            require(systemId in systemIds) { "Adjacency references unknown origin system $systemId" }
            require(edges.all { it.fromSystemId == systemId && it.toSystemId in systemIds }) {
                "Adjacency contains an inconsistent or unknown route edge"
            }
        }
    }

    fun containsSystem(systemId: Int): Boolean = systemId in systemIds

    fun neighbors(systemId: Int): List<RouteEdge> = adjacency[systemId].orEmpty()

    companion object {
        private val EDGE_ORDER = compareBy<RouteEdge>(
            { it.type == RouteEdgeType.WORMHOLE },
            RouteEdge::toSystemId,
            { it.type.ordinal },
            { it.id.value },
        )
    }
}

object RouteGraphBuilder {
    fun build(
        staticMapData: StaticMapData,
        ansiblexConnections: List<AnsiblexConnection> = emptyList(),
        wormholeConnections: List<WormholeConnection> = emptyList(),
    ): RouteGraph {
        val systemIds = staticMapData.systems.mapTo(linkedSetOf()) { it.id }
        val edges = mutableListOf<RouteEdge>()

        staticMapData.connections.sortedWith(compareBy({ it.firstSystemId }, { it.secondSystemId })).forEach {
            require(it.firstSystemId in systemIds && it.secondSystemId in systemIds) {
                "Stargate connection references an unknown solar system"
            }
            val connectionId = RouteConnectionId("stargate:${it.firstSystemId}:${it.secondSystemId}")
            edges += edge(connectionId, it.firstSystemId, it.secondSystemId, RouteEdgeType.STARGATE)
            edges += edge(connectionId, it.secondSystemId, it.firstSystemId, RouteEdgeType.STARGATE)
        }

        ansiblexConnections.asSequence()
            .filter(AnsiblexConnection::enabled)
            .sortedWith(compareBy({ it.firstSystemId }, { it.secondSystemId }, { it.id }))
            .forEach { connection ->
                require(connection.firstSystemId in systemIds && connection.secondSystemId in systemIds) {
                    "Ansiblex connection ${connection.id} references an unknown solar system"
                }
                val connectionId = RouteConnectionId("ansiblex:${connection.id}")
                when (connection.direction) {
                    AnsiblexDirection.BIDIRECTIONAL -> {
                        edges += edge(connectionId, connection.firstSystemId, connection.secondSystemId, RouteEdgeType.ANSIBLEX)
                        edges += edge(connectionId, connection.secondSystemId, connection.firstSystemId, RouteEdgeType.ANSIBLEX)
                    }
                    AnsiblexDirection.FIRST_TO_SECOND -> {
                        edges += edge(connectionId, connection.firstSystemId, connection.secondSystemId, RouteEdgeType.ANSIBLEX)
                    }
                    AnsiblexDirection.SECOND_TO_FIRST -> {
                        edges += edge(connectionId, connection.secondSystemId, connection.firstSystemId, RouteEdgeType.ANSIBLEX)
                    }
                }
            }

        wormholeConnections.asSequence()
            .sortedWith(compareBy({ it.firstSystemId }, { it.secondSystemId }, { it.id }))
            .forEach { connection ->
                require(connection.firstSystemId in systemIds && connection.secondSystemId in systemIds) {
                    "Wormhole connection ${connection.id} references an unknown solar system"
                }
                val connectionId = RouteConnectionId(connection.id)
                edges += edge(connectionId, connection.firstSystemId, connection.secondSystemId, RouteEdgeType.WORMHOLE)
                edges += edge(connectionId, connection.secondSystemId, connection.firstSystemId, RouteEdgeType.WORMHOLE)
            }

        return RouteGraph(
            systemIds = systemIds,
            adjacency = edges.distinctBy(RouteEdge::id).groupBy(RouteEdge::fromSystemId),
        )
    }

    private fun edge(
        connectionId: RouteConnectionId,
        fromSystemId: Int,
        toSystemId: Int,
        type: RouteEdgeType,
    ) = RouteEdge(
        id = RouteEdgeId("${connectionId.value}:$fromSystemId:$toSystemId"),
        connectionId = connectionId,
        fromSystemId = fromSystemId,
        toSystemId = toSystemId,
        type = type,
    )
}
