package dev.evestaticmapplanner.marker.application

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.repository.SavedMarkerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SavedMarkerState(
    val isLoading: Boolean = true,
    val markersBySystemId: Map<Int, Marker> = emptyMap(),
    val childrenByParentSystemId: Map<Int, List<SavedMarkerChild>> = emptyMap(),
    val databaseError: String? = null,
)

class SavedMarkerService(
    private val repository: SavedMarkerRepository?,
    userDatabaseError: String?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutationMutex = Mutex()
    private val mutableState = MutableStateFlow(SavedMarkerState())
    val state: StateFlow<SavedMarkerState> = mutableState.asStateFlow()

    init {
        if (repository == null) {
            mutableState.value = SavedMarkerState(
                isLoading = false,
                databaseError = userDatabaseError ?: "Saved marker database is unavailable",
            )
        } else {
            scope.launch { load() }
        }
    }

    fun get(systemId: Int): Marker? = state.value.markersBySystemId[systemId]

    fun getAll(): List<Marker> = state.value.markersBySystemId.values.sortedBy(Marker::systemId)

    suspend fun create(systemId: Int, draft: MarkerDraft): Marker = mutate { repository ->
        val marker = withContext(ioDispatcher) { repository.create(systemId, draft) }
        validateSavedMarker(marker, systemId)
        val current = mutableState.value
        mutableState.value = current.copy(
            markersBySystemId = current.markersBySystemId + (systemId to marker),
            childrenByParentSystemId = current.childrenByParentSystemId + (systemId to emptyList()),
        )
        marker
    }

    suspend fun update(systemId: Int, draft: MarkerDraft): Marker = mutate { repository ->
        val marker = withContext(ioDispatcher) { repository.update(systemId, draft) }
        validateSavedMarker(marker, systemId)
        val current = mutableState.value
        mutableState.value = current.copy(
            markersBySystemId = current.markersBySystemId + (systemId to marker),
        )
        marker
    }

    suspend fun delete(systemId: Int): Boolean = mutate { repository ->
        val deleted = withContext(ioDispatcher) { repository.delete(systemId) }
        if (deleted) {
            val current = mutableState.value
            mutableState.value = current.copy(
                markersBySystemId = current.markersBySystemId - systemId,
                childrenByParentSystemId = current.childrenByParentSystemId - systemId,
            )
        }
        deleted
    }

    suspend fun addChild(parentSystemId: Int, type: SavedMarkerChildType): SavedMarkerChild =
        mutate { repository ->
            val child = withContext(ioDispatcher) { repository.addChild(parentSystemId, type) }
            check(child.parentSystemId == parentSystemId) {
                "Saved marker repository returned a child for the wrong parent"
            }
            val current = mutableState.value
            val children = (current.childrenByParentSystemId[parentSystemId].orEmpty() + child)
                .sortedWith(childOrdering)
            mutableState.value = current.copy(
                childrenByParentSystemId = current.childrenByParentSystemId + (parentSystemId to children),
            )
            child
        }

    suspend fun removeChild(parentSystemId: Int, childId: String): Boolean = mutate { repository ->
        val removed = withContext(ioDispatcher) { repository.removeChild(parentSystemId, childId) }
        if (removed) {
            val current = mutableState.value
            val children = current.childrenByParentSystemId[parentSystemId].orEmpty()
                .filterNot { it.id == childId }
            mutableState.value = current.copy(
                childrenByParentSystemId = current.childrenByParentSystemId + (parentSystemId to children),
            )
        }
        removed
    }

    fun close() = scope.cancel()

    private suspend fun load() = mutationMutex.withLock {
        val repository = checkNotNull(repository)
        runCatching {
            withContext(ioDispatcher) {
                repository.getAll().let { markers -> markers to repository.getAllChildren() }
            }
        }.onSuccess { (markers, childrenByParent) ->
            val bySystemId = markers.associateBy(Marker::systemId)
            check(bySystemId.size == markers.size) { "Saved marker repository returned duplicate solar systems" }
            check(markers.all { it.persistence == MarkerPersistence.SAVED }) {
                "Saved marker repository returned a temporary marker"
            }
            check(childrenByParent.keys.all(bySystemId::containsKey)) {
                "Saved marker repository returned children without a loaded parent"
            }
            mutableState.value = SavedMarkerState(
                isLoading = false,
                markersBySystemId = bySystemId,
                childrenByParentSystemId = bySystemId.keys.associateWith { systemId ->
                    childrenByParent[systemId].orEmpty().sortedWith(childOrdering)
                },
            )
        }.onFailure { error ->
            mutableState.value = SavedMarkerState(
                isLoading = false,
                databaseError = error.message ?: "Unable to load saved markers",
            )
        }
    }

    private suspend fun <T> mutate(operation: suspend (SavedMarkerRepository) -> T): T =
        mutationMutex.withLock {
            val current = mutableState.value
            check(!current.isLoading) { "Saved markers are still loading" }
            check(current.databaseError == null) { current.databaseError ?: "Saved marker database is unavailable" }
            operation(checkNotNull(repository))
        }
}

private val childOrdering = compareBy(SavedMarkerChild::orderIndex, SavedMarkerChild::id)

private fun validateSavedMarker(marker: Marker, expectedSystemId: Int) {
    check(marker.systemId == expectedSystemId && marker.persistence == MarkerPersistence.SAVED) {
        "Saved marker repository returned an invalid marker for solar system $expectedSystemId"
    }
}
