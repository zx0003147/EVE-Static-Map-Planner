package dev.evestaticmapplanner.feature.api

import java.time.Instant
import java.util.Collections

class AllianceReferenceSnapshot(
    val allianceId: Long,
    val name: String? = null,
    val ticker: String? = null,
) {
    init {
        require(allianceId > 0) { "Alliance ID must be positive" }
        AllianceDirectoryValidation.optionalText("Alliance name", name, 128)
        AllianceDirectoryValidation.optionalText("Alliance ticker", ticker, 32)
    }
}

class AllianceDirectoryProviderSnapshot(
    alliances: List<AllianceReferenceSnapshot>,
    val observedAt: Instant? = null,
    val freshnessSeconds: Long? = null,
    val errorMessage: String? = null,
) {
    val alliances: List<AllianceReferenceSnapshot> = Collections.unmodifiableList(ArrayList(alliances))

    init {
        require(this.alliances.map { it.allianceId }.distinct().size == this.alliances.size) {
            "Alliance directory provider snapshot must not contain duplicate Alliance IDs"
        }
        require(freshnessSeconds == null || freshnessSeconds >= 0) { "Freshness must not be negative" }
        AllianceDirectoryValidation.optionalText("Alliance directory error", errorMessage, 240)
    }
}

/** Pack-owned in-memory source. [snapshot] must never perform network I/O. */
interface AllianceDirectoryProvider {
    fun snapshot(): AllianceDirectoryProviderSnapshot

    fun requestRefresh(): Boolean = false
}

/** Optional, additive directory capability introduced by Feature API artifact 2.4.0. */
interface AllianceDirectoryCapability : FeatureCapability {
    fun register(provider: AllianceDirectoryProvider): AllianceDirectoryRegistration
}

interface AllianceDirectoryRegistration : AutoCloseable {
    fun requestRefresh()

    override fun close()
}

private object AllianceDirectoryValidation {
    fun optionalText(label: String, value: String?, maximumLength: Int) {
        if (value == null) return
        require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
        require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
        require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
    }
}
