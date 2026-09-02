package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.preferences.SharedMapPreferences
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.api.SharedMapException
import dev.evestaticmapplanner.shared.model.SharedMapConfiguration
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedMarkerDraft
import dev.evestaticmapplanner.shared.model.SharedMarkerValidationException
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.shared.sync.SharedMapSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class SharedMapViewModel(
    private val session: SharedMapSession,
    private val scope: CoroutineScope,
) : AutoCloseable {
    val state: StateFlow<SharedMapState> = session.state

    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    private val _markerMutation = MutableStateFlow(SharedMarkerMutationUiState())
    val markerMutation: StateFlow<SharedMarkerMutationUiState> = _markerMutation.asStateFlow()

    private val _admin = MutableStateFlow(SharedAdminUiState())
    val admin: StateFlow<SharedAdminUiState> = _admin.asStateFlow()

    private var operationSequence = 0L
    private var observedWorkspaceId: String? = null

    init {
        scope.launch {
            state.collectLatest { current ->
                val workspaceChanged = current.selectedWorkspaceId != observedWorkspaceId
                if (workspaceChanged) {
                    observedWorkspaceId = current.selectedWorkspaceId
                    clearMarkerMutationFeedback()
                    clearAdminState(current.selectedWorkspaceId)
                } else if (current.identity?.workspace?.role != SharedWorkspaceRole.ADMIN) {
                    clearAdminState(current.selectedWorkspaceId)
                }
            }
        }
    }

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

    fun createSharedMarker(systemId: Int, draft: SharedMarkerDraft): Long? = launchMarkerMutation(
        kind = SharedMarkerMutationKind.CREATE,
        targetSystemId = systemId,
    ) { operationId ->
        val marker = session.createSharedMarker(systemId, draft)
        SharedMarkerMutationCompletion(operationId, SharedMarkerMutationKind.CREATE, marker = marker)
    }

    fun updateSharedMarker(markerId: String, expectedVersion: Long, draft: SharedMarkerDraft): Long? =
        launchMarkerMutation(
            kind = SharedMarkerMutationKind.UPDATE,
            targetMarkerId = markerId,
        ) { operationId ->
            val marker = session.updateSharedMarker(markerId, expectedVersion, draft)
            SharedMarkerMutationCompletion(operationId, SharedMarkerMutationKind.UPDATE, marker = marker)
        }

    fun deleteSharedMarker(markerId: String, expectedVersion: Long): Long? = launchMarkerMutation(
        kind = SharedMarkerMutationKind.DELETE,
        targetMarkerId = markerId,
    ) { operationId ->
        session.deleteSharedMarker(markerId, expectedVersion)
        SharedMarkerMutationCompletion(
            operationId,
            SharedMarkerMutationKind.DELETE,
            deletedMarkerId = markerId,
        )
    }

    fun clearMarkerMutationFeedback() {
        if (!_markerMutation.value.busy) _markerMutation.value = SharedMarkerMutationUiState()
    }

    fun loadMembers() {
        val workspaceId = state.value.selectedWorkspaceId ?: return
        if (state.value.identity?.workspace?.role != SharedWorkspaceRole.ADMIN || _admin.value.loading) return
        _admin.value = _admin.value.copy(workspaceId = workspaceId, loading = true, error = null)
        scope.launch {
            try {
                val members = session.getMembers()
                if (state.value.selectedWorkspaceId == workspaceId) {
                    _admin.value = _admin.value.copy(members = members, loading = false, error = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.value.selectedWorkspaceId == workspaceId) {
                    _admin.value = _admin.value.copy(loading = false, error = error.toSharedMapError())
                }
            }
        }
    }

    fun createMember(displayName: String, role: SharedWorkspaceRole): Boolean = launchAdminMutation(null) {
        val member = session.createMember(displayName, role)
        _admin.value = _admin.value.copy(
            members = (_admin.value.members.filterNot { it.memberId == member.memberId } + member)
                .sortedBy { it.displayName.lowercase() },
        )
    }

    fun changeMemberRole(memberId: String, expectedVersion: Long, role: SharedWorkspaceRole): Boolean =
        launchAdminMutation(memberId) {
            val member = session.updateMember(memberId, expectedVersion, role = role)
            replaceMember(member)
            if (state.value.identity?.workspace?.memberId == memberId) session.refreshNow()
        }

    fun removeMember(memberId: String, expectedVersion: Long): Boolean = launchAdminMutation(memberId) {
        session.deleteMember(memberId, expectedVersion)
        _admin.value = _admin.value.copy(members = _admin.value.members.filterNot { it.memberId == memberId })
        if (state.value.identity?.workspace?.memberId == memberId) session.refreshNow()
    }

    fun createInvite(memberId: String, expiresInHours: Long = 72): Boolean = launchAdminMutation(memberId) {
        val (invite, secret) = session.createInvite(memberId, expiresInHours)
        _admin.value.oneTimeInvite?.close()
        _admin.value = _admin.value.copy(oneTimeInvite = OneTimeSharedInvite(invite, secret))
    }

    fun clearAdminError() {
        _admin.value = _admin.value.copy(error = null)
    }

    fun clearOneTimeInvite() {
        _admin.value.oneTimeInvite?.close()
        _admin.value = _admin.value.copy(oneTimeInvite = null)
    }

    suspend fun disconnectForPreferencesReset(): Result<Unit> = runCatching { session.disconnect() }

    fun clearOperationError() {
        _operationError.value = null
    }

    override fun close() {
        _admin.value.oneTimeInvite?.close()
        _admin.value = SharedAdminUiState()
        session.close()
    }

    private fun launchMarkerMutation(
        kind: SharedMarkerMutationKind,
        targetMarkerId: String? = null,
        targetSystemId: Int? = null,
        operation: suspend (Long) -> SharedMarkerMutationCompletion,
    ): Long? {
        if (_markerMutation.value.busy) return null
        val operationId = ++operationSequence
        _markerMutation.value = SharedMarkerMutationUiState(
            operationId = operationId,
            kind = kind,
            targetMarkerId = targetMarkerId,
            targetSystemId = targetSystemId,
            busy = true,
        )
        scope.launch {
            try {
                val completion = operation(operationId)
                _markerMutation.value = _markerMutation.value.copy(busy = false, completion = completion)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _markerMutation.value = _markerMutation.value.copy(
                    busy = false,
                    error = error.toSharedMapError(),
                )
            }
        }
        return operationId
    }

    private fun launchAdminMutation(memberId: String?, operation: suspend () -> Unit): Boolean {
        if (_admin.value.busyMemberId != null || _admin.value.loading) return false
        _admin.value = _admin.value.copy(busyMemberId = memberId ?: ADMIN_GLOBAL_BUSY, error = null)
        scope.launch {
            try {
                operation()
                _admin.value = _admin.value.copy(busyMemberId = null, error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _admin.value = _admin.value.copy(busyMemberId = null, error = error.toSharedMapError())
            }
        }
        return true
    }

    private fun replaceMember(member: dev.evestaticmapplanner.shared.model.SharedMember) {
        _admin.value = _admin.value.copy(
            members = _admin.value.members.map { if (it.memberId == member.memberId) member else it },
        )
    }

    private fun clearAdminState(workspaceId: String?) {
        _admin.value.oneTimeInvite?.close()
        _admin.value = SharedAdminUiState(workspaceId = workspaceId)
    }

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

internal fun Exception.toSharedMapError(): SharedMapError = when (this) {
    is SharedMapException -> error
    is SharedMarkerValidationException -> SharedMapError.InvalidArgument(message)
    else -> SharedMapError.InvalidResponse("The Shared Map operation could not be completed.")
}

private const val ADMIN_GLOBAL_BUSY = "__global__"

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
