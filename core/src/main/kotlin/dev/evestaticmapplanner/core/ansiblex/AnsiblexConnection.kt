package dev.evestaticmapplanner.core.ansiblex

import dev.evestaticmapplanner.core.identity.CurrentIdentityContext
import java.time.Instant

enum class AnsiblexDirection {
    BIDIRECTIONAL,
    FIRST_TO_SECOND,
    SECOND_TO_FIRST,
}

enum class AnsiblexSource {
    IMPORT,
    MANUAL,
}

data class AnsiblexConnection(
    val id: String,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val direction: AnsiblexDirection,
    val displayName: String?,
    val notes: String?,
    val source: AnsiblexSource,
    val sourceBatchId: String?,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val ownerAllianceId: Long? = null,
    val ownerAllianceName: String? = null,
    val ownerAllianceTicker: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Ansiblex connection ID must not be blank" }
        require(firstSystemId > 0 && secondSystemId > 0) { "Solar system IDs must be positive" }
        require(firstSystemId < secondSystemId) {
            "Ansiblex endpoints must be canonical, distinct, and ordered"
        }
        require(source != AnsiblexSource.IMPORT || !sourceBatchId.isNullOrBlank()) {
            "Imported Ansiblex connections must reference an import batch"
        }
        require(source != AnsiblexSource.MANUAL || sourceBatchId == null) {
            "Manual Ansiblex connections cannot reference an import batch"
        }
        require(ownerAllianceId == null || ownerAllianceId > 0) { "Ansiblex owner alliance ID must be positive" }
        requireAllianceDisplayText("Ansiblex owner alliance name", ownerAllianceName, MAX_ALLIANCE_NAME_LENGTH)
        requireAllianceDisplayText("Ansiblex owner alliance ticker", ownerAllianceTicker, MAX_ALLIANCE_TICKER_LENGTH)
    }

    fun logicalFromSystemId(): Int = when (direction) {
        AnsiblexDirection.SECOND_TO_FIRST -> secondSystemId
        else -> firstSystemId
    }

    fun logicalToSystemId(): Int = when (direction) {
        AnsiblexDirection.SECOND_TO_FIRST -> firstSystemId
        else -> secondSystemId
    }
}

data class AnsiblexDraft(
    val fromSystemId: Int,
    val toSystemId: Int,
    val bidirectional: Boolean = true,
    val displayName: String? = null,
    val notes: String? = null,
    val enabled: Boolean = true,
    val ownerAllianceId: Long? = null,
    val ownerAllianceName: String? = null,
    val ownerAllianceTicker: String? = null,
) {
    init {
        require(fromSystemId > 0 && toSystemId > 0) { "Solar system IDs must be positive" }
        require(fromSystemId != toSystemId) { "Ansiblex connection cannot be a self-loop" }
    }

    val firstSystemId: Int get() = minOf(fromSystemId, toSystemId)
    val secondSystemId: Int get() = maxOf(fromSystemId, toSystemId)
    val direction: AnsiblexDirection get() = when {
        bidirectional -> AnsiblexDirection.BIDIRECTIONAL
        fromSystemId == firstSystemId -> AnsiblexDirection.FIRST_TO_SECOND
        else -> AnsiblexDirection.SECOND_TO_FIRST
    }
}

enum class AnsiblexAccessStatus {
    AVAILABLE,
    DISABLED,
    ALLIANCE_NOT_SELECTED,
    OWNER_UNKNOWN,
    ALLIANCE_MISMATCH,
}

object AnsiblexAccessPolicy {
    fun status(connection: AnsiblexConnection, identity: CurrentIdentityContext?): AnsiblexAccessStatus {
        if (!connection.enabled) return AnsiblexAccessStatus.DISABLED
        val owner = connection.ownerAllianceId ?: return AnsiblexAccessStatus.OWNER_UNKNOWN
        val selected = identity?.allianceId ?: return AnsiblexAccessStatus.ALLIANCE_NOT_SELECTED
        return if (owner == selected) {
            AnsiblexAccessStatus.AVAILABLE
        } else {
            AnsiblexAccessStatus.ALLIANCE_MISMATCH
        }
    }

    fun isUsable(connection: AnsiblexConnection, identity: CurrentIdentityContext?): Boolean =
        status(connection, identity) == AnsiblexAccessStatus.AVAILABLE

    fun usableConnections(
        connections: Iterable<AnsiblexConnection>,
        identity: CurrentIdentityContext?,
    ): List<AnsiblexConnection> = connections.filter { isUsable(it, identity) }
}

fun normalizeAllianceDisplayName(value: String?): String? = normalizeAllianceDisplayText(
    value,
    "Alliance name",
    MAX_ALLIANCE_NAME_LENGTH,
)

fun normalizeAllianceTicker(value: String?): String? = normalizeAllianceDisplayText(
    value,
    "Alliance ticker",
    MAX_ALLIANCE_TICKER_LENGTH,
)

private fun normalizeAllianceDisplayText(value: String?, label: String, maximumLength: Int): String? = value
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.also { require(it.length <= maximumLength) { "$label must not exceed $maximumLength characters" } }

private fun requireAllianceDisplayText(label: String, value: String?, maximumLength: Int) {
    require(value == normalizeAllianceDisplayText(value, label, maximumLength)) { "$label must be canonical" }
}

const val MAX_ALLIANCE_NAME_LENGTH = 128
const val MAX_ALLIANCE_TICKER_LENGTH = 32
