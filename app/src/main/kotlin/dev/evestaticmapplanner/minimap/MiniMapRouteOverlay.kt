package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.route.RouteEdgeType

enum class MiniMapRouteKind {
    USER_NORMAL,
    MISSION_NORMAL,
    MISSION_CAPITAL,
}

data class MiniMapRouteSegment(
    val fromSystemId: Int,
    val toSystemId: Int,
    val from: MapPoint,
    val to: MapPoint,
    val edgeType: RouteEdgeType? = null,
)

/** A clipped, render-only view of an already-calculated route. */
data class MiniMapRouteOverlay(
    val id: String,
    val kind: MiniMapRouteKind,
    val styleIndex: Int,
    val segments: List<MiniMapRouteSegment>,
)
