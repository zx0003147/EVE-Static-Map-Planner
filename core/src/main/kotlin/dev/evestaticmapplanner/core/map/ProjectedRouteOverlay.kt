package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteResult

data class ProjectedRouteLeg(
    val edge: RouteEdge,
    val from: MapPoint,
    val to: MapPoint,
)

data class ProjectedRouteOverlay(
    val route: RouteResult,
    val legs: List<ProjectedRouteLeg>,
    val omittedSystemIds: Set<Int>,
    val omittedLegCount: Int,
)

object ProjectedRouteOverlayBuilder {
    fun build(route: RouteResult, scene: ProjectedMapScene): ProjectedRouteOverlay {
        val omittedSystems = route.systems.filterTo(linkedSetOf()) { it !in scene.nodesById }
        val legs = route.edges.mapNotNull { edge ->
            val from = scene.nodesById[edge.fromSystemId]?.position ?: return@mapNotNull null
            val to = scene.nodesById[edge.toSystemId]?.position ?: return@mapNotNull null
            ProjectedRouteLeg(edge, from, to)
        }
        return ProjectedRouteOverlay(
            route = route,
            legs = legs,
            omittedSystemIds = omittedSystems,
            omittedLegCount = route.edges.size - legs.size,
        )
    }
}
