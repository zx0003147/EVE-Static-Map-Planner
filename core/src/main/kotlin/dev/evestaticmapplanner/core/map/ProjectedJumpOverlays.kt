package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.route.CapitalRouteResult

data class ProjectedJumpRangeOverlay(
    val overlay: JumpRangeOverlay,
    val reachableNodes: List<ProjectedSystemNode>,
    val omittedSystemIds: Set<Int>,
)

object ProjectedJumpRangeOverlayBuilder {
    fun build(overlay: JumpRangeOverlay, scene: ProjectedMapScene): ProjectedJumpRangeOverlay {
        val nodes = overlay.reachableSystemIds.mapNotNull(scene.nodesById::get)
        return ProjectedJumpRangeOverlay(
            overlay = overlay,
            reachableNodes = nodes,
            omittedSystemIds = overlay.reachableSystemIds.filterTo(linkedSetOf()) { it !in scene.nodesById },
        )
    }
}

data class ProjectedCapitalRouteLeg(
    val fromSystemId: Int,
    val toSystemId: Int,
    val distanceLy: Double,
    val from: MapPoint,
    val to: MapPoint,
)

data class ProjectedCapitalRouteOverlay(
    val route: CapitalRouteResult,
    val legs: List<ProjectedCapitalRouteLeg>,
    val omittedSystemIds: Set<Int>,
    val omittedLegCount: Int,
)

object ProjectedCapitalRouteOverlayBuilder {
    fun build(route: CapitalRouteResult, scene: ProjectedMapScene): ProjectedCapitalRouteOverlay {
        val omitted = route.systems.filterTo(linkedSetOf()) { it !in scene.nodesById }
        val legs = route.legs.mapNotNull { leg ->
            val from = scene.nodesById[leg.fromSystemId]?.position ?: return@mapNotNull null
            val to = scene.nodesById[leg.toSystemId]?.position ?: return@mapNotNull null
            ProjectedCapitalRouteLeg(leg.fromSystemId, leg.toSystemId, leg.distanceLy, from, to)
        }
        return ProjectedCapitalRouteOverlay(route, legs, omitted, route.legs.size - legs.size)
    }
}
