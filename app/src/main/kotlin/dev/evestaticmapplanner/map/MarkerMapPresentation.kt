package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.marker.SavedMarkerChildVisual
import dev.evestaticmapplanner.marker.SavedMarkerChildVisuals
import dev.evestaticmapplanner.preferences.MarkerPreferences
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class PresentedMapMarker(
    val marker: Marker,
    val screenCenter: MapPoint,
    val visibleName: String?,
    val visualStyle: MarkerVisualStyle,
    val children: List<PresentedSavedMarkerChild> = emptyList(),
)

data class PresentedSavedMarkerChild(
    val child: SavedMarkerChild,
    val visual: SavedMarkerChildVisual,
    val screenCenter: MapPoint,
)

enum class MarkerVisualStyle {
    OUTER_RING,
    OUTLINE_DIAMOND,
}

object MarkerMapPresentationBuilder {
    fun build(
        scene: ProjectedMapScene,
        transform: MapTransform,
        visibleSystemIds: Collection<Int>,
        markersBySystemId: Map<Int, Marker>,
        preferences: MarkerPreferences,
        semanticMode: SemanticLabelMode,
        offsetPx: Double,
        childrenByParentSystemId: Map<Int, List<SavedMarkerChild>> = emptyMap(),
        expandedSystemIds: Set<Int> = emptySet(),
        childOrbitRadiusPx: Double = 38.0,
    ): List<PresentedMapMarker> {
        if (!preferences.showMarkers) return emptyList()
        return visibleSystemIds.mapNotNull { systemId ->
            val marker = markersBySystemId[systemId] ?: return@mapNotNull null
            val node = scene.nodesById[systemId] ?: return@mapNotNull null
            val systemPosition = transform.worldToScreen(node.position)
            val saved = marker.persistence == MarkerPersistence.SAVED
            PresentedMapMarker(
                marker = marker,
                screenCenter = if (saved) systemPosition else MapPoint(systemPosition.x - offsetPx, systemPosition.y - offsetPx),
                visibleName = marker.name.takeIf {
                    preferences.showMarkerNames && semanticMode == SemanticLabelMode.SYSTEM
                },
                visualStyle = if (saved) {
                    MarkerVisualStyle.OUTER_RING
                } else {
                    MarkerVisualStyle.OUTLINE_DIAMOND
                },
                children = if (saved && systemId in expandedSystemIds) {
                    radialChildren(childrenByParentSystemId[systemId].orEmpty(), systemPosition, childOrbitRadiusPx)
                } else {
                    emptyList()
                },
            )
        }
    }

    private fun radialChildren(
        children: List<SavedMarkerChild>,
        center: MapPoint,
        orbitRadiusPx: Double,
    ): List<PresentedSavedMarkerChild> = children.mapIndexed { index, child ->
        val angle = Math.toRadians(-90.0 + index * (360.0 / children.size))
        PresentedSavedMarkerChild(
            child = child,
            visual = SavedMarkerChildVisuals.resolve(child.type),
            screenCenter = MapPoint(center.x + cos(angle) * orbitRadiusPx, center.y + sin(angle) * orbitRadiusPx),
        )
    }
}

data class SavedMarkerPointerHit(
    val parentSystemId: Int,
    val child: PresentedSavedMarkerChild?,
)

internal fun isSavedMarkerExpanded(
    isHovered: Boolean,
    isSelected: Boolean,
    isInteractionActive: Boolean = false,
): Boolean = isHovered || isSelected || isInteractionActive

internal fun hitTestSavedMarkerInteraction(
    markers: List<PresentedMapMarker>,
    point: MapPoint,
    badgeHitRadiusPx: Double,
    corridorRadiusPx: Double,
): SavedMarkerPointerHit? {
    markers.asReversed().forEach { marker ->
        marker.children.asReversed().firstOrNull { child ->
            distance(point, child.screenCenter) <= badgeHitRadiusPx
        }?.let { return SavedMarkerPointerHit(marker.marker.systemId, it) }
    }
    markers.asReversed().forEach { marker ->
        if (marker.children.any { child ->
                distanceToSegment(point, marker.screenCenter, child.screenCenter) <= corridorRadiusPx
            }
        ) return SavedMarkerPointerHit(marker.marker.systemId, null)
    }
    return null
}

private fun distance(first: MapPoint, second: MapPoint): Double = hypot(first.x - second.x, first.y - second.y)

private fun distanceToSegment(point: MapPoint, start: MapPoint, end: MapPoint): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0.0) return distance(point, start)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return distance(point, MapPoint(start.x + t * dx, start.y + t * dy))
}
