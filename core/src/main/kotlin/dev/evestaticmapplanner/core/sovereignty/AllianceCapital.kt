package dev.evestaticmapplanner.core.sovereignty

import dev.evestaticmapplanner.core.identity.CurrentIdentityContext

enum class AllianceCapitalStatus {
    AVAILABLE,
    UNKNOWN,
    AMBIGUOUS,
    UNAVAILABLE,
}

/** Stable-ID Alliance Capital resolution with snapshot provenance attached. */
data class AllianceCapitalRecord(
    val allianceId: Long,
    val capitalSystemId: Int? = null,
    val status: AllianceCapitalStatus,
    val conflictingSystemIds: List<Int> = emptyList(),
    val observedAtEpochMillis: Long? = null,
    val source: String? = null,
    val freshness: SovereigntyFreshness,
    val diagnostic: String? = null,
) {
    init {
        require(allianceId > 0) { "Alliance ID must be positive" }
        require(capitalSystemId == null || capitalSystemId > 0) { "Capital solar system ID must be positive" }
        require(observedAtEpochMillis == null || observedAtEpochMillis >= 0) {
            "Alliance Capital observation time must not be negative"
        }
        require(conflictingSystemIds.all { it > 0 }) { "Conflicting capital solar system IDs must be positive" }
        require(conflictingSystemIds == conflictingSystemIds.distinct().sorted()) {
            "Conflicting capital solar system IDs must be unique and sorted"
        }
        requireAllianceCapitalText("Alliance Capital source", source, 128)
        requireAllianceCapitalText("Alliance Capital diagnostic", diagnostic, 240)
        when (status) {
            AllianceCapitalStatus.AVAILABLE -> require(capitalSystemId != null && conflictingSystemIds.isEmpty()) {
                "Available Alliance Capital records require exactly one capital"
            }
            AllianceCapitalStatus.AMBIGUOUS -> require(capitalSystemId == null && conflictingSystemIds.size > 1) {
                "Ambiguous Alliance Capital records require multiple conflicting systems"
            }
            AllianceCapitalStatus.UNKNOWN, AllianceCapitalStatus.UNAVAILABLE -> require(
                capitalSystemId == null && conflictingSystemIds.isEmpty(),
            ) { "Unknown or unavailable Alliance Capital records cannot assert a capital" }
        }
        if (freshness == SovereigntyFreshness.UNAVAILABLE) {
            require(status == AllianceCapitalStatus.UNAVAILABLE) {
                "Unavailable Alliance Capital freshness cannot assert a resolution"
            }
        } else {
            require(source != null) { "Fresh or stale Alliance Capital records require a source" }
        }
    }
}

/** One internally consistent Alliance Capital provider observation, keyed only by Alliance ID. */
data class AllianceCapitalSnapshot(
    val recordsByAllianceId: Map<Long, AllianceCapitalRecord> = emptyMap(),
    val observedAtEpochMillis: Long? = null,
    val source: String? = null,
    val freshness: SovereigntyFreshness = SovereigntyFreshness.UNAVAILABLE,
    val errorMessage: String? = null,
) {
    init {
        require(observedAtEpochMillis == null || observedAtEpochMillis >= 0) {
            "Alliance Capital snapshot observation time must not be negative"
        }
        requireAllianceCapitalText("Alliance Capital source", source, 128)
        requireAllianceCapitalText("Alliance Capital error", errorMessage, 240)
        require(recordsByAllianceId.all { (allianceId, record) -> allianceId == record.allianceId }) {
            "Alliance Capital snapshot keys must equal record Alliance IDs"
        }
        require(recordsByAllianceId.values.all { record ->
            record.source == source && record.freshness == freshness &&
                record.observedAtEpochMillis == observedAtEpochMillis
        }) { "Alliance Capital record metadata must match its containing snapshot" }
        if (freshness == SovereigntyFreshness.UNAVAILABLE) {
            require(recordsByAllianceId.isEmpty()) {
                "Unavailable Alliance Capital snapshots must not contain records"
            }
        } else {
            require(source != null) { "Fresh or stale Alliance Capital snapshots require a source" }
        }
    }

    fun getCapital(allianceId: Long): AllianceCapitalRecord {
        require(allianceId > 0) { "Alliance ID must be positive" }
        recordsByAllianceId[allianceId]?.let { return it }
        return if (freshness == SovereigntyFreshness.UNAVAILABLE) {
            AllianceCapitalRecord(
                allianceId = allianceId,
                status = AllianceCapitalStatus.UNAVAILABLE,
                freshness = SovereigntyFreshness.UNAVAILABLE,
                diagnostic = errorMessage ?: "Alliance Capital data is unavailable",
            )
        } else {
            AllianceCapitalRecord(
                allianceId = allianceId,
                status = AllianceCapitalStatus.UNKNOWN,
                observedAtEpochMillis = observedAtEpochMillis,
                source = source,
                freshness = freshness,
                diagnostic = "No capital record was observed for Alliance ID $allianceId",
            )
        }
    }

    companion object {
        fun unavailable(errorMessage: String? = null) = AllianceCapitalSnapshot(errorMessage = errorMessage)
    }
}

data class CurrentAllianceCapitalState(
    val allianceId: Long? = null,
    val allianceName: String? = null,
    val allianceTicker: String? = null,
    val capital: AllianceCapitalRecord? = null,
) {
    init {
        require(allianceId == null || allianceId > 0) { "Alliance ID must be positive" }
        require((allianceId == null) == (capital == null)) {
            "Current Alliance Capital state must resolve exactly when an Alliance ID is selected"
        }
        require(capital == null || capital.allianceId == allianceId) {
            "Current Alliance Capital record must match the selected Alliance ID"
        }
    }
}

object CurrentAllianceCapitalResolver {
    fun resolve(
        identity: CurrentIdentityContext?,
        snapshot: AllianceCapitalSnapshot,
    ): CurrentAllianceCapitalState {
        val allianceId = identity?.allianceId ?: return CurrentAllianceCapitalState()
        return CurrentAllianceCapitalState(
            allianceId = allianceId,
            allianceName = identity.allianceName,
            allianceTicker = identity.allianceTicker,
            capital = snapshot.getCapital(allianceId),
        )
    }
}

private fun requireAllianceCapitalText(label: String, value: String?, maximumLength: Int) {
    if (value == null) return
    require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
    require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
}
