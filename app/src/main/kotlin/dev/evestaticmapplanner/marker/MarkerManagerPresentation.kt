package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import dev.evestaticmapplanner.localization.MarkerMessage
import dev.evestaticmapplanner.localization.MarkerStrings
import dev.evestaticmapplanner.localization.MarkerUiMessage
import dev.evestaticmapplanner.localization.UiMessage
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog

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
        strings: MarkerStrings = AppStringsCatalog.forLocale(AppLocale.EN_US).marker,
    ): MarkerManagerPresentation {
        val normalizedQuery = query.trim()
        val rows = state.markersBySystemId.values.asSequence()
            .filter { it.persistence == MarkerPersistence.SAVED }
            .map { marker -> marker.toRow(systemNamesById[marker.systemId] ?: strings.fallbackSystem(marker.systemId)) }
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

internal fun markerCreationConflict(marker: Marker?): UiMessage? = when (marker?.persistence) {
    null -> null
    MarkerPersistence.TEMPORARY -> MarkerUiMessage(MarkerMessage.TEMPORARY_CONFLICT)
    MarkerPersistence.SAVED -> MarkerUiMessage(MarkerMessage.SAVED_CONFLICT)
}

private fun Marker.toRow(systemName: String) = SavedMarkerRowPresentation(
    systemId = systemId,
    systemName = systemName,
    markerName = name,
    color = color,
    notes = notes,
    createdBy = checkNotNull(createdBy),
)

internal fun savedMarkerProvenanceLabel(createdBy: SavedMarkerCreatedBy, strings: MarkerStrings): String? =
    if (createdBy == SavedMarkerCreatedBy.AI) strings.createdByAi else null
