package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.ProjectedMapScene

enum class RouteWaypointKind {
    USER_NORMAL,
    USER_CAPITAL,
    MISSION_NORMAL,
    MISSION_CAPITAL,
}

data class RouteWaypointSource(
    val routeKey: String,
    val kind: RouteWaypointKind,
    val systemIds: List<Int>,
)

data class PresentedRouteWaypoint(
    val routeKey: String,
    val kind: RouteWaypointKind,
    val systemId: Int,
    val sequenceNumber: Int,
    val stackIndex: Int,
)

object RouteWaypointPresentationBuilder {
    fun build(scene: ProjectedMapScene, sources: List<RouteWaypointSource>): List<PresentedRouteWaypoint> {
        val countsBySystem = mutableMapOf<Int, Int>()
        return buildList {
            sources.forEach { source ->
                source.systemIds.forEachIndexed { index, systemId ->
                    if (systemId in scene.nodesById) {
                        val stackIndex = countsBySystem.getOrDefault(systemId, 0)
                        countsBySystem[systemId] = stackIndex + 1
                        add(
                            PresentedRouteWaypoint(
                                routeKey = source.routeKey,
                                kind = source.kind,
                                systemId = systemId,
                                sequenceNumber = index + 1,
                                stackIndex = stackIndex,
                            ),
                        )
                    }
                }
            }
        }
    }
}
