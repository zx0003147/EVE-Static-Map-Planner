package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker

data class MarkerUiState(
    val isLoading: Boolean = true,
    val markersBySystemId: Map<Int, Marker> = emptyMap(),
    val busySystemIds: Set<Int> = emptySet(),
    val databaseError: String? = null,
    val operationError: String? = null,
) {
    val canCreateMarkers: Boolean get() = !isLoading && databaseError == null
}
