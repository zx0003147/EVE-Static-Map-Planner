package dev.evestaticmapplanner.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.route.NormalRouteEngine
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteGraph
import dev.evestaticmapplanner.core.route.RouteGraphBuilder
import dev.evestaticmapplanner.core.route.RouteOptions
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

class RoutePlannerViewModel(
    private val staticMapRepository: StaticMapRepository,
    private val searchRepository: SystemSearchRepository,
    private val ansiblexRepository: AnsiblexRepository?,
    private val importService: AnsiblexImportService?,
    userDatabaseError: String?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMillis: Long = 180,
    private val routeEngine: NormalRouteEngine = NormalRouteEngine(),
) {
    private val mutableState = MutableStateFlow(RoutePlannerUiState(userDatabaseError = userDatabaseError))
    val state: StateFlow<RoutePlannerUiState> = mutableState.asStateFlow()

    private var staticData: StaticMapData? = null
    private var systemsById: Map<Int, SolarSystem> = emptyMap()
    private var graph: RouteGraph? = null
    private var systemSearchJob: Job? = null
    private var fromSearchJob: Job? = null
    private var toSearchJob: Job? = null

    init {
        load()
    }

    fun updateSystemQuery(query: String) {
        mutableState.update { it.copy(systemQuery = query, systemResults = emptyList()) }
        systemSearchJob?.cancel()
        systemSearchJob = scheduleSearch(query) { results ->
            mutableState.update { it.copy(systemResults = results) }
        }
    }

    fun selectSystemSearch(system: SolarSystem) {
        systemSearchJob?.cancel()
        mutableState.update { it.copy(systemQuery = system.name, systemResults = emptyList()) }
    }

    fun updateFromQuery(query: String) {
        mutableState.update { it.copy(fromQuery = query, selectedFrom = null, fromResults = emptyList()) }
        fromSearchJob?.cancel()
        fromSearchJob = scheduleSearch(query) { results -> mutableState.update { it.copy(fromResults = results) } }
    }

    fun updateToQuery(query: String) {
        mutableState.update { it.copy(toQuery = query, selectedTo = null, toResults = emptyList()) }
        toSearchJob?.cancel()
        toSearchJob = scheduleSearch(query) { results -> mutableState.update { it.copy(toResults = results) } }
    }

    fun selectFrom(system: SolarSystem) {
        fromSearchJob?.cancel()
        mutableState.update { it.copy(fromQuery = system.name, selectedFrom = system, fromResults = emptyList()) }
    }

    fun selectTo(system: SolarSystem) {
        toSearchJob?.cancel()
        mutableState.update { it.copy(toQuery = system.name, selectedTo = system, toResults = emptyList()) }
    }

    fun setRouteStart(systemId: Int) {
        systemsById[systemId]?.let(::selectFrom)
    }

    fun setRouteDestination(systemId: Int) {
        systemsById[systemId]?.let(::selectTo)
    }

    fun setUseAnsiblex(enabled: Boolean) {
        mutableState.update {
            it.copy(
                useAnsiblex = enabled && it.isAnsiblexAvailable,
                routeOutcome = null,
                activeRoute = null,
                routeSystemNames = emptyList(),
            )
        }
    }

    fun setShowAnsiblexLayer(show: Boolean) {
        mutableState.update { it.copy(showAnsiblexLayer = show) }
    }

    fun calculateRoute() {
        val current = mutableState.value
        val start = current.selectedFrom ?: return
        val destination = current.selectedTo ?: return
        val routeGraph = graph ?: return
        val outcome = routeEngine.calculate(
            routeGraph,
            start.id,
            destination.id,
            RouteOptions(useAnsiblex = current.useAnsiblex),
        )
        val route = when (outcome) {
            is RouteCalculationOutcome.Found -> outcome.route
            is RouteCalculationOutcome.SameSystem -> outcome.route
            else -> null
        }
        mutableState.update {
            it.copy(
                routeOutcome = outcome,
                activeRoute = route,
                routeSystemNames = route?.systems?.map { id -> systemsById[id]?.name ?: id.toString() }.orEmpty(),
            )
        }
    }

    fun clearRoute() {
        mutableState.update { it.copy(routeOutcome = null, activeRoute = null, routeSystemNames = emptyList()) }
    }

    fun setImportMode(mode: AnsiblexImportMode) {
        mutableState.update { it.copy(importMode = mode, importPreview = null, importError = null) }
    }

    fun previewImport(file: Path) {
        val service = importService ?: return
        val mode = mutableState.value.importMode
        mutableState.update { it.copy(isImportBusy = true, importPreview = null, importError = null) }
        scope.launch {
            runCatching { withContext(ioDispatcher) { service.preview(file, mode) } }
                .onSuccess { preview ->
                    mutableState.update { it.copy(isImportBusy = false, importPreview = preview) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(isImportBusy = false, importError = error.message ?: "Unable to preview import") }
                }
        }
    }

    fun applyImport() {
        val service = importService ?: return
        val preview = mutableState.value.importPreview ?: return
        mutableState.update { it.copy(isImportBusy = true, importError = null) }
        scope.launch {
            runCatching { withContext(ioDispatcher) { service.apply(preview) } }
                .onSuccess { result ->
                    refreshAnsiblex(
                        "Applied ${result.addedCount} additions, ${result.updatedCount} updates, " +
                            "${result.removedCount} removals",
                    )
                }
                .onFailure { error ->
                    mutableState.update { it.copy(isImportBusy = false, importError = error.message ?: "Unable to apply import") }
                }
        }
    }

    fun discardImportPreview() {
        mutableState.update { it.copy(importPreview = null, importError = null) }
    }

    fun addManual(
        from: String,
        to: String,
        bidirectional: Boolean,
        displayName: String?,
        notes: String?,
    ) {
        val repository = ansiblexRepository ?: return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val fromSystem = resolveExact(from) ?: error("Unknown or ambiguous From system: $from")
                    val toSystem = resolveExact(to) ?: error("Unknown or ambiguous To system: $to")
                    repository.addManual(
                        AnsiblexDraft(
                            fromSystemId = fromSystem.id,
                            toSystemId = toSystem.id,
                            bidirectional = bidirectional,
                            displayName = displayName,
                            notes = notes,
                        ),
                    )
                }
            }.onSuccess {
                refreshAnsiblex("Manual connection added")
            }.onFailure { error ->
                mutableState.update { it.copy(managerMessage = error.message ?: "Unable to add connection") }
            }
        }
    }

    fun setConnectionEnabled(id: String, enabled: Boolean) = mutateConnections {
        check(it.setEnabled(id, enabled)) { "Connection no longer exists" }
        if (enabled) "Connection enabled" else "Connection disabled"
    }

    fun deleteConnection(id: String) = mutateConnections {
        check(it.delete(id)) { "Connection no longer exists" }
        "Connection deleted"
    }

    fun clearImported() = mutateConnections { "Cleared ${it.clearImported()} imported connections" }

    fun clearAll() = mutateConnections { "Cleared ${it.clearAll()} Ansiblex connections" }

    fun clearManagerMessage() {
        mutableState.update { it.copy(managerMessage = null) }
    }

    fun close() {
        scope.cancel()
    }

    private fun load() {
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val data = staticMapRepository.load()
                    val ansiblex = ansiblexRepository?.getAll().orEmpty()
                    data to ansiblex
                }
            }.onSuccess { (data, ansiblex) ->
                staticData = data
                systemsById = data.systems.associateBy(SolarSystem::id)
                val graphResult = runCatching { RouteGraphBuilder.build(data, ansiblex) }
                graph = graphResult.getOrElse { RouteGraphBuilder.build(data) }
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        userDatabaseError = it.userDatabaseError ?: graphResult.exceptionOrNull()?.message,
                        ansiblexConnections = if (graphResult.isSuccess) ansiblex else emptyList(),
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.copy(isLoading = false, error = error.message ?: "Unable to load route graph") }
            }
        }
    }

    private fun scheduleSearch(query: String, publish: (List<SolarSystem>) -> Unit): Job = scope.launch {
        if (query.isBlank()) {
            publish(emptyList())
            return@launch
        }
        delay(searchDebounceMillis)
        val results = withContext(ioDispatcher) { searchRepository.searchSystems(query, 20) }
        publish(results)
    }

    private fun resolveExact(value: String): SolarSystem? {
        val query = value.trim()
        query.toIntOrNull()?.let { return systemsById[it] }
        return searchRepository.searchSystems(query, 20).singleOrNull { it.name.equals(query, ignoreCase = true) }
    }

    private fun mutateConnections(block: (AnsiblexRepository) -> String) {
        val repository = ansiblexRepository ?: return
        scope.launch {
            runCatching { withContext(ioDispatcher) { block(repository) } }
                .onSuccess(::refreshAnsiblex)
                .onFailure { error ->
                    mutableState.update { it.copy(managerMessage = error.message ?: "Unable to update connection") }
                }
        }
    }

    private fun refreshAnsiblex(message: String) {
        val repository = ansiblexRepository ?: return
        scope.launch {
            runCatching { withContext(ioDispatcher) { repository.getAll() } }
                .onSuccess { connections ->
                    val data = checkNotNull(staticData)
                    graph = RouteGraphBuilder.build(data, connections)
                    mutableState.update {
                        it.copy(
                            ansiblexConnections = connections,
                            routeOutcome = null,
                            activeRoute = null,
                            routeSystemNames = emptyList(),
                            importPreview = null,
                            importError = null,
                            isImportBusy = false,
                            managerMessage = message,
                        )
                    }
                }
                .onFailure { error ->
                    graph = staticData?.let(RouteGraphBuilder::build)
                    mutableState.update {
                        it.copy(
                            userDatabaseError = error.message ?: "Unable to refresh Ansiblex data",
                            ansiblexConnections = emptyList(),
                            useAnsiblex = false,
                            isImportBusy = false,
                        )
                    }
                }
        }
    }
}
