package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.model.SharedInvite
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMember

internal enum class SharedMarkerMutationKind { CREATE, UPDATE, DELETE }

internal data class SharedMarkerMutationCompletion(
    val operationId: Long,
    val kind: SharedMarkerMutationKind,
    val marker: SharedMarker? = null,
    val deletedMarkerId: String? = null,
)

internal data class SharedMarkerMutationUiState(
    val operationId: Long? = null,
    val kind: SharedMarkerMutationKind? = null,
    val targetMarkerId: String? = null,
    val targetSystemId: Int? = null,
    val busy: Boolean = false,
    val completion: SharedMarkerMutationCompletion? = null,
    val error: SharedMapError? = null,
)

internal class OneTimeSharedInvite(
    val invite: SharedInvite,
    private val secret: SecretValue,
) : AutoCloseable {
    fun <T> useSecret(block: (String) -> T): T = secret.useString(block)
    override fun close() = secret.close()
    override fun toString(): String = "OneTimeSharedInvite(inviteId=${invite.inviteId}, secret=${SecretValue.REDACTED})"
}

internal data class SharedAdminUiState(
    val workspaceId: String? = null,
    val members: List<SharedMember> = emptyList(),
    val loading: Boolean = false,
    val busyMemberId: String? = null,
    val error: SharedMapError? = null,
    val oneTimeInvite: OneTimeSharedInvite? = null,
)
