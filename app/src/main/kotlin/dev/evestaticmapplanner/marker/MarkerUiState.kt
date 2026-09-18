package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.localization.UiMessage

data class MarkerUiState(
    val isLoading: Boolean = true,
    val markersBySystemId: Map<Int, Marker> = emptyMap(),
    val childrenByParentSystemId: Map<Int, List<SavedMarkerChild>> = emptyMap(),
    val busySystemIds: Set<Int> = emptySet(),
    val databaseError: String? = null,
    val operationError: UiMessage? = null,
) {
    val canCreateMarkers: Boolean get() = !isLoading && databaseError == null
}
