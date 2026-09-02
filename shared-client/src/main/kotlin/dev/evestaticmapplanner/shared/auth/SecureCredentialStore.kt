package dev.evestaticmapplanner.shared.auth

import dev.evestaticmapplanner.shared.api.SharedServerUrl
import java.util.UUID

data class SharedCredentialKey(
    val serverOrigin: String,
    val workspaceId: String,
) {
    init {
        require(SharedServerUrl.parse(serverOrigin).origin == serverOrigin) { "Server origin must be canonical" }
        val parsed = UUID.fromString(workspaceId)
        require(parsed.toString() == workspaceId) { "Workspace ID must be a canonical UUID" }
    }
}

interface SecureCredentialStore {
    fun load(key: SharedCredentialKey): SecretValue?
    fun save(key: SharedCredentialKey, secret: SecretValue)
    fun delete(key: SharedCredentialKey)
}

class SecureCredentialException(message: String, cause: Throwable? = null) : Exception(message, cause)
