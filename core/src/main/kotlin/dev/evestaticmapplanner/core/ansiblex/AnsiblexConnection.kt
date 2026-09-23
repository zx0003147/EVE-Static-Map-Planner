package dev.evestaticmapplanner.core.ansiblex

import java.time.Instant
import java.util.Locale

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
    val ownerAllianceId: String? = null,
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
        require(ownerAllianceId == null || ownerAllianceId == normalizeAllianceId(ownerAllianceId)) {
            "Ansiblex owner alliance ID must be canonical"
        }
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
    val ownerAllianceId: String? = null,
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
    fun status(connection: AnsiblexConnection, currentAllianceId: String?): AnsiblexAccessStatus {
        if (!connection.enabled) return AnsiblexAccessStatus.DISABLED
        val selected = normalizeAllianceId(currentAllianceId)
            ?: return AnsiblexAccessStatus.ALLIANCE_NOT_SELECTED
        val owner = connection.ownerAllianceId ?: return AnsiblexAccessStatus.OWNER_UNKNOWN
        return if (owner == selected) {
            AnsiblexAccessStatus.AVAILABLE
        } else {
            AnsiblexAccessStatus.ALLIANCE_MISMATCH
        }
    }

    fun isUsable(connection: AnsiblexConnection, currentAllianceId: String?): Boolean =
        status(connection, currentAllianceId) == AnsiblexAccessStatus.AVAILABLE

    fun usableConnections(
        connections: Iterable<AnsiblexConnection>,
        currentAllianceId: String?,
    ): List<AnsiblexConnection> = connections.filter { isUsable(it, currentAllianceId) }
}

fun normalizeAllianceId(value: String?): String? = value
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.uppercase(Locale.ROOT)
    ?.also { require(it.length <= MAX_ALLIANCE_ID_LENGTH) { "Alliance ID must not exceed $MAX_ALLIANCE_ID_LENGTH characters" } }

const val MAX_ALLIANCE_ID_LENGTH = 64
