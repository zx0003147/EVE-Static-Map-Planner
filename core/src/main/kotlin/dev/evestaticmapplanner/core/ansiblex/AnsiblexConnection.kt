package dev.evestaticmapplanner.core.ansiblex

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
