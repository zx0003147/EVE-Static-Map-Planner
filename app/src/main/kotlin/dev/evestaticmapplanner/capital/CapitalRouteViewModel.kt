package dev.evestaticmapplanner.capital

import dev.evestaticmapplanner.AppDiagnostics
import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.route.CapitalRouteEngine
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.CapitalNavigationOutcome
import dev.evestaticmapplanner.core.route.CapitalNavigationPlanner
import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.NavigationIntentValidation
import dev.evestaticmapplanner.core.route.NavigationSegment
import dev.evestaticmapplanner.core.route.NavigationStopRole
import dev.evestaticmapplanner.localization.AdjacentNavigationStopsUiMessage
import dev.evestaticmapplanner.localization.InvalidNavigationStopUiMessage
import dev.evestaticmapplanner.localization.ManualMaximumLyMustBeNumberUiMessage
import dev.evestaticmapplanner.localization.ManualMaximumLyMustBePositiveUiMessage
import dev.evestaticmapplanner.localization.MissingTerminalStopUiMessage
import dev.evestaticmapplanner.localization.NavigationSegmentFailureUiMessage
import dev.evestaticmapplanner.localization.NavigationStopUiRole
import dev.evestaticmapplanner.localization.UiMessage
import dev.evestaticmapplanner.localization.UnableToLoadCapitalRouteDataUiMessage
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

data class CapitalRoutePlanningSnapshot(
    val fromSystemId: Int? = null,
    val toSystemId: Int? = null,
    val manualRangeText: String = "5",
    val calculated: Boolean = false,
    val outcome: CapitalRouteOutcome? = null,
    val activeRoute: CapitalRouteResult? = null,
    val routeSystemNames: List<String> = emptyList(),
    val waypointSystemIds: List<Int> = emptyList(),
    val calculatedWaypointSystemIds: List<Int> = emptyList(),
    val calculatedExplicitDestinationSystemId: Int? = null,
    val isRouteStale: Boolean = false,
    val navigationMessage: UiMessage? = null,
)

interface CapitalRoutePlanningPort {
    fun planningSnapshot(): CapitalRoutePlanningSnapshot
    fun restorePlanningSnapshot(snapshot: CapitalRoutePlanningSnapshot)
}

class CapitalRouteViewModel(
    private val staticMapRepository: StaticMapRepository,
    private val searchRepository: SystemSearchRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val calculationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val searchDebounceMillis: Long = 180,
) : CapitalRoutePlanningPort {
    private val mutableState = MutableStateFlow(CapitalRouteUiState())
    val state: StateFlow<CapitalRouteUiState> = mutableState.asStateFlow()
    private var systemsById: Map<Int, SolarSystem> = emptyMap()
    private var engine: CapitalRouteEngine? = null
    private var fromSearchJob: Job? = null
    private var toSearchJob: Job? = null
    private var pendingPlanningSnapshot: CapitalRoutePlanningSnapshot? = null
    private var calculationGeneration = 0L

    init {
        scope.launch {
            runCatching { withContext(ioDispatcher) { staticMapRepository.load() } }
                .onSuccess { data ->
                    systemsById = data.systems.associateBy(SolarSystem::id)
                    engine = CapitalRouteEngine(
                        CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(data.systems)),
                    )
                    mutableState.update { it.copy(isLoading = false) }
                    pendingPlanningSnapshot?.let {
                        pendingPlanningSnapshot = null
                        applyPlanningSnapshot(it)
                    }
                }
                .onFailure { error ->
                    AppDiagnostics.warning("Capital route data load failed", error)
                    mutableState.update { it.copy(isLoading = false, error = UnableToLoadCapitalRouteDataUiMessage) }
                }
        }
    }

    fun updateFromQuery(query: String) {
        mutableState.update { current ->
            current.copy(
                fromQuery = query,
                selectedFrom = null,
                fromResults = emptyList(),
                isRouteStale = current.activeRoute != null,
                navigationMessage = null,
            )
        }
        fromSearchJob?.cancel()
        fromSearchJob = scheduleSearch(query) { results -> mutableState.update { it.copy(fromResults = results) } }
    }

    fun updateToQuery(query: String) {
        mutableState.update { current ->
            current.copy(
                toQuery = query,
                selectedTo = null,
                toResults = emptyList(),
                isRouteStale = current.activeRoute != null,
                navigationMessage = null,
            )
        }
        toSearchJob?.cancel()
        toSearchJob = scheduleSearch(query) { results -> mutableState.update { it.copy(toResults = results) } }
    }

    fun selectFrom(system: SolarSystem) {
        fromSearchJob?.cancel()
        updateDraft { current -> current.copy(fromQuery = system.name, selectedFrom = system, fromResults = emptyList()) }
    }

    fun selectTo(system: SolarSystem) {
        toSearchJob?.cancel()
        updateDraft { current -> current.copy(toQuery = system.name, selectedTo = system, toResults = emptyList()) }
    }

    fun setRouteStart(systemId: Int) = systemsById[systemId]?.let(::selectFrom)

    fun setRouteDestination(systemId: Int) = systemsById[systemId]?.let(::selectTo)

    fun addRouteWaypoint(systemId: Int) {
        systemsById[systemId]?.let { waypoint ->
            updateDraft { current -> current.copy(waypoints = current.waypoints + waypoint) }
        }
    }

    fun removeRouteWaypoint(index: Int) {
        updateDraft { current ->
            if (index !in current.waypoints.indices) current else current.copy(
                waypoints = current.waypoints.filterIndexed { waypointIndex, _ -> waypointIndex != index },
            )
        }
    }

    fun moveRouteWaypoint(fromIndex: Int, toIndex: Int) {
        updateDraft { current ->
            if (fromIndex !in current.waypoints.indices || toIndex !in current.waypoints.indices || fromIndex == toIndex) {
                current
            } else {
                val reordered = current.waypoints.toMutableList()
                reordered.add(toIndex, reordered.removeAt(fromIndex))
                current.copy(waypoints = reordered)
            }
        }
    }

    fun updateManualRange(text: String) {
        mutableState.update { current ->
            current.copy(
                manualRangeText = text,
                error = null,
                isRouteStale = current.activeRoute != null,
                navigationMessage = null,
            )
        }
    }

    fun calculate() {
        val generation = ++calculationGeneration
        val current = mutableState.value
        val from = current.selectedFrom ?: return
        val routeEngine = engine ?: return
        val range = current.manualRangeText.trim().toDoubleOrNull()
        if (range == null) {
            mutableState.update { it.copy(error = ManualMaximumLyMustBeNumberUiMessage) }
            return
        }
        val profile = runCatching { JumpProfile.manual(range, "capital-manual") }.getOrElse {
            mutableState.update { it.copy(error = ManualMaximumLyMustBePositiveUiMessage) }
            return
        }
        val intent = NavigationIntent(from.id, current.waypoints.map(SolarSystem::id), current.selectedTo?.id)
        mutableState.update { it.copy(isCalculating = true, error = null, navigationMessage = null) }
        scope.launch {
            val outcome = withContext(calculationDispatcher) {
                CapitalNavigationPlanner(routeEngine).calculate(intent, profile)
            }
            if (generation != calculationGeneration) return@launch
            when (outcome) {
                is CapitalNavigationOutcome.Found -> mutableState.update {
                    it.copy(
                        isCalculating = false,
                        outcome = CapitalRouteOutcome.Found(outcome.route),
                        activeRoute = outcome.route,
                        routeSystemNames = outcome.route.systems.map { id -> systemsById[id]?.name ?: id.toString() },
                        calculatedWaypointSystemIds = intent.waypointSystemIds,
                        calculatedExplicitDestinationSystemId = intent.destinationSystemId,
                        isRouteStale = false,
                        navigationMessage = null,
                    )
                }
                is CapitalNavigationOutcome.InvalidIntent -> mutableState.update {
                    it.copy(
                        isCalculating = false,
                        isRouteStale = true,
                        navigationMessage = validationMessage(outcome.validation),
                    )
                }
                is CapitalNavigationOutcome.SegmentFailed -> mutableState.update {
                    it.copy(
                        isCalculating = false,
                        outcome = if (it.activeRoute == null) outcome.cause else it.outcome,
                        isRouteStale = true,
                        navigationMessage = segmentFailureMessage(outcome.segment),
                    )
                }
            }
        }
    }

    fun clear() {
        calculationGeneration++
        mutableState.update {
            it.copy(
                outcome = null,
                activeRoute = null,
                routeSystemNames = emptyList(),
                calculatedWaypointSystemIds = emptyList(),
                calculatedExplicitDestinationSystemId = null,
                isRouteStale = false,
                navigationMessage = null,
                error = null,
            )
        }
    }

    override fun planningSnapshot(): CapitalRoutePlanningSnapshot = mutableState.value.let { current ->
        CapitalRoutePlanningSnapshot(
            fromSystemId = current.selectedFrom?.id,
            toSystemId = current.selectedTo?.id,
            waypointSystemIds = current.waypoints.map(SolarSystem::id),
            manualRangeText = current.manualRangeText,
            calculated = current.outcome != null,
            outcome = current.outcome,
            activeRoute = current.activeRoute,
            routeSystemNames = current.routeSystemNames,
            calculatedWaypointSystemIds = current.calculatedWaypointSystemIds,
            calculatedExplicitDestinationSystemId = current.calculatedExplicitDestinationSystemId,
            isRouteStale = current.isRouteStale,
            navigationMessage = current.navigationMessage,
        )
    }

    override fun restorePlanningSnapshot(snapshot: CapitalRoutePlanningSnapshot) {
        calculationGeneration++
        fromSearchJob?.cancel()
        toSearchJob?.cancel()
        if (systemsById.isEmpty() || engine == null) {
            pendingPlanningSnapshot = snapshot
            mutableState.update {
                it.copy(
                    fromQuery = "",
                    toQuery = "",
                    selectedFrom = null,
                    selectedTo = null,
                    waypoints = emptyList(),
                    fromResults = emptyList(),
                    toResults = emptyList(),
                    manualRangeText = snapshot.manualRangeText,
                    outcome = null,
                    activeRoute = null,
                    routeSystemNames = emptyList(),
                    calculatedWaypointSystemIds = emptyList(),
                    calculatedExplicitDestinationSystemId = null,
                    isRouteStale = false,
                    navigationMessage = null,
                    error = null,
                )
            }
            return
        }
        applyPlanningSnapshot(snapshot)
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

    private fun applyPlanningSnapshot(snapshot: CapitalRoutePlanningSnapshot) {
        val from = snapshot.fromSystemId?.let(systemsById::get)
        val to = snapshot.toSystemId?.let(systemsById::get)
        val waypoints = snapshot.waypointSystemIds.mapNotNull(systemsById::get)
        mutableState.update {
            it.copy(
                isCalculating = false,
                fromQuery = from?.name.orEmpty(),
                toQuery = to?.name.orEmpty(),
                selectedFrom = from,
                selectedTo = to,
                waypoints = waypoints,
                fromResults = emptyList(),
                toResults = emptyList(),
                manualRangeText = snapshot.manualRangeText,
                outcome = snapshot.outcome,
                activeRoute = snapshot.activeRoute,
                routeSystemNames = snapshot.routeSystemNames,
                calculatedWaypointSystemIds = snapshot.calculatedWaypointSystemIds,
                calculatedExplicitDestinationSystemId = snapshot.calculatedExplicitDestinationSystemId,
                isRouteStale = snapshot.isRouteStale,
                navigationMessage = snapshot.navigationMessage,
                error = null,
            )
        }
    }

    private fun updateDraft(transform: (CapitalRouteUiState) -> CapitalRouteUiState) {
        mutableState.update { current ->
            val proposed = transform(current)
            if (proposed == current) return@update current
            val validation = proposed.navigationIntentOrNull()?.validate()
            if (validation is NavigationIntentValidation.AdjacentDuplicate) {
                current.copy(navigationMessage = validationMessage(validation))
            } else {
                proposed.copy(
                    isRouteStale = current.activeRoute != null,
                    navigationMessage = null,
                )
            }
        }
    }

    private fun CapitalRouteUiState.navigationIntentOrNull(): NavigationIntent? = selectedFrom?.let {
        NavigationIntent(it.id, waypoints.map(SolarSystem::id), selectedTo?.id)
    }

    private fun validationMessage(validation: NavigationIntentValidation): UiMessage = when (validation) {
        NavigationIntentValidation.Valid -> InvalidNavigationStopUiMessage
        NavigationIntentValidation.MissingTerminalStop -> MissingTerminalStopUiMessage
        NavigationIntentValidation.InvalidSystemId -> InvalidNavigationStopUiMessage
        is NavigationIntentValidation.AdjacentDuplicate ->
            AdjacentNavigationStopsUiMessage(systemsById[validation.systemId]?.name ?: validation.systemId.toString())
    }

    private fun segmentFailureMessage(segment: NavigationSegment): UiMessage = NavigationSegmentFailureUiMessage(
        fromRole = segment.fromRole.toUiRole(),
        fromSystemName = systemsById[segment.fromSystemId]?.name ?: segment.fromSystemId.toString(),
        toRole = segment.toRole.toUiRole(),
        toSystemName = systemsById[segment.toSystemId]?.name ?: segment.toSystemId.toString(),
    )
}

private fun NavigationStopRole.toUiRole(): NavigationStopUiRole = when (this) {
    NavigationStopRole.START -> NavigationStopUiRole.START
    NavigationStopRole.WAYPOINT -> NavigationStopUiRole.WAYPOINT
    NavigationStopRole.DESTINATION -> NavigationStopUiRole.DESTINATION
}
