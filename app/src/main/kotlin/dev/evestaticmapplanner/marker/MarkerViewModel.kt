package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.marker.application.SavedMarkerService
import dev.evestaticmapplanner.marker.application.SavedMarkerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MarkerViewModel(
    private val savedMarkerService: SavedMarkerService,
    private val scope: CoroutineScope,
) {
    private val stateLock = Any()
    private var transientState = MarkerTransientState()
    private val mutableState = MutableStateFlow(buildUiState(savedMarkerService.state.value, transientState))
    val state: StateFlow<MarkerUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            savedMarkerService.state.collect { savedState ->
                synchronized(stateLock) {
                    val externallyClaimedSystemIds = savedState.markersBySystemId.keys - transientState.busySystemIds
                    transientState = transientState.copy(
                        temporaryMarkersBySystemId = transientState.temporaryMarkersBySystemId -
                            externallyClaimedSystemIds,
                    )
                    mutableState.value = buildUiState(savedState, transientState)
                }
            }
        }
    }

    fun addTemporary(systemId: Int): Boolean = synchronized(stateLock) {
        val current = currentState()
        creationError(current, systemId)?.let {
            updateTransient { state -> state.copy(operationError = it) }
            return@synchronized false
        }
        val marker = runCatching { Marker.temporary(systemId) }.getOrElse { error ->
            updateTransient { state ->
                state.copy(operationError = error.message ?: "Unable to create temporary marker")
            }
            return@synchronized false
        }
        updateTransient { state ->
            state.copy(
                temporaryMarkersBySystemId = state.temporaryMarkersBySystemId + (systemId to marker),
                operationError = null,
            )
        }
        true
    }

    fun updateTemporary(systemId: Int, draft: MarkerDraft): Boolean = synchronized(stateLock) {
        val current = currentState()
        val marker = current.markersBySystemId[systemId]
        val error = when {
            systemId in current.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
            marker == null -> "Marker no longer exists for solar system $systemId"
            marker.persistence != MarkerPersistence.TEMPORARY ->
                "Saved marker cannot be updated as temporary for solar system $systemId"
            else -> null
        }
        if (error != null) {
            updateTransient { it.copy(operationError = error) }
            return@synchronized false
        }
        updateTransient { state ->
            state.copy(
                temporaryMarkersBySystemId = state.temporaryMarkersBySystemId +
                    (systemId to Marker.temporary(systemId, draft)),
                operationError = null,
            )
        }
        true
    }

    fun removeTemporary(systemId: Int): Boolean = synchronized(stateLock) {
        val current = currentState()
        val marker = current.markersBySystemId[systemId]
        val error = when {
            systemId in current.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
            marker == null -> "Marker no longer exists for solar system $systemId"
            marker.persistence != MarkerPersistence.TEMPORARY ->
                "Saved marker cannot be removed as temporary for solar system $systemId"
            else -> null
        }
        if (error != null) {
            updateTransient { it.copy(operationError = error) }
            return@synchronized false
        }
        updateTransient { state ->
            state.copy(
                temporaryMarkersBySystemId = state.temporaryMarkersBySystemId - systemId,
                operationError = null,
            )
        }
        true
    }

    fun clearTemporaryMarkers(): Boolean = synchronized(stateLock) {
        val current = currentState()
        val busyTemporary = current.busySystemIds.any(transientState.temporaryMarkersBySystemId::containsKey)
        if (busyTemporary) {
            updateTransient { it.copy(operationError = "A temporary marker operation is still in progress") }
            return@synchronized false
        }
        updateTransient { it.copy(temporaryMarkersBySystemId = emptyMap(), operationError = null) }
        true
    }

    fun createSaved(
        systemId: Int,
        draft: MarkerDraft,
        initialTags: List<SavedMarkerChildType> = emptyList(),
    ): Boolean {
        synchronized(stateLock) {
            val current = currentState()
            creationError(current, systemId)?.let {
                updateTransient { state -> state.copy(operationError = it) }
                return false
            }
            reserve(systemId)
        }
        scope.launch {
            runCatching { savedMarkerService.create(systemId, draft, initialTags) }
                .onSuccess { completeSavedMutation(systemId) }
                .onFailure { error -> failSavedMutation(systemId, error, "Unable to create saved marker") }
        }
        return true
    }

    fun updateSaved(systemId: Int, draft: MarkerDraft): Boolean {
        if (!reserveExistingSaved(systemId)) return false
        scope.launch {
            runCatching { savedMarkerService.update(systemId, draft) }
                .onSuccess { completeSavedMutation(systemId) }
                .onFailure { error -> failSavedMutation(systemId, error, "Unable to update saved marker") }
        }
        return true
    }

    fun removeSaved(systemId: Int): Boolean {
        if (!reserveExistingSaved(systemId)) return false
        scope.launch {
            runCatching {
                check(savedMarkerService.delete(systemId)) {
                    "Saved marker no longer exists for solar system $systemId"
                }
            }.onSuccess {
                completeSavedMutation(systemId)
            }.onFailure { error ->
                failSavedMutation(systemId, error, "Unable to remove saved marker")
            }
        }
        return true
    }

    fun addChild(parentSystemId: Int, type: SavedMarkerChildType): Boolean {
        if (!reserveExistingSaved(parentSystemId)) return false
        synchronized(stateLock) {
            val current = currentState()
            if (current.childrenByParentSystemId[parentSystemId].orEmpty().any { it.type == type }) {
                updateTransient { state ->
                    state.copy(
                        busySystemIds = state.busySystemIds - parentSystemId,
                        operationError = "${type.key} is already assigned to this saved marker",
                    )
                }
                return false
            }
        }
        scope.launch {
            runCatching { savedMarkerService.addChild(parentSystemId, type) }
                .onSuccess { completeSavedMutation(parentSystemId) }
                .onFailure { error -> failSavedMutation(parentSystemId, error, "Unable to add saved marker tag") }
        }
        return true
    }

    fun removeChild(parentSystemId: Int, childId: String): Boolean {
        val childExists = synchronized(stateLock) {
            currentState().childrenByParentSystemId[parentSystemId].orEmpty().any { it.id == childId }
        }
        if (!childExists) {
            setOperationError("Saved marker tag no longer exists")
            return false
        }
        if (!reserveExistingSaved(parentSystemId)) return false
        scope.launch {
            runCatching {
                check(savedMarkerService.removeChild(parentSystemId, childId)) {
                    "Saved marker tag no longer exists"
                }
            }
                .onSuccess { completeSavedMutation(parentSystemId) }
                .onFailure { error -> failSavedMutation(parentSystemId, error, "Unable to remove saved marker tag") }
        }
        return true
    }

    fun saveTemporaryPermanently(systemId: Int): Boolean {
        val snapshot = synchronized(stateLock) {
            val current = currentState()
            val marker = current.markersBySystemId[systemId]
            val error = when {
                current.isLoading -> "Saved markers are still loading"
                current.databaseError != null -> current.databaseError
                systemId in current.busySystemIds ->
                    "Marker operation is already in progress for solar system $systemId"
                marker == null -> "Marker no longer exists for solar system $systemId"
                marker.persistence != MarkerPersistence.TEMPORARY ->
                    "Marker is already saved for solar system $systemId"
                else -> null
            }
            if (error != null) {
                updateTransient { it.copy(operationError = error) }
                return false
            }
            reserve(systemId)
            checkNotNull(marker)
        }
        scope.launch {
            runCatching { savedMarkerService.create(systemId, snapshot.toDraft()) }
                .onSuccess {
                    synchronized(stateLock) {
                        check(transientState.temporaryMarkersBySystemId[systemId] == snapshot) {
                            "Temporary marker changed during persistence"
                        }
                        updateTransient { state ->
                            state.copy(
                                temporaryMarkersBySystemId = state.temporaryMarkersBySystemId - systemId,
                                busySystemIds = state.busySystemIds - systemId,
                                operationError = null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    failSavedMutation(systemId, error, "Unable to save temporary marker permanently")
                }
        }
        return true
    }

    fun clearOperationError() = setOperationError(null)

    fun close() = scope.cancel()

    private fun reserveExistingSaved(systemId: Int): Boolean = synchronized(stateLock) {
        val current = currentState()
        val marker = current.markersBySystemId[systemId]
        val error = when {
            current.isLoading -> "Saved markers are still loading"
            current.databaseError != null -> current.databaseError
            systemId in current.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
            marker == null -> "Marker no longer exists for solar system $systemId"
            marker.persistence != MarkerPersistence.SAVED ->
                "Temporary marker cannot use a saved marker operation for solar system $systemId"
            else -> null
        }
        if (error != null) {
            updateTransient { it.copy(operationError = error) }
            return@synchronized false
        }
        reserve(systemId)
        true
    }

    private fun creationError(state: MarkerUiState, systemId: Int): String? = when {
        state.isLoading -> "Saved markers are still loading"
        state.databaseError != null -> state.databaseError
        systemId in state.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
        systemId in state.markersBySystemId -> "Solar system $systemId already has a marker"
        else -> null
    }

    private fun reserve(systemId: Int) = updateTransient { state ->
        state.copy(busySystemIds = state.busySystemIds + systemId, operationError = null)
    }

    private fun completeSavedMutation(systemId: Int) = updateTransient { state ->
        state.copy(busySystemIds = state.busySystemIds - systemId, operationError = null)
    }

    private fun failSavedMutation(systemId: Int, error: Throwable, fallback: String) =
        updateTransient { state ->
            state.copy(
                busySystemIds = state.busySystemIds - systemId,
                operationError = error.message ?: fallback,
            )
        }

    private fun setOperationError(message: String?) = synchronized(stateLock) {
        updateTransient { it.copy(operationError = message) }
    }

    private fun currentState(): MarkerUiState = buildUiState(savedMarkerService.state.value, transientState)

    private fun updateTransient(transform: (MarkerTransientState) -> MarkerTransientState) {
        transientState = transform(transientState)
        mutableState.value = buildUiState(savedMarkerService.state.value, transientState)
    }
}

private data class MarkerTransientState(
    val temporaryMarkersBySystemId: Map<Int, Marker> = emptyMap(),
    val busySystemIds: Set<Int> = emptySet(),
    val operationError: String? = null,
)

private fun buildUiState(saved: SavedMarkerState, transient: MarkerTransientState): MarkerUiState = MarkerUiState(
    isLoading = saved.isLoading,
    markersBySystemId = saved.markersBySystemId + transient.temporaryMarkersBySystemId,
    childrenByParentSystemId = saved.childrenByParentSystemId,
    busySystemIds = transient.busySystemIds,
    databaseError = saved.databaseError,
    operationError = transient.operationError,
)
