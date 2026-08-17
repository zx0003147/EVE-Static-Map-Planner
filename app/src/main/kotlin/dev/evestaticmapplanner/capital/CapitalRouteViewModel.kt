package dev.evestaticmapplanner.capital

import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.route.CapitalRouteEngine
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
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

class CapitalRouteViewModel(
    private val staticMapRepository: StaticMapRepository,
    private val searchRepository: SystemSearchRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val calculationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val searchDebounceMillis: Long = 180,
) {
    private val mutableState = MutableStateFlow(CapitalRouteUiState())
    val state: StateFlow<CapitalRouteUiState> = mutableState.asStateFlow()
    private var systemsById: Map<Int, SolarSystem> = emptyMap()
    private var engine: CapitalRouteEngine? = null
    private var fromSearchJob: Job? = null
    private var toSearchJob: Job? = null

    init {
        scope.launch {
            runCatching { withContext(ioDispatcher) { staticMapRepository.load() } }
                .onSuccess { data ->
                    systemsById = data.systems.associateBy(SolarSystem::id)
                    engine = CapitalRouteEngine(
                        CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(data.systems)),
                    )
                    mutableState.update { it.copy(isLoading = false) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(isLoading = false, error = error.message ?: "Unable to load capital route data") }
                }
        }
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

    fun setRouteStart(systemId: Int) = systemsById[systemId]?.let(::selectFrom)

    fun setRouteDestination(systemId: Int) = systemsById[systemId]?.let(::selectTo)

    fun updateManualRange(text: String) {
        mutableState.update { it.copy(manualRangeText = text, error = null, outcome = null, activeRoute = null) }
    }

    fun calculate() {
        val current = mutableState.value
        val from = current.selectedFrom ?: return
        val to = current.selectedTo ?: return
        val routeEngine = engine ?: return
        val range = current.manualRangeText.trim().toDoubleOrNull()
        if (range == null) {
            mutableState.update { it.copy(error = "Manual maximum LY must be a number") }
            return
        }
        val profile = runCatching { JumpProfile.manual(range, "capital-manual") }.getOrElse { error ->
            mutableState.update { it.copy(error = error.message) }
            return
        }
        mutableState.update { it.copy(isCalculating = true, error = null, outcome = null, activeRoute = null) }
        scope.launch {
            val outcome = withContext(calculationDispatcher) { routeEngine.calculate(from.id, to.id, profile) }
            val route = when (outcome) {
                is CapitalRouteOutcome.Found -> outcome.route
                is CapitalRouteOutcome.SameSystem -> outcome.route
                else -> null
            }
            mutableState.update {
                it.copy(
                    isCalculating = false,
                    outcome = outcome,
                    activeRoute = route,
                    routeSystemNames = route?.systems?.map { id -> systemsById[id]?.name ?: id.toString() }.orEmpty(),
                )
            }
        }
    }

    fun clear() {
        mutableState.update { it.copy(outcome = null, activeRoute = null, routeSystemNames = emptyList(), error = null) }
    }

    fun close() = scope.cancel()

    private fun scheduleSearch(query: String, publish: (List<SolarSystem>) -> Unit): Job = scope.launch {
        if (query.isBlank()) {
            publish(emptyList())
            return@launch
        }
        delay(searchDebounceMillis)
        publish(withContext(ioDispatcher) { searchRepository.searchSystems(query, 20) })
    }
}
