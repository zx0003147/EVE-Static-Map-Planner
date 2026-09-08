package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.model.StaticMapData

enum class RouteEdgeType {
    STARGATE,
    ANSIBLEX,
    WORMHOLE,
}

enum class RouteLinkDirection {
    BIDIRECTIONAL,
    FIRST_TO_SECOND,
    SECOND_TO_FIRST,
}

/** Platform-neutral input for non-SDE route links such as Ansiblex and temporary Wormholes. */
data class RouteLink(
    val connectionId: RouteConnectionId,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val direction: RouteLinkDirection,
    val type: RouteEdgeType,
    val enabled: Boolean = true,
) {
    init {
        require(firstSystemId > 0 && secondSystemId > 0 && firstSystemId < secondSystemId) {
            "Route link endpoints must be canonical, distinct, and positive"
        }
        require(type != RouteEdgeType.STARGATE) { "SDE Stargates must come from StaticMapData" }
    }
}

data class RouteEdgeId(val value: String) {
    init {
        require(value.isNotBlank()) { "Route edge ID must not be blank" }
    }
}

data class RouteConnectionId(val value: String) {
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
        routeLinks: List<RouteLink> = emptyList(),
    ): RouteGraph {
        val systemIds = staticMapData.systems.mapTo(linkedSetOf()) { it.id }
        val edges = mutableListOf<RouteEdge>()

        staticMapData.connections.sortedWith(compareBy({ it.firstSystemId }, { it.secondSystemId })).forEach {
            require(it.firstSystemId in systemIds && it.secondSystemId in systemIds) {
                "Stargate connection references an unknown solar system"
            }
            val connectionId = RouteConnectionId("stargate:${it.firstSystemId}:${it.secondSystemId}")
            edges += routeEdge(connectionId, it.firstSystemId, it.secondSystemId, RouteEdgeType.STARGATE)
            edges += routeEdge(connectionId, it.secondSystemId, it.firstSystemId, RouteEdgeType.STARGATE)
        }

        routeLinks.asSequence()
            .filter(RouteLink::enabled)
            .forEach { link ->
                require(link.firstSystemId in systemIds && link.secondSystemId in systemIds) {
                    "Route link ${link.connectionId.value} references an unknown solar system"
                }
            }
        edges += RouteLinkEdgeBuilder.build(routeLinks)

        return RouteGraph(
            systemIds = systemIds,
            adjacency = edges.distinctBy(RouteEdge::id).groupBy(RouteEdge::fromSystemId),
        )
    }
}

/** Converts enabled, directional platform-neutral links to route-engine edges. */
object RouteLinkEdgeBuilder {
    fun build(links: List<RouteLink>): List<RouteEdge> = buildList {
        links.asSequence()
            .filter(RouteLink::enabled)
            .sortedWith(compareBy({ it.type.ordinal }, { it.firstSystemId }, { it.secondSystemId }, { it.connectionId.value }))
            .forEach { link ->
                when (link.direction) {
                    RouteLinkDirection.BIDIRECTIONAL -> {
                        add(routeEdge(link.connectionId, link.firstSystemId, link.secondSystemId, link.type))
                        add(routeEdge(link.connectionId, link.secondSystemId, link.firstSystemId, link.type))
                    }
                    RouteLinkDirection.FIRST_TO_SECOND -> {
                        add(routeEdge(link.connectionId, link.firstSystemId, link.secondSystemId, link.type))
                    }
                    RouteLinkDirection.SECOND_TO_FIRST -> {
                        add(routeEdge(link.connectionId, link.secondSystemId, link.firstSystemId, link.type))
                    }
                }
            }
    }
}

private fun routeEdge(
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
