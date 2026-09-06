package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import java.util.ArrayDeque

data class MiniMapSliceEdge(
    val connectionId: RouteConnectionId,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val type: RouteEdgeType,
    val first: MapPoint,
    val second: MapPoint,
)

data class MiniMapSlice(
    val centerSystemId: Int,
    val maxHops: Int,
    val distanceBySystem: Map<Int, Int>,
    val nodes: List<ProjectedSystemNode>,
    val edges: List<MiniMapSliceEdge>,
    val localBounds: MapBounds,
) {
    val includedSystemIds: Set<Int> = distanceBySystem.keys
}

/** Pure topology extraction over a shared projected scene; it owns no repository or UI state. */
object HopNeighborhoodExtractor {
    fun extract(
        scene: ProjectedMapScene,
        centerSystemId: Int,
        maxHops: Int,
        allowedEdgeTypes: Set<RouteEdgeType> = setOf(RouteEdgeType.STARGATE),
        additionalEdges: List<RouteEdge> = emptyList(),
    ): MiniMapSlice {
        require(maxHops in 1..5) { "Mini-map Stargate hops must be between 1 and 5" }
        require(centerSystemId in scene.nodesById) { "Mini-map center is absent from the projected scene" }

        val directed = buildList {
            if (RouteEdgeType.STARGATE in allowedEdgeTypes) {
                scene.edges.forEach { edge ->
                    val id = RouteConnectionId("stargate:${edge.firstSystemId}:${edge.secondSystemId}")
                    add(RouteEdge(RouteEdgeId("${id.value}:${edge.firstSystemId}:${edge.secondSystemId}"), id,
                        edge.firstSystemId, edge.secondSystemId, RouteEdgeType.STARGATE))
                    add(RouteEdge(RouteEdgeId("${id.value}:${edge.secondSystemId}:${edge.firstSystemId}"), id,
                        edge.secondSystemId, edge.firstSystemId, RouteEdgeType.STARGATE))
                }
            }
            addAll(additionalEdges.filter { it.type in allowedEdgeTypes })
        }.filter { it.fromSystemId in scene.nodesById && it.toSystemId in scene.nodesById }

        val adjacency = directed.groupBy(RouteEdge::fromSystemId)
        val distance = linkedMapOf(centerSystemId to 0)
        val queue = ArrayDeque<Int>().apply { add(centerSystemId) }
        while (queue.isNotEmpty()) {
            val from = queue.removeFirst()
            val nextDistance = distance.getValue(from) + 1
            if (nextDistance > maxHops) continue
            adjacency[from].orEmpty().forEach { edge ->
                if (edge.toSystemId !in distance) {
                    distance[edge.toSystemId] = nextDistance
                    queue.addLast(edge.toSystemId)
                }
            }
        }

        val included = distance.keys
        val nodes = included.map(scene.nodesById::getValue).sortedBy { distance.getValue(it.system.id) }
        val edges = directed.asSequence()
            .filter { it.fromSystemId in included && it.toSystemId in included }
            .groupBy(RouteEdge::connectionId)
            .values
            .map { directions ->
                val edge = directions.minWith(compareBy(RouteEdge::fromSystemId, RouteEdge::toSystemId))
                MiniMapSliceEdge(
                    edge.connectionId,
                    minOf(edge.fromSystemId, edge.toSystemId),
                    maxOf(edge.fromSystemId, edge.toSystemId),
                    edge.type,
                    scene.nodesById.getValue(minOf(edge.fromSystemId, edge.toSystemId)).position,
                    scene.nodesById.getValue(maxOf(edge.fromSystemId, edge.toSystemId)).position,
                )
            }
            .sortedWith(compareBy({ it.type.ordinal }, MiniMapSliceEdge::firstSystemId, MiniMapSliceEdge::secondSystemId))
            .toList()
        return MiniMapSlice(
            centerSystemId,
            maxHops,
            distance.toMap(),
            nodes,
            edges,
            MapBounds.fromPoints(nodes.map(ProjectedSystemNode::position)),
        )
    }
}
