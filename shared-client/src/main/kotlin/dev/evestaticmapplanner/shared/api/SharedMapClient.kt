package dev.evestaticmapplanner.shared.api

import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.model.SharedIdentity
import dev.evestaticmapplanner.shared.model.SharedInvite
import dev.evestaticmapplanner.shared.model.SharedMember
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerDraft
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedServerMeta
import dev.evestaticmapplanner.shared.model.SharedWorkspace
import dev.evestaticmapplanner.shared.protocol.ExchangedCredential
import java.util.UUID

interface SharedMapClient : AutoCloseable {
    suspend fun getMeta(server: SharedServerUrl): SharedServerMeta
    suspend fun exchangeInvite(
        server: SharedServerUrl,
        invite: SecretValue,
        deviceName: String,
    ): ExchangedCredential
    suspend fun getMe(server: SharedServerUrl, token: SecretValue): SharedIdentity
    suspend fun getWorkspaces(server: SharedServerUrl, token: SecretValue): List<SharedWorkspace>
    suspend fun getMarkerSnapshot(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
    ): SharedMarkerSnapshot
    suspend fun createSharedMarker(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        systemId: Int,
        draft: SharedMarkerDraft,
        idempotencyKey: UUID,
    ): SharedMarker = unsupported()
    suspend fun updateSharedMarker(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        markerId: String,
        expectedVersion: Long,
        draft: SharedMarkerDraft,
        idempotencyKey: UUID,
    ): SharedMarker = unsupported()
    suspend fun deleteSharedMarker(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        markerId: String,
        expectedVersion: Long,
        idempotencyKey: UUID,
    ): Unit = unsupported()
    suspend fun getMembers(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
    ): List<SharedMember> = unsupported()
    suspend fun createMember(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        displayName: String,
        role: dev.evestaticmapplanner.shared.model.SharedWorkspaceRole,
        idempotencyKey: UUID,
    ): SharedMember = unsupported()
    suspend fun updateMember(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        memberId: String,
        expectedVersion: Long,
        displayName: String? = null,
        role: dev.evestaticmapplanner.shared.model.SharedWorkspaceRole? = null,
        idempotencyKey: UUID,
    ): SharedMember = unsupported()
    suspend fun deleteMember(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        memberId: String,
        expectedVersion: Long,
        idempotencyKey: UUID,
    ): Unit = unsupported()
    suspend fun createInvite(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        memberId: String,
        expiresInHours: Long,
        idempotencyKey: UUID,
    ): Pair<SharedInvite, SecretValue> = unsupported()
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("Shared Map operation is not implemented")
