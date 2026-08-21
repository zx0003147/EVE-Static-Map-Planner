package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.repository.SavedMarkerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MarkerViewModel(
    private val savedMarkerRepository: SavedMarkerRepository?,
    userDatabaseError: String?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(MarkerUiState())
    val state: StateFlow<MarkerUiState> = mutableState.asStateFlow()

    init {
        if (savedMarkerRepository == null) {
            setState {
                it.copy(
                    isLoading = false,
                    databaseError = userDatabaseError ?: "Saved marker database is unavailable",
                )
            }
        } else {
            loadSavedMarkers()
        }
    }

    fun addTemporary(systemId: Int): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        creationError(current, systemId)?.let {
            mutableState.value = current.copy(operationError = it)
            return@synchronized false
        }
        val marker = runCatching { Marker.temporary(systemId) }.getOrElse { error ->
            mutableState.value = current.copy(operationError = error.message ?: "Unable to create temporary marker")
            return@synchronized false
        }
        mutableState.value = current.copy(
            markersBySystemId = current.markersBySystemId + (systemId to marker),
            operationError = null,
        )
        true
    }

    fun updateTemporary(systemId: Int, draft: MarkerDraft): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        val marker = current.markersBySystemId[systemId]
        val error = when {
            systemId in current.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
            marker == null -> "Marker no longer exists for solar system $systemId"
            marker.persistence != MarkerPersistence.TEMPORARY -> "Saved marker cannot be updated as temporary for solar system $systemId"
            else -> null
        }
        if (error != null) {
            mutableState.value = current.copy(operationError = error)
            return@synchronized false
        }
        mutableState.value = current.copy(
            markersBySystemId = current.markersBySystemId + (systemId to Marker.temporary(systemId, draft)),
            operationError = null,
        )
        true
    }

    fun removeTemporary(systemId: Int): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        val marker = current.markersBySystemId[systemId]
        val error = when {
            systemId in current.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
            marker == null -> "Marker no longer exists for solar system $systemId"
            marker.persistence != MarkerPersistence.TEMPORARY -> "Saved marker cannot be removed as temporary for solar system $systemId"
            else -> null
        }
        if (error != null) {
            mutableState.value = current.copy(operationError = error)
            return@synchronized false
        }
        mutableState.value = current.copy(
            markersBySystemId = current.markersBySystemId - systemId,
            operationError = null,
        )
        true
    }

    fun clearTemporaryMarkers(): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        val busyTemporary = current.busySystemIds.any { id ->
            current.markersBySystemId[id]?.persistence == MarkerPersistence.TEMPORARY
        }
        if (busyTemporary) {
            mutableState.value = current.copy(operationError = "A temporary marker operation is still in progress")
            return@synchronized false
        }
        mutableState.value = current.copy(
            markersBySystemId = current.markersBySystemId.filterValues { it.persistence == MarkerPersistence.SAVED },
            operationError = null,
        )
        true
    }

    fun createSaved(systemId: Int, draft: MarkerDraft): Boolean {
        val repository = savedMarkerRepository ?: return rejectDatabaseUnavailable()
        synchronized(stateLock) {
            val current = mutableState.value
            creationError(current, systemId)?.let {
                mutableState.value = current.copy(operationError = it)
                return false
            }
            mutableState.value = current.copy(
                busySystemIds = current.busySystemIds + systemId,
                operationError = null,
            )
        }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { repository.create(systemId, draft) }.also { marker ->
                    check(marker.systemId == systemId && marker.persistence == MarkerPersistence.SAVED)
                }
            }
                .onSuccess { marker ->
                    completeSavedMutation(systemId) { markers -> markers + (systemId to marker) }
                }
                .onFailure { error -> failSavedMutation(systemId, error, "Unable to create saved marker") }
        }
        return true
    }

    fun updateSaved(systemId: Int, draft: MarkerDraft): Boolean {
        val repository = savedMarkerRepository ?: return rejectDatabaseUnavailable()
        if (!reserveExistingSaved(systemId)) return false
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { repository.update(systemId, draft) }.also { marker ->
                    check(marker.systemId == systemId && marker.persistence == MarkerPersistence.SAVED)
                }
            }
                .onSuccess { marker ->
                    completeSavedMutation(systemId) { markers -> markers + (systemId to marker) }
                }
                .onFailure { error -> failSavedMutation(systemId, error, "Unable to update saved marker") }
        }
        return true
    }

    fun removeSaved(systemId: Int): Boolean {
        val repository = savedMarkerRepository ?: return rejectDatabaseUnavailable()
        if (!reserveExistingSaved(systemId)) return false
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    check(repository.delete(systemId)) { "Saved marker no longer exists for solar system $systemId" }
                }
            }.onSuccess {
                completeSavedMutation(systemId) { markers -> markers - systemId }
            }.onFailure { error ->
                failSavedMutation(systemId, error, "Unable to remove saved marker")
            }
        }
        return true
    }

    fun saveTemporaryPermanently(systemId: Int): Boolean {
        val repository = savedMarkerRepository ?: return rejectDatabaseUnavailable()
        val snapshot = synchronized(stateLock) {
            val current = mutableState.value
            val marker = current.markersBySystemId[systemId]
            val error = when {
                current.isLoading -> "Saved markers are still loading"
                current.databaseError != null -> current.databaseError
                systemId in current.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
                marker == null -> "Marker no longer exists for solar system $systemId"
                marker.persistence != MarkerPersistence.TEMPORARY -> "Marker is already saved for solar system $systemId"
                else -> null
            }
            if (error != null) {
                mutableState.value = current.copy(operationError = error)
                return false
            }
            mutableState.value = current.copy(
                busySystemIds = current.busySystemIds + systemId,
                operationError = null,
            )
            checkNotNull(marker)
        }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { repository.create(systemId, snapshot.toDraft()) }.also { saved ->
                    check(saved.systemId == systemId && saved.persistence == MarkerPersistence.SAVED)
                }
            }
                .onSuccess { saved ->
                    completeSavedMutation(systemId) { markers ->
                        check(markers[systemId] == snapshot) { "Temporary marker changed during persistence" }
                        markers + (systemId to saved)
                    }
                }
                .onFailure { error ->
                    failSavedMutation(systemId, error, "Unable to save temporary marker permanently")
                }
        }
        return true
    }

    fun clearOperationError() = setState { it.copy(operationError = null) }

    fun close() = scope.cancel()

    private fun loadSavedMarkers() {
        val repository = checkNotNull(savedMarkerRepository)
        scope.launch {
            runCatching { withContext(ioDispatcher) { repository.getAll() } }
                .onSuccess { markers ->
                    val bySystemId = markers.associateBy(Marker::systemId)
                    check(bySystemId.size == markers.size) { "Saved marker repository returned duplicate solar systems" }
                    check(markers.all { it.persistence == MarkerPersistence.SAVED }) {
                        "Saved marker repository returned a temporary marker"
                    }
                    setState {
                        it.copy(
                            isLoading = false,
                            markersBySystemId = bySystemId,
                            databaseError = null,
                            operationError = null,
                        )
                    }
                }
                .onFailure { error ->
                    setState {
                        it.copy(
                            isLoading = false,
                            databaseError = error.message ?: "Unable to load saved markers",
                        )
                    }
                }
        }
    }

    private fun reserveExistingSaved(systemId: Int): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        val marker = current.markersBySystemId[systemId]
        val error = when {
            current.isLoading -> "Saved markers are still loading"
            current.databaseError != null -> current.databaseError
            systemId in current.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
            marker == null -> "Marker no longer exists for solar system $systemId"
            marker.persistence != MarkerPersistence.SAVED -> "Temporary marker cannot use a saved marker operation for solar system $systemId"
            else -> null
        }
        if (error != null) {
            mutableState.value = current.copy(operationError = error)
            return@synchronized false
        }
        mutableState.value = current.copy(
            busySystemIds = current.busySystemIds + systemId,
            operationError = null,
        )
        true
    }

    private fun creationError(state: MarkerUiState, systemId: Int): String? = when {
        state.isLoading -> "Saved markers are still loading"
        state.databaseError != null -> state.databaseError
        systemId in state.busySystemIds -> "Marker operation is already in progress for solar system $systemId"
        systemId in state.markersBySystemId -> "Solar system $systemId already has a marker"
        else -> null
    }

    private fun completeSavedMutation(
        systemId: Int,
        transform: (Map<Int, Marker>) -> Map<Int, Marker>,
    ) = setState { current ->
        current.copy(
            markersBySystemId = transform(current.markersBySystemId),
            busySystemIds = current.busySystemIds - systemId,
            operationError = null,
        )
    }

    private fun failSavedMutation(systemId: Int, error: Throwable, fallback: String) = setState { current ->
        current.copy(
            busySystemIds = current.busySystemIds - systemId,
            operationError = error.message ?: fallback,
        )
    }

    private fun rejectDatabaseUnavailable(): Boolean {
        setState { current ->
            current.copy(operationError = current.databaseError ?: "Saved marker database is unavailable")
        }
        return false
    }

    private fun setState(transform: (MarkerUiState) -> MarkerUiState) {
        synchronized(stateLock) {
            mutableState.value = transform(mutableState.value)
        }
    }
}
