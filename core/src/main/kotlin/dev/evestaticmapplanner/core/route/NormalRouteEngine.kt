package dev.evestaticmapplanner.core.route

import java.util.ArrayDeque

data class RouteOptions(
    val useAnsiblex: Boolean = false,
    val useWormholes: Boolean = false,
)

data class RouteResult(
    val startSystemId: Int,
    val destinationSystemId: Int,
    val systems: List<Int>,
    val edges: List<RouteEdge>,
) {
    init {
        require(systems.isNotEmpty()) { "Route must contain at least one solar system" }
        require(systems.first() == startSystemId && systems.last() == destinationSystemId) {
            "Route endpoints do not match its solar system sequence"
        }
        require(systems.size == edges.size + 1) { "Route must contain exactly one more system than edge" }
        edges.forEachIndexed { index, edge ->
            require(edge.fromSystemId == systems[index] && edge.toSystemId == systems[index + 1]) {
                "Route edge $index does not match its adjacent solar systems"
            }
        }
    }

    val totalJumps: Int get() = edges.size
    val stargateJumps: Int get() = edges.count { it.type == RouteEdgeType.STARGATE }
    val ansiblexJumps: Int get() = edges.count { it.type == RouteEdgeType.ANSIBLEX }
    val wormholeJumps: Int get() = edges.count { it.type == RouteEdgeType.WORMHOLE }
}

enum class RouteEndpoint {
    START,
    DESTINATION,
}

sealed interface RouteCalculationOutcome {
    data class Found(val route: RouteResult) : RouteCalculationOutcome
    data class SameSystem(val route: RouteResult) : RouteCalculationOutcome
    data class Unreachable(val startSystemId: Int, val destinationSystemId: Int) : RouteCalculationOutcome
    data class InvalidEndpoint(
        val invalid: Set<RouteEndpoint>,
        val startSystemId: Int,
        val destinationSystemId: Int,
    ) : RouteCalculationOutcome
}

class NormalRouteEngine {
    fun calculate(
        graph: RouteGraph,
        startSystemId: Int,
        destinationSystemId: Int,
        options: RouteOptions = RouteOptions(),
    ): RouteCalculationOutcome {
        val invalid = buildSet {
            if (!graph.containsSystem(startSystemId)) add(RouteEndpoint.START)
            if (!graph.containsSystem(destinationSystemId)) add(RouteEndpoint.DESTINATION)
        }
        if (invalid.isNotEmpty()) {
            return RouteCalculationOutcome.InvalidEndpoint(invalid, startSystemId, destinationSystemId)
        }
        if (startSystemId == destinationSystemId) {
            return RouteCalculationOutcome.SameSystem(
                RouteResult(startSystemId, destinationSystemId, listOf(startSystemId), emptyList()),
            )
        }

        val queue = ArrayDeque<Int>()
        val predecessor = mutableMapOf<Int, RouteEdge>()
        val visited = mutableSetOf(startSystemId)
        queue.addLast(startSystemId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (edge in graph.neighbors(current)) {
                if (!options.useAnsiblex && edge.type == RouteEdgeType.ANSIBLEX) continue
                if (!options.useWormholes && edge.type == RouteEdgeType.WORMHOLE) continue
                if (!visited.add(edge.toSystemId)) continue
                predecessor[edge.toSystemId] = edge
                if (edge.toSystemId == destinationSystemId) {
                    return RouteCalculationOutcome.Found(
                        reconstruct(startSystemId, destinationSystemId, predecessor),
                    )
                }
                queue.addLast(edge.toSystemId)
            }
        }

        return RouteCalculationOutcome.Unreachable(startSystemId, destinationSystemId)
    }

    private fun reconstruct(
        startSystemId: Int,
        destinationSystemId: Int,
        predecessor: Map<Int, RouteEdge>,
    ): RouteResult {
        val reversedEdges = mutableListOf<RouteEdge>()
        var current = destinationSystemId
        while (current != startSystemId) {
            val edge = checkNotNull(predecessor[current]) { "Route predecessor chain is incomplete" }
            reversedEdges += edge
            current = edge.fromSystemId
        }
        val edges = reversedEdges.asReversed()
        return RouteResult(
            startSystemId = startSystemId,
            destinationSystemId = destinationSystemId,
            systems = buildList {
                add(startSystemId)
                edges.forEach { add(it.toSystemId) }
            },
            edges = edges,
        )
    }
}
