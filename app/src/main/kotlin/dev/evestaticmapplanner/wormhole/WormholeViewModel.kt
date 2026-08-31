package dev.evestaticmapplanner.wormhole

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WormholeUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val connections: List<WormholeConnection> = emptyList(),
    val systemNamesById: Map<Int, String> = emptyMap(),
    val managerFromQuery: String = "",
    val managerFromResults: List<SolarSystem> = emptyList(),
    val selectedManagerFrom: SolarSystem? = null,
    val managerToQuery: String = "",
    val managerToResults: List<SolarSystem> = emptyList(),
    val selectedManagerTo: SolarSystem? = null,
    val managerMessage: String? = null,
    val quickOrigin: SolarSystem? = null,
    val quickToQuery: String = "",
    val quickToResults: List<SolarSystem> = emptyList(),
    val selectedQuickTo: SolarSystem? = null,
    val quickMessage: String? = null,
) {
    val canAddFromManager: Boolean
        get() = selectedManagerFrom != null &&
            selectedManagerTo != null &&
            selectedManagerFrom.id != selectedManagerTo.id

    val canAddFromQuickCreate: Boolean
        get() = quickOrigin != null && selectedQuickTo != null && quickOrigin.id != selectedQuickTo.id
}

enum class CreateWormholeUiResult {
    CREATED,
    ALREADY_EXISTS,
    SAME_ENDPOINT,
    INVALID_SELECTION,
}

data class WormholeConnectionRow(
    val id: String,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val firstSystemName: String,
    val secondSystemName: String,
) {
    val canonicalLabel: String get() = "$firstSystemName ↔ $secondSystemName"

    fun otherEndpointName(systemId: Int): String = when (systemId) {
        firstSystemId -> secondSystemName
        secondSystemId -> firstSystemName
        else -> error("System $systemId is not an endpoint of $id")
    }
}

object WormholePresentationBuilder {
    fun rows(
        connections: List<WormholeConnection>,
        systemNamesById: Map<Int, String>,
    ): List<WormholeConnectionRow> = connections.map { connection ->
        WormholeConnectionRow(
            id = connection.id,
            firstSystemId = connection.firstSystemId,
            secondSystemId = connection.secondSystemId,
            firstSystemName = systemNamesById[connection.firstSystemId] ?: "System ${connection.firstSystemId}",
            secondSystemName = systemNamesById[connection.secondSystemId] ?: "System ${connection.secondSystemId}",
        )
    }

    fun rowsForSystem(
        systemId: Int,
        connections: List<WormholeConnection>,
        systemNamesById: Map<Int, String>,
    ): List<WormholeConnectionRow> = rows(connections, systemNamesById).filter {
        it.firstSystemId == systemId || it.secondSystemId == systemId
    }
}

class WormholeViewModel(
    private val store: WormholeSessionStore,
    private val staticMapRepository: StaticMapRepository,
    private val searchRepository: SystemSearchRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMillis: Long = 180,
) {
    private val mutableState = MutableStateFlow(WormholeUiState(connections = store.connections.value))
    val state: StateFlow<WormholeUiState> = mutableState.asStateFlow()

    private var systemsById: Map<Int, SolarSystem> = emptyMap()
    private var managerFromSearchJob: Job? = null
    private var managerToSearchJob: Job? = null
    private var quickToSearchJob: Job? = null

    init {
        observeConnections()
        loadSystems()
    }

    fun updateManagerFromQuery(query: String) {
        mutableState.update {
            it.copy(
                managerFromQuery = query,
                managerFromResults = emptyList(),
                selectedManagerFrom = null,
                managerMessage = null,
            )
        }
        managerFromSearchJob?.cancel()
        managerFromSearchJob = scheduleSearch(
            query = query,
            onError = { message -> mutableState.update { it.copy(managerMessage = message) } },
            publish = { results -> mutableState.update { it.copy(managerFromResults = results) } },
        )
    }

    fun selectManagerFrom(system: SolarSystem) {
        managerFromSearchJob?.cancel()
        mutableState.update {
            it.copy(
                managerFromQuery = system.name,
                managerFromResults = emptyList(),
                selectedManagerFrom = system,
                managerMessage = sameEndpointMessage(system, it.selectedManagerTo),
            )
        }
    }

    fun updateManagerToQuery(query: String) {
        mutableState.update {
            it.copy(
                managerToQuery = query,
                managerToResults = emptyList(),
                selectedManagerTo = null,
                managerMessage = null,
            )
        }
        managerToSearchJob?.cancel()
        managerToSearchJob = scheduleSearch(
            query = query,
            onError = { message -> mutableState.update { it.copy(managerMessage = message) } },
            publish = { results -> mutableState.update { it.copy(managerToResults = results) } },
        )
    }

    fun selectManagerTo(system: SolarSystem) {
        managerToSearchJob?.cancel()
        mutableState.update {
            it.copy(
                managerToQuery = system.name,
                managerToResults = emptyList(),
                selectedManagerTo = system,
                managerMessage = sameEndpointMessage(it.selectedManagerFrom, system),
            )
        }
    }

    fun addFromManager(): CreateWormholeUiResult {
        val current = mutableState.value
        val result = create(current.selectedManagerFrom, current.selectedManagerTo)
        mutableState.update {
            when (result) {
                CreateWormholeUiResult.CREATED -> it.copy(
                    managerFromQuery = "",
                    managerFromResults = emptyList(),
                    selectedManagerFrom = null,
                    managerToQuery = "",
                    managerToResults = emptyList(),
                    selectedManagerTo = null,
                    managerMessage = WORMHOLE_ADDED_MESSAGE,
                )
                else -> it.copy(managerMessage = result.message)
            }
        }
        return result
    }

    fun beginQuickCreate(origin: SolarSystem) {
        quickToSearchJob?.cancel()
        mutableState.update {
            it.copy(
                quickOrigin = origin,
                quickToQuery = "",
                quickToResults = emptyList(),
                selectedQuickTo = null,
                quickMessage = null,
            )
        }
    }

    fun updateQuickToQuery(query: String) {
        mutableState.update {
            it.copy(
                quickToQuery = query,
                quickToResults = emptyList(),
                selectedQuickTo = null,
                quickMessage = null,
            )
        }
        quickToSearchJob?.cancel()
        quickToSearchJob = scheduleSearch(
            query = query,
            onError = { message -> mutableState.update { it.copy(quickMessage = message) } },
            publish = { results -> mutableState.update { it.copy(quickToResults = results) } },
        )
    }

    fun selectQuickTo(system: SolarSystem) {
        quickToSearchJob?.cancel()
        mutableState.update {
            it.copy(
                quickToQuery = system.name,
                quickToResults = emptyList(),
                selectedQuickTo = system,
                quickMessage = sameEndpointMessage(it.quickOrigin, system),
            )
        }
    }

    fun addFromQuickCreate(): CreateWormholeUiResult {
        val current = mutableState.value
        val result = create(current.quickOrigin, current.selectedQuickTo)
        mutableState.update { it.copy(quickMessage = result.message) }
        return result
    }

    fun endQuickCreate() {
        quickToSearchJob?.cancel()
        mutableState.update {
            it.copy(
                quickOrigin = null,
                quickToQuery = "",
                quickToResults = emptyList(),
                selectedQuickTo = null,
                quickMessage = null,
            )
        }
    }

    fun remove(connectionId: String): Boolean {
        val removed = store.remove(connectionId)
        mutableState.update {
            it.copy(managerMessage = if (removed) WORMHOLE_REMOVED_MESSAGE else WORMHOLE_MISSING_MESSAGE)
        }
        return removed
    }

    fun clearAll(): Int {
        val cleared = store.clear()
        mutableState.update {
            it.copy(managerMessage = "Cleared $cleared Wormhole ${if (cleared == 1) "connection" else "connections"}")
        }
        return cleared
    }

    fun clearManagerMessage() {
        mutableState.update { it.copy(managerMessage = null) }
    }

    fun close() {
        scope.cancel()
    }

    private fun create(first: SolarSystem?, second: SolarSystem?): CreateWormholeUiResult = when {
        first == null || second == null -> CreateWormholeUiResult.INVALID_SELECTION
        first.id == second.id -> CreateWormholeUiResult.SAME_ENDPOINT
        store.add(first.id, second.id) == AddWormholeResult.CREATED -> CreateWormholeUiResult.CREATED
        else -> CreateWormholeUiResult.ALREADY_EXISTS
    }

    private fun loadSystems() {
        scope.launch {
            runCatching { withContext(ioDispatcher) { staticMapRepository.load().systems.associateBy(SolarSystem::id) } }
                .onSuccess { systems ->
                    systemsById = systems
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            systemNamesById = systems.mapValues { entry -> entry.value.name },
                        )
                    }
                }
                .onFailure { failure ->
                    mutableState.update {
                        it.copy(isLoading = false, loadError = failure.message ?: "Unable to load solar systems")
                    }
                }
        }
    }

    private fun observeConnections() {
        scope.launch {
            store.connections.collect { connections ->
                mutableState.update { it.copy(connections = connections) }
            }
        }
    }

    private fun scheduleSearch(
        query: String,
        onError: (String) -> Unit,
        publish: (List<SolarSystem>) -> Unit,
    ): Job = scope.launch {
        if (query.isBlank()) {
            publish(emptyList())
            return@launch
        }
        delay(searchDebounceMillis)
        val results = runCatching { withContext(ioDispatcher) { searchRepository.searchSystems(query, 20) } }
            .getOrElse { failure ->
                onError(failure.message ?: "System search failed")
                emptyList()
            }
        publish(results)
    }

    private fun sameEndpointMessage(first: SolarSystem?, second: SolarSystem?): String? =
        if (first != null && second != null && first.id == second.id) SAME_ENDPOINT_MESSAGE else null
}

private val CreateWormholeUiResult.message: String
    get() = when (this) {
        CreateWormholeUiResult.CREATED -> WORMHOLE_ADDED_MESSAGE
        CreateWormholeUiResult.ALREADY_EXISTS -> WORMHOLE_DUPLICATE_MESSAGE
        CreateWormholeUiResult.SAME_ENDPOINT -> SAME_ENDPOINT_MESSAGE
        CreateWormholeUiResult.INVALID_SELECTION -> "Select both Wormhole endpoints"
    }

internal const val WORMHOLE_ADDED_MESSAGE = "Wormhole added"
internal const val WORMHOLE_REMOVED_MESSAGE = "Wormhole removed"
internal const val WORMHOLE_MISSING_MESSAGE = "Wormhole connection no longer exists"
internal const val WORMHOLE_DUPLICATE_MESSAGE = "Wormhole connection already exists"
internal const val SAME_ENDPOINT_MESSAGE = "Wormhole endpoints must be different systems"
