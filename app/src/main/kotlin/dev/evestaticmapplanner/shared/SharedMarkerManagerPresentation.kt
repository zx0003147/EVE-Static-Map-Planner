package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import java.time.Instant

internal enum class SharedMarkerManagerSort { SYSTEM, UPDATED, NAME }

internal data class SharedMarkerManagerRow(
    val markerId: String,
    val systemId: Int,
    val systemName: String,
    val systemKnown: Boolean,
    val name: String,
    val color: SharedMarkerColor,
    val tags: List<String>,
    val updatedBy: String,
    val updatedAt: Instant,
    val version: Long,
)

internal data class SharedMarkerManagerPresentation(
    val rows: List<SharedMarkerManagerRow>,
    val selected: SharedMarkerManagerRow?,
    val canWrite: Boolean,
)

internal object SharedMarkerManagerPresentationBuilder {
    fun build(
        state: SharedMapState,
        systemNamesById: Map<Int, String>,
        query: String,
        sort: SharedMarkerManagerSort,
        selectedMarkerId: String?,
    ): SharedMarkerManagerPresentation {
        val normalizedQuery = query.trim()
        val comparator = when (sort) {
            SharedMarkerManagerSort.SYSTEM -> compareBy<SharedMarkerManagerRow>(
                { it.systemName.lowercase() },
                SharedMarkerManagerRow::systemId,
                SharedMarkerManagerRow::markerId,
            )
            SharedMarkerManagerSort.UPDATED -> compareByDescending<SharedMarkerManagerRow> { it.updatedAt }
                .thenBy { it.systemName.lowercase() }
                .thenBy(SharedMarkerManagerRow::markerId)
            SharedMarkerManagerSort.NAME -> compareBy<SharedMarkerManagerRow>(
                { it.name.lowercase() },
                { it.systemName.lowercase() },
                SharedMarkerManagerRow::markerId,
            )
        }
        val rows = state.snapshot?.markers?.values.orEmpty().asSequence()
            .map { it.toManagerRow(systemNamesById[it.systemId]) }
            .filter { row ->
                normalizedQuery.isEmpty() ||
                    row.systemName.contains(normalizedQuery, ignoreCase = true) ||
                    row.name.contains(normalizedQuery, ignoreCase = true) ||
                    row.tags.any { it.contains(normalizedQuery, ignoreCase = true) }
            }
            .sortedWith(comparator)
            .toList()
        return SharedMarkerManagerPresentation(
            rows = rows,
            selected = rows.singleOrNull { it.markerId == selectedMarkerId },
            canWrite = canWriteSharedMarkers(state),
        )
    }
}

private fun SharedMarker.toManagerRow(systemName: String?) = SharedMarkerManagerRow(
    markerId = markerId,
    systemId = systemId,
    systemName = systemName ?: "Unknown System ($systemId)",
    systemKnown = systemName != null,
    name = name,
    color = color,
    tags = tags,
    updatedBy = updatedBy.displayName,
    updatedAt = updatedAt,
    version = version,
)
