package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.preferences.SharedMapPreferences
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.model.SharedMapConfiguration
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.sync.SharedMapSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class SharedMapViewModel(
    private val session: SharedMapSession,
    private val scope: CoroutineScope,
) : AutoCloseable {
    val state: StateFlow<SharedMapState> = session.state

    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    fun restore(preferences: SharedMapPreferences) {
        launchOperation { session.restore(preferences.toConfiguration()) }
    }

    fun connect(serverUrl: String, invite: SecretValue, deviceName: String) {
        val ownedInvite = invite.copy()
        val job = launchOperation { ownedInvite.use { session.connect(serverUrl, it, deviceName) } }
        job.invokeOnCompletion { ownedInvite.close() }
    }

    fun switchWorkspace(workspaceId: String) {
        launchOperation { session.switchWorkspace(workspaceId) }
    }

    fun refreshNow() {
        launchOperation { session.refreshNow() }
    }

    fun disconnect() {
        launchOperation { session.disconnect() }
    }

    suspend fun disconnectForPreferencesReset(): Result<Unit> = runCatching { session.disconnect() }

    fun clearOperationError() {
        _operationError.value = null
    }

    override fun close() = session.close()

    private fun launchOperation(block: suspend () -> Unit) = scope.launch {
        _operationError.value = null
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _operationError.value = "The Shared Map operation could not be completed."
        }
    }
}

internal fun SharedMapPreferences.toConfiguration(): SharedMapConfiguration = SharedMapConfiguration(
    serverUrl = serverUrl,
    selectedWorkspaceId = selectedWorkspaceId,
    deviceName = deviceName,
)

internal fun SharedMapConfiguration.toPreferences(): SharedMapPreferences = SharedMapPreferences(
    serverUrl = serverUrl,
    selectedWorkspaceId = selectedWorkspaceId,
    deviceName = deviceName,
)
