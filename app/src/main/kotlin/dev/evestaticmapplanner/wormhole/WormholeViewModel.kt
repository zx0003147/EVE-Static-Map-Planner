package dev.evestaticmapplanner.wormhole

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import dev.evestaticmapplanner.localization.UiMessage
import dev.evestaticmapplanner.localization.WormholeMessage
import dev.evestaticmapplanner.localization.WormholeUiMessage
import dev.evestaticmapplanner.localization.WormholeStrings
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
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
    val loadError: UiMessage? = null,
    val connections: List<WormholeConnection> = emptyList(),
    val systemNamesById: Map<Int, String> = emptyMap(),
    val managerFromQuery: String = "",
    val managerFromResults: List<SolarSystem> = emptyList(),
    val selectedManagerFrom: SolarSystem? = null,
    val managerToQuery: String = "",
    val managerToResults: List<SolarSystem> = emptyList(),
    val selectedManagerTo: SolarSystem? = null,
    val managerMessage: UiMessage? = null,
    val quickOrigin: SolarSystem? = null,
    val quickToQuery: String = "",
    val quickToResults: List<SolarSystem> = emptyList(),
    val selectedQuickTo: SolarSystem? = null,
    val quickMessage: UiMessage? = null,
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
        strings: WormholeStrings = AppStringsCatalog.forLocale(AppLocale.EN_US).wormhole,
    ): List<WormholeConnectionRow> = connections.map { connection ->
        WormholeConnectionRow(
            id = connection.id,
            firstSystemId = connection.firstSystemId,
            secondSystemId = connection.secondSystemId,
            firstSystemName = systemNamesById[connection.firstSystemId] ?: strings.fallbackSystem(connection.firstSystemId),
            secondSystemName = systemNamesById[connection.secondSystemId] ?: strings.fallbackSystem(connection.secondSystemId),
        )
    }

    fun rowsForSystem(
        systemId: Int,
        connections: List<WormholeConnection>,
        systemNamesById: Map<Int, String>,
        strings: WormholeStrings = AppStringsCatalog.forLocale(AppLocale.EN_US).wormhole,
    ): List<WormholeConnectionRow> = rows(connections, systemNamesById, strings).filter {
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
                    managerMessage = WormholeUiMessage(WormholeMessage.ADDED),
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
            it.copy(
                managerMessage = WormholeUiMessage(
                    if (removed) WormholeMessage.REMOVED else WormholeMessage.MISSING,
                ),
            )
        }
        return removed
    }

    fun clearAll(): Int {
        val cleared = store.clear()
        mutableState.update {
            it.copy(managerMessage = WormholeUiMessage(WormholeMessage.CLEARED, count = cleared))
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
                        it.copy(
                            isLoading = false,
                            loadError = WormholeUiMessage(
                                WormholeMessage.LOAD_FAILED,
                                technicalDetail = failure.message,
                            ),
                        )
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
        onError: (UiMessage) -> Unit,
        publish: (List<SolarSystem>) -> Unit,
    ): Job = scope.launch {
        if (query.isBlank()) {
            publish(emptyList())
            return@launch
        }
        delay(searchDebounceMillis)
        val results = runCatching { withContext(ioDispatcher) { searchRepository.searchSystems(query, 20) } }
            .getOrElse { failure ->
                onError(WormholeUiMessage(WormholeMessage.SEARCH_FAILED, technicalDetail = failure.message))
                emptyList()
            }
        publish(results)
    }

    private fun sameEndpointMessage(first: SolarSystem?, second: SolarSystem?): UiMessage? =
        if (first != null && second != null && first.id == second.id) {
            WormholeUiMessage(WormholeMessage.SAME_ENDPOINT)
        } else {
            null
        }
}

private val CreateWormholeUiResult.message: UiMessage
    get() = when (this) {
        CreateWormholeUiResult.CREATED -> WormholeUiMessage(WormholeMessage.ADDED)
        CreateWormholeUiResult.ALREADY_EXISTS -> WormholeUiMessage(WormholeMessage.DUPLICATE)
        CreateWormholeUiResult.SAME_ENDPOINT -> WormholeUiMessage(WormholeMessage.SAME_ENDPOINT)
        CreateWormholeUiResult.INVALID_SELECTION -> WormholeUiMessage(WormholeMessage.INVALID_SELECTION)
    }
