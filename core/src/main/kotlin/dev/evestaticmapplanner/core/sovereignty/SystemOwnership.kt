package dev.evestaticmapplanner.core.sovereignty

enum class SystemOwnerKind {
    ALLIANCE,
    CORPORATION,
    FACTION,
    UNCLAIMED,
    UNKNOWN,
}

enum class SovereigntyStatus {
    CLAIMED,
    UNCLAIMED,
    UNKNOWN,
}

enum class SovereigntyFreshness {
    AVAILABLE,
    STALE,
    UNAVAILABLE,
}

/**
 * Stable ownership semantics for one solar system.
 *
 * IDs define entity identity. Names are optional display metadata and must never be used for joins.
 */
data class SystemOwnership(
    val systemId: Int,
    val ownerKind: SystemOwnerKind,
    val allianceId: Long? = null,
    val allianceName: String? = null,
    val corporationId: Long? = null,
    val corporationName: String? = null,
    val factionId: Long? = null,
    val factionName: String? = null,
    val sovereigntyStatus: SovereigntyStatus = SovereigntyStatus.UNKNOWN,
    val observedAtEpochMillis: Long? = null,
    val source: String,
    val freshness: SovereigntyFreshness,
) {
    init {
        require(systemId > 0) { "Solar system ID must be positive" }
        require(allianceId == null || allianceId > 0) { "Alliance ID must be positive" }
        require(corporationId == null || corporationId > 0) { "Corporation ID must be positive" }
        require(factionId == null || factionId > 0) { "Faction ID must be positive" }
        require(observedAtEpochMillis == null || observedAtEpochMillis >= 0) {
            "Ownership observation time must not be negative"
        }
        requireCanonicalSource(source)
        requireDisplayText("Alliance name", allianceName)
        requireDisplayText("Corporation name", corporationName)
        requireDisplayText("Faction name", factionName)
        when (ownerKind) {
            SystemOwnerKind.ALLIANCE -> require(allianceId != null) {
                "Alliance ownership requires a stable Alliance ID"
            }
            SystemOwnerKind.CORPORATION -> require(corporationId != null) {
                "Corporation ownership requires a stable Corporation ID"
            }
            SystemOwnerKind.FACTION -> require(factionId != null) {
                "Faction ownership requires a stable Faction ID"
            }
            SystemOwnerKind.UNCLAIMED -> require(
                allianceId == null && corporationId == null && factionId == null,
            ) { "Unclaimed ownership must not carry an owner ID" }
            SystemOwnerKind.UNKNOWN -> Unit
        }
        if (freshness == SovereigntyFreshness.UNAVAILABLE) {
            require(ownerKind == SystemOwnerKind.UNKNOWN) {
                "Unavailable ownership cannot assert an owner"
            }
        }
    }
}

/** One internally consistent provider observation, keyed only by canonical solar-system ID. */
data class SovereigntySnapshot(
    val systemsById: Map<Int, SystemOwnership> = emptyMap(),
    val observedAtEpochMillis: Long? = null,
    val source: String? = null,
    val freshness: SovereigntyFreshness = SovereigntyFreshness.UNAVAILABLE,
    val errorMessage: String? = null,
) {
    init {
        require(observedAtEpochMillis == null || observedAtEpochMillis >= 0) {
            "Sovereignty snapshot observation time must not be negative"
        }
        source?.let(::requireCanonicalSource)
        requireDisplayText("Sovereignty error", errorMessage, 240)
        require(systemsById.all { (systemId, ownership) -> systemId == ownership.systemId }) {
            "Sovereignty snapshot keys must equal ownership system IDs"
        }
        require(systemsById.values.all { ownership ->
            ownership.source == source && ownership.freshness == freshness &&
                ownership.observedAtEpochMillis == observedAtEpochMillis
        }) { "Sovereignty ownership metadata must match its containing snapshot" }
        if (freshness == SovereigntyFreshness.UNAVAILABLE) {
            require(systemsById.isEmpty()) { "Unavailable sovereignty snapshots must not contain ownership data" }
        } else {
            require(source != null) { "Available sovereignty snapshots require a source" }
        }
    }

    fun getOwnership(systemId: Int): SystemOwnership? = systemsById[systemId]

    companion object {
        fun unavailable(errorMessage: String? = null) = SovereigntySnapshot(errorMessage = errorMessage)
    }
}

private fun requireCanonicalSource(value: String) {
    require(value.isNotBlank() && value == value.trim()) { "Sovereignty source must be canonical" }
    require(value.length <= 128) { "Sovereignty source must not exceed 128 characters" }
    require(value.none(Char::isISOControl)) { "Sovereignty source must not contain control characters" }
}

private fun requireDisplayText(label: String, value: String?, maximumLength: Int = 128) {
    if (value == null) return
    require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
    require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
}
