package dev.evestaticmapplanner.feature.api

import java.time.Instant
import java.util.Collections

/** One `is_capital_system == true` observation from the public ESI sovereignty feed. */
class AllianceCapitalRecordDto(
    val allianceId: Long,
    val capitalSystemId: Int,
) {
    init {
        require(allianceId > 0) { "Alliance ID must be positive" }
        require(capitalSystemId > 0) { "Capital solar system ID must be positive" }
    }
}

/**
 * Pack-owned Alliance Capital observation.
 *
 * More than one record for an Alliance ID is deliberately preserved. The Host turns that input into an
 * ambiguous state instead of silently selecting a capital. An absent Alliance ID means unknown/no capital.
 */
class AllianceCapitalSnapshotDto(
    records: List<AllianceCapitalRecordDto>,
    val observedAt: Instant? = null,
    val source: String,
    val freshness: SovereigntyFreshnessDto,
    val errorMessage: String? = null,
) {
    val records: List<AllianceCapitalRecordDto> = Collections.unmodifiableList(ArrayList(records))

    init {
        AllianceCapitalApiValidation.requiredText("Alliance Capital source", source, 128)
        AllianceCapitalApiValidation.optionalText("Alliance Capital error", errorMessage, 240)
        require(this.records.map { it.allianceId to it.capitalSystemId }.distinct().size == this.records.size) {
            "Alliance Capital snapshot must not contain duplicate Alliance/System pairs"
        }
        if (freshness == SovereigntyFreshnessDto.UNAVAILABLE) {
            require(this.records.isEmpty()) { "Unavailable Alliance Capital snapshots must not contain records" }
        }
    }
}

/** Pack-owned in-memory source. [snapshot] must never perform network I/O. */
interface AllianceCapitalProvider {
    fun snapshot(): AllianceCapitalSnapshotDto

    fun requestRefresh(): Boolean = false
}

/** Optional, additive Alliance Capital capability. */
interface AllianceCapitalCapability : FeatureCapability {
    fun register(provider: AllianceCapitalProvider): AllianceCapitalRegistration
}

interface AllianceCapitalRegistration : AutoCloseable {
    /** Notifies the Host that the Provider's in-memory snapshot changed. */
    fun requestRefresh()

    override fun close()
}

private object AllianceCapitalApiValidation {
    fun requiredText(label: String, value: String, maximumLength: Int) {
        require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
        require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
        require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
    }

    fun optionalText(label: String, value: String?, maximumLength: Int) {
        if (value != null) requiredText(label, value, maximumLength)
    }
}
