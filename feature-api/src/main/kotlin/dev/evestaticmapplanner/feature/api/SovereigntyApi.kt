package dev.evestaticmapplanner.feature.api

import java.time.Instant
import java.util.Collections

enum class SovereigntyOwnerKindDto {
    ALLIANCE,
    CORPORATION,
    FACTION,
    UNCLAIMED,
    UNKNOWN,
}

enum class SovereigntyStatusDto {
    CLAIMED,
    UNCLAIMED,
    UNKNOWN,
}

enum class SovereigntyFreshnessDto {
    AVAILABLE,
    STALE,
    UNAVAILABLE,
}

class SystemOwnershipDto(
    val systemId: Int,
    val ownerKind: SovereigntyOwnerKindDto,
    val allianceId: Long? = null,
    val allianceName: String? = null,
    val corporationId: Long? = null,
    val corporationName: String? = null,
    val factionId: Long? = null,
    val factionName: String? = null,
    val sovereigntyStatus: SovereigntyStatusDto = SovereigntyStatusDto.UNKNOWN,
) {
    init {
        require(systemId > 0) { "Solar system ID must be positive" }
        require(allianceId == null || allianceId > 0) { "Alliance ID must be positive" }
        require(corporationId == null || corporationId > 0) { "Corporation ID must be positive" }
        require(factionId == null || factionId > 0) { "Faction ID must be positive" }
        SovereigntyApiValidation.optionalText("Alliance name", allianceName, 128)
        SovereigntyApiValidation.optionalText("Corporation name", corporationName, 128)
        SovereigntyApiValidation.optionalText("Faction name", factionName, 128)
        when (ownerKind) {
            SovereigntyOwnerKindDto.ALLIANCE -> require(allianceId != null) {
                "Alliance ownership requires a stable Alliance ID"
            }
            SovereigntyOwnerKindDto.CORPORATION -> require(corporationId != null) {
                "Corporation ownership requires a stable Corporation ID"
            }
            SovereigntyOwnerKindDto.FACTION -> require(factionId != null) {
                "Faction ownership requires a stable Faction ID"
            }
            SovereigntyOwnerKindDto.UNCLAIMED -> require(
                allianceId == null && corporationId == null && factionId == null,
            ) { "Unclaimed ownership must not carry an owner ID" }
            SovereigntyOwnerKindDto.UNKNOWN -> Unit
        }
    }
}

class SovereigntySnapshotDto(
    systems: List<SystemOwnershipDto>,
    val observedAt: Instant? = null,
    val source: String,
    val freshness: SovereigntyFreshnessDto,
    val errorMessage: String? = null,
) {
    val systems: List<SystemOwnershipDto> = Collections.unmodifiableList(ArrayList(systems))

    init {
        SovereigntyApiValidation.requiredText("Sovereignty source", source, 128)
        SovereigntyApiValidation.optionalText("Sovereignty error", errorMessage, 240)
        require(this.systems.map { it.systemId }.distinct().size == this.systems.size) {
            "Sovereignty snapshot must not contain duplicate solar system IDs"
        }
        if (freshness == SovereigntyFreshnessDto.UNAVAILABLE) {
            require(this.systems.isEmpty()) { "Unavailable sovereignty snapshots must not contain ownership data" }
        }
    }
}

/** Pack-owned in-memory source. [snapshot] must never perform network I/O. */
interface SovereigntyProvider {
    fun snapshot(): SovereigntySnapshotDto

    fun requestRefresh(): Boolean = false
}

/** Optional, additive system-ownership capability introduced by Feature API artifact 2.5.0. */
interface SovereigntyCapability : FeatureCapability {
    fun register(provider: SovereigntyProvider): SovereigntyRegistration
}

interface SovereigntyRegistration : AutoCloseable {
    /** Notifies the Host that the Provider's in-memory snapshot changed. */
    fun requestRefresh()

    override fun close()
}

private object SovereigntyApiValidation {
    fun requiredText(label: String, value: String, maximumLength: Int) {
        require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
        require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
        require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
    }

    fun optionalText(label: String, value: String?, maximumLength: Int) {
        if (value != null) requiredText(label, value, maximumLength)
    }
}
