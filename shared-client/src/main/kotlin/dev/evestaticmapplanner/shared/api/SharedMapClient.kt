package dev.evestaticmapplanner.shared.api

import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.model.SharedIdentity
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedServerMeta
import dev.evestaticmapplanner.shared.model.SharedWorkspace
import dev.evestaticmapplanner.shared.protocol.ExchangedCredential

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
}
