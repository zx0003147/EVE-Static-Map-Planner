package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.route.RouteEdgeType

/** Small platform-neutral visual contract shared by Desktop Compose and Web Canvas. */
data class MapStrokeSemantics(
    val argb: Long,
    val widthPx: Double,
    val dashPatternPx: List<Double> = emptyList(),
    val curved: Boolean = false,
)

enum class PrimarySystemNodeShape { SYSTEM, KEEPSTAR }

enum class SystemStateOverlayPriority {
    COVERAGE,
    ROUTE,
    WAYPOINT,
    SELECTED_OR_HOVERED,
}

object MapVisualSemantics {
    val stargateNetwork = MapStrokeSemantics(0x553F6685L, 1.0)
    val ansiblexNetwork = MapStrokeSemantics(0x997C5CE0L, 1.5, listOf(6.0, 5.0), curved = true)
    val capitalRoute = MapStrokeSemantics(0xFFB388FFL, 4.0)

    val normalRouteByEdgeType: Map<RouteEdgeType, MapStrokeSemantics> = mapOf(
        RouteEdgeType.STARGATE to MapStrokeSemantics(0xFF42D6F5L, 3.0),
        RouteEdgeType.ANSIBLEX to MapStrokeSemantics(0xFFFF9F43L, 4.0, listOf(12.0, 7.0), curved = true),
        RouteEdgeType.WORMHOLE to MapStrokeSemantics(0xFF32D6C5L, 4.0),
    )

    val overlayPriority = SystemStateOverlayPriority.entries

    fun primaryNodeShape(hasKeepstarSavedMarkerType: Boolean): PrimarySystemNodeShape =
        if (hasKeepstarSavedMarkerType) PrimarySystemNodeShape.KEEPSTAR else PrimarySystemNodeShape.SYSTEM
}
