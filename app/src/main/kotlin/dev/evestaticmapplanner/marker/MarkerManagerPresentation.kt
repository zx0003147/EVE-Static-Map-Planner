package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy

data class SavedMarkerRowPresentation(
    val systemId: Int,
    val systemName: String,
    val markerName: String?,
    val color: MarkerColor,
    val notes: String?,
    val createdBy: SavedMarkerCreatedBy,
)

data class MarkerManagerPresentation(
    val rows: List<SavedMarkerRowPresentation>,
    val selectedRow: SavedMarkerRowPresentation?,
    val selectionActionsEnabled: Boolean,
)

object MarkerManagerPresentationBuilder {
    fun build(
        state: MarkerUiState,
        systemNamesById: Map<Int, String>,
        query: String,
        selectedSystemId: Int?,
    ): MarkerManagerPresentation {
        val normalizedQuery = query.trim()
        val rows = state.markersBySystemId.values.asSequence()
            .filter { it.persistence == MarkerPersistence.SAVED }
            .map { marker -> marker.toRow(systemNamesById[marker.systemId] ?: "System ${marker.systemId}") }
            .filter { row ->
                normalizedQuery.isEmpty() ||
                    row.systemName.contains(normalizedQuery, ignoreCase = true) ||
                    row.markerName?.contains(normalizedQuery, ignoreCase = true) == true ||
                    row.notes?.contains(normalizedQuery, ignoreCase = true) == true
            }
            .sortedWith(compareBy<SavedMarkerRowPresentation>({ it.systemName.lowercase() }, { it.systemId }))
            .toList()
        val selected = rows.singleOrNull { it.systemId == selectedSystemId }
        return MarkerManagerPresentation(
            rows = rows,
            selectedRow = selected,
            selectionActionsEnabled = selected != null && selected.systemId !in state.busySystemIds,
        )
    }
}

internal fun markerCreationConflict(marker: Marker?): String? = when (marker?.persistence) {
    null -> null
    MarkerPersistence.TEMPORARY ->
        "This system already has a temporary marker. Use Save Permanently on that marker instead."
    MarkerPersistence.SAVED -> "This system already has a marker."
}

private fun Marker.toRow(systemName: String) = SavedMarkerRowPresentation(
    systemId = systemId,
    systemName = systemName,
    markerName = name,
    color = color,
    notes = notes,
    createdBy = checkNotNull(createdBy),
)

internal fun savedMarkerProvenanceLabel(createdBy: SavedMarkerCreatedBy): String? =
    if (createdBy == SavedMarkerCreatedBy.AI) "Created by AI" else null
