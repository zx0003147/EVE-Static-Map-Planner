package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.marker.application.SavedMarkerService
import dev.evestaticmapplanner.marker.application.SavedMarkerState
import dev.evestaticmapplanner.localization.MarkerDatabaseUnavailableUiMessage
import dev.evestaticmapplanner.localization.MarkerMessage
import dev.evestaticmapplanner.localization.MarkerUiMessage
import dev.evestaticmapplanner.localization.UiMessage
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

    fun addTemporary(systemId: Int, draft: MarkerDraft = MarkerDraft.create()): Boolean = synchronized(stateLock) {
        val current = currentState()
        creationError(current, systemId)?.let {
            updateTransient { state -> state.copy(operationError = it) }
            return@synchronized false
        }
        val marker = runCatching { Marker.temporary(systemId, draft) }.getOrElse { error ->
            updateTransient { state ->
                state.copy(
                    operationError = MarkerUiMessage(
                        MarkerMessage.CREATE_TEMPORARY_FAILED,
                        technicalDetail = error.message,
                    ),
                )
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
            systemId in current.busySystemIds -> MarkerUiMessage(MarkerMessage.OPERATION_IN_PROGRESS, systemId)
            marker == null -> MarkerUiMessage(MarkerMessage.MARKER_MISSING, systemId)
            marker.persistence != MarkerPersistence.TEMPORARY ->
                MarkerUiMessage(MarkerMessage.SAVED_CANNOT_UPDATE_AS_TEMPORARY, systemId)
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
            systemId in current.busySystemIds -> MarkerUiMessage(MarkerMessage.OPERATION_IN_PROGRESS, systemId)
            marker == null -> MarkerUiMessage(MarkerMessage.MARKER_MISSING, systemId)
            marker.persistence != MarkerPersistence.TEMPORARY ->
                MarkerUiMessage(MarkerMessage.SAVED_CANNOT_REMOVE_AS_TEMPORARY, systemId)
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
            updateTransient { it.copy(operationError = MarkerUiMessage(MarkerMessage.TEMPORARY_OPERATION_IN_PROGRESS)) }
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
                .onFailure { error -> failSavedMutation(systemId, error, MarkerMessage.CREATE_SAVED_FAILED) }
        }
        return true
    }

    fun updateSaved(systemId: Int, draft: MarkerDraft): Boolean {
        if (!reserveExistingSaved(systemId)) return false
        scope.launch {
            runCatching { savedMarkerService.update(systemId, draft) }
                .onSuccess { completeSavedMutation(systemId) }
                .onFailure { error -> failSavedMutation(systemId, error, MarkerMessage.UPDATE_SAVED_FAILED) }
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
                failSavedMutation(systemId, error, MarkerMessage.REMOVE_SAVED_FAILED)
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
                        operationError = MarkerUiMessage(MarkerMessage.TAG_ALREADY_ASSIGNED, tag = type.key),
                    )
                }
                return false
            }
        }
        scope.launch {
            runCatching { savedMarkerService.addChild(parentSystemId, type) }
                .onSuccess { completeSavedMutation(parentSystemId) }
                .onFailure { error -> failSavedMutation(parentSystemId, error, MarkerMessage.ADD_TAG_FAILED) }
        }
        return true
    }

    fun removeChild(parentSystemId: Int, childId: String): Boolean {
        val childExists = synchronized(stateLock) {
            currentState().childrenByParentSystemId[parentSystemId].orEmpty().any { it.id == childId }
        }
        if (!childExists) {
            setOperationError(MarkerUiMessage(MarkerMessage.TAG_MISSING))
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
                .onFailure { error -> failSavedMutation(parentSystemId, error, MarkerMessage.REMOVE_TAG_FAILED) }
        }
        return true
    }

    fun saveTemporaryPermanently(systemId: Int): Boolean {
        val snapshot = synchronized(stateLock) {
            val current = currentState()
            val marker = current.markersBySystemId[systemId]
            val error = when {
                current.isLoading -> MarkerUiMessage(MarkerMessage.STILL_LOADING)
                current.databaseError != null -> MarkerDatabaseUnavailableUiMessage(current.databaseError)
                systemId in current.busySystemIds ->
                    MarkerUiMessage(MarkerMessage.OPERATION_IN_PROGRESS, systemId)
                marker == null -> MarkerUiMessage(MarkerMessage.MARKER_MISSING, systemId)
                marker.persistence != MarkerPersistence.TEMPORARY ->
                    MarkerUiMessage(MarkerMessage.ALREADY_SAVED, systemId)
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
                    failSavedMutation(systemId, error, MarkerMessage.SAVE_TEMPORARY_FAILED)
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
            current.isLoading -> MarkerUiMessage(MarkerMessage.STILL_LOADING)
            current.databaseError != null -> MarkerDatabaseUnavailableUiMessage(current.databaseError)
            systemId in current.busySystemIds -> MarkerUiMessage(MarkerMessage.OPERATION_IN_PROGRESS, systemId)
            marker == null -> MarkerUiMessage(MarkerMessage.MARKER_MISSING, systemId)
            marker.persistence != MarkerPersistence.SAVED ->
                MarkerUiMessage(MarkerMessage.TEMPORARY_CANNOT_USE_SAVED_OPERATION, systemId)
            else -> null
        }
        if (error != null) {
            updateTransient { it.copy(operationError = error) }
            return@synchronized false
        }
        reserve(systemId)
        true
    }

    private fun creationError(state: MarkerUiState, systemId: Int): UiMessage? = when {
        state.isLoading -> MarkerUiMessage(MarkerMessage.STILL_LOADING)
        state.databaseError != null -> MarkerDatabaseUnavailableUiMessage(state.databaseError)
        systemId in state.busySystemIds -> MarkerUiMessage(MarkerMessage.OPERATION_IN_PROGRESS, systemId)
        systemId in state.markersBySystemId -> MarkerUiMessage(MarkerMessage.SYSTEM_ALREADY_MARKED, systemId)
        else -> null
    }

    private fun reserve(systemId: Int) = updateTransient { state ->
        state.copy(busySystemIds = state.busySystemIds + systemId, operationError = null)
    }

    private fun completeSavedMutation(systemId: Int) = updateTransient { state ->
        state.copy(busySystemIds = state.busySystemIds - systemId, operationError = null)
    }

    private fun failSavedMutation(systemId: Int, error: Throwable, message: MarkerMessage) =
        updateTransient { state ->
            state.copy(
                busySystemIds = state.busySystemIds - systemId,
                operationError = MarkerUiMessage(message, systemId = systemId, technicalDetail = error.message),
            )
        }

    private fun setOperationError(message: UiMessage?) = synchronized(stateLock) {
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
    val operationError: UiMessage? = null,
)

private fun buildUiState(saved: SavedMarkerState, transient: MarkerTransientState): MarkerUiState = MarkerUiState(
    isLoading = saved.isLoading,
    markersBySystemId = saved.markersBySystemId + transient.temporaryMarkersBySystemId,
    childrenByParentSystemId = saved.childrenByParentSystemId,
    busySystemIds = transient.busySystemIds,
    databaseError = saved.databaseError,
    operationError = transient.operationError,
)
