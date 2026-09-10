package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapVisualSemantics
import dev.evestaticmapplanner.core.map.PrimarySystemNodeShape
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.marker.markerColor

data class PresentedPrimarySystemNode(
    val systemId: Int,
    val shape: PrimarySystemNodeShape,
    val screenCenter: MapPoint,
    val color: Color,
)

object SystemNodeShapePresentationBuilder {
    fun build(
        visibleSystemIds: Collection<Int>,
        localMarkersBySystemId: Map<Int, Marker>,
        localChildrenBySystemId: Map<Int, List<SavedMarkerChild>>,
        showLocalMarkers: Boolean,
        sharedMarkerState: SharedMarkerPresentationState,
        screenPosition: (Int) -> MapPoint?,
    ): List<PresentedPrimarySystemNode> {
        val localTagsBySystemId: Map<Int, Iterable<String>> = if (showLocalMarkers) {
            localMarkersBySystemId.asSequence()
                .filter { (_, marker) -> marker.persistence == MarkerPersistence.SAVED }
                .associate { (systemId, _) ->
                    systemId to localChildrenBySystemId[systemId].orEmpty().map { it.type.key }
                }
        } else {
            emptyMap()
        }
        val sharedTagsBySystemId: Map<Int, Iterable<String>> = if (sharedMarkerState.isVisible) {
            sharedMarkerState.markersBySystemId.mapValues { (_, marker) -> marker.tags }
        } else {
            emptyMap()
        }
        val shapesBySystemId = MapVisualSemantics.primaryNodeShapes(localTagsBySystemId, sharedTagsBySystemId)
        val visible = visibleSystemIds.toHashSet()
        return shapesBySystemId.mapNotNull { (systemId, shape) ->
            if (systemId !in visible) return@mapNotNull null
            val center = screenPosition(systemId) ?: return@mapNotNull null
            val localShape = MapVisualSemantics.primaryNodeShape(
                localTagsBySystemId[systemId].orEmpty(),
                emptyList(),
            )
            val color = if (localShape == shape) {
                localMarkersBySystemId[systemId]?.let { markerColor(it.color) }
            } else {
                sharedMarkerState.markersBySystemId[systemId]?.let { sharedMarkerColor(it.color) }
            } ?: return@mapNotNull null
            PresentedPrimarySystemNode(systemId, shape, center, color)
        }.sortedBy(PresentedPrimarySystemNode::systemId)
    }
}

private fun Iterable<String>?.orEmpty(): Iterable<String> = this ?: emptyList()
