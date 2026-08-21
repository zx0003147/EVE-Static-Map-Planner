package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.preferences.MarkerPreferences

data class PresentedMapMarker(
    val marker: Marker,
    val screenCenter: MapPoint,
    val visibleName: String?,
    val visualStyle: MarkerVisualStyle,
)

enum class MarkerVisualStyle {
    SOLID_DIAMOND,
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
    ): List<PresentedMapMarker> {
        if (!preferences.showMarkers) return emptyList()
        return visibleSystemIds.mapNotNull { systemId ->
            val marker = markersBySystemId[systemId] ?: return@mapNotNull null
            val node = scene.nodesById[systemId] ?: return@mapNotNull null
            val systemPosition = transform.worldToScreen(node.position)
            PresentedMapMarker(
                marker = marker,
                screenCenter = MapPoint(systemPosition.x - offsetPx, systemPosition.y - offsetPx),
                visibleName = marker.name.takeIf {
                    preferences.showMarkerNames && semanticMode == SemanticLabelMode.SYSTEM
                },
                visualStyle = if (marker.persistence == MarkerPersistence.SAVED) {
                    MarkerVisualStyle.SOLID_DIAMOND
                } else {
                    MarkerVisualStyle.OUTLINE_DIAMOND
                },
            )
        }
    }
}
