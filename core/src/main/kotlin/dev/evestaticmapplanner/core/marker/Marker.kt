package dev.evestaticmapplanner.core.marker

import java.time.Instant

enum class MarkerPersistence {
    TEMPORARY,
    SAVED,
}

enum class MarkerColor {
    RED,
    ORANGE,
    YELLOW,
    GREEN,
    BLUE,
    PURPLE,
    WHITE,
}

enum class SavedMarkerCreatedBy {
    USER,
    AI,
}

@ConsistentCopyVisibility
data class MarkerDraft private constructor(
    val name: String?,
    val notes: String?,
    val color: MarkerColor,
) {
    companion object {
        fun create(
            name: String? = null,
            notes: String? = null,
            color: MarkerColor = MarkerColor.YELLOW,
        ): MarkerDraft = MarkerDraft(
            name = name.normalizedMarkerText(),
            notes = notes.normalizedMarkerText(),
            color = color,
        )
    }
}

@ConsistentCopyVisibility
data class Marker private constructor(
    val systemId: Int,
    val persistence: MarkerPersistence,
    val name: String?,
    val notes: String?,
    val color: MarkerColor,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val createdBy: SavedMarkerCreatedBy?,
) {
    init {
        require(systemId > 0) { "Marker solar system ID must be positive" }
        when (persistence) {
            MarkerPersistence.TEMPORARY -> require(createdAt == null && updatedAt == null && createdBy == null) {
                "Temporary markers cannot have persistence metadata"
            }
            MarkerPersistence.SAVED -> require(createdAt != null && updatedAt != null && createdBy != null) {
                "Saved markers require persistence metadata"
            }
        }
    }

    fun toDraft(): MarkerDraft = MarkerDraft.create(name, notes, color)

    companion object {
        fun temporary(systemId: Int, draft: MarkerDraft = MarkerDraft.create()): Marker = Marker(
            systemId = systemId,
            persistence = MarkerPersistence.TEMPORARY,
            name = draft.name,
            notes = draft.notes,
            color = draft.color,
            createdAt = null,
            updatedAt = null,
            createdBy = null,
        )

        fun saved(
            systemId: Int,
            draft: MarkerDraft,
            createdAt: Instant,
            updatedAt: Instant,
            createdBy: SavedMarkerCreatedBy = SavedMarkerCreatedBy.USER,
        ): Marker = Marker(
            systemId = systemId,
            persistence = MarkerPersistence.SAVED,
            name = draft.name,
            notes = draft.notes,
            color = draft.color,
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdBy = createdBy,
        )
    }
}

private fun String?.normalizedMarkerText(): String? = this?.trim()?.takeIf(String::isNotEmpty)
