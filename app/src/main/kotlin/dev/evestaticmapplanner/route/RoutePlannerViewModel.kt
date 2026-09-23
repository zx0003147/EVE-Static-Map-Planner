package dev.evestaticmapplanner.route

import dev.evestaticmapplanner.AppDiagnostics
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.ansiblex.AnsiblexAccessPolicy
import dev.evestaticmapplanner.core.ansiblex.normalizeAllianceId
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.route.NormalRouteEngine
import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.NavigationIntentValidation
import dev.evestaticmapplanner.core.route.NavigationSegment
import dev.evestaticmapplanner.core.route.NavigationStopRole
import dev.evestaticmapplanner.core.route.NormalNavigationOutcome
import dev.evestaticmapplanner.core.route.NormalNavigationPlanner
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteGraph
import dev.evestaticmapplanner.core.route.buildDesktopRouteGraph
import dev.evestaticmapplanner.core.route.RouteOptions
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportService
import dev.evestaticmapplanner.localization.AdjacentNavigationStopsUiMessage
import dev.evestaticmapplanner.localization.AnsiblexDataUnavailableUiMessage
import dev.evestaticmapplanner.localization.InvalidNavigationStopUiMessage
import dev.evestaticmapplanner.localization.MissingTerminalStopUiMessage
import dev.evestaticmapplanner.localization.NavigationSegmentFailureUiMessage
import dev.evestaticmapplanner.localization.NavigationStopUiRole
import dev.evestaticmapplanner.localization.UiMessage
import dev.evestaticmapplanner.localization.UnableToLoadRouteGraphUiMessage
import dev.evestaticmapplanner.localization.AnsiblexMessage
import dev.evestaticmapplanner.localization.AnsiblexUiMessage
import dev.evestaticmapplanner.wormhole.WormholeSessionStore
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
import java.nio.file.Path

data class NormalRoutePlanningSnapshot(
    val fromSystemId: Int? = null,
    val toSystemId: Int? = null,
    val useAnsiblex: Boolean = false,
    val useWormholes: Boolean = false,
    val calculated: Boolean = false,
    val waypointSystemIds: List<Int> = emptyList(),
    val routeOutcome: RouteCalculationOutcome? = null,
    val activeRoute: RouteResult? = null,
    val routeSystemNames: List<String> = emptyList(),
    val calculatedWaypointSystemIds: List<Int> = emptyList(),
    val calculatedExplicitDestinationSystemId: Int? = null,
    val isRouteStale: Boolean = false,
    val navigationMessage: UiMessage? = null,
)

interface NormalRoutePlanningPort {
    fun planningSnapshot(): NormalRoutePlanningSnapshot
    fun restorePlanningSnapshot(snapshot: NormalRoutePlanningSnapshot)
}

class RoutePlannerViewModel(
    private val staticMapRepository: StaticMapRepository,
    private val searchRepository: SystemSearchRepository,
    private val ansiblexRepository: AnsiblexRepository?,
    private val importService: AnsiblexImportService?,
    userDatabaseError: String?,
    private val wormholeSessionStore: WormholeSessionStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMillis: Long = 180,
    private val routeEngine: NormalRouteEngine = NormalRouteEngine(),
) : NormalRoutePlanningPort {
    private val navigationPlanner = NormalNavigationPlanner(routeEngine)
    private val mutableState = MutableStateFlow(
        RoutePlannerUiState(
            userDatabaseError = userDatabaseError?.let { AnsiblexDataUnavailableUiMessage },
            wormholeConnections = wormholeSessionStore.connections.value,
        ),
    )
    val state: StateFlow<RoutePlannerUiState> = mutableState.asStateFlow()

    private var staticData: StaticMapData? = null
    private var systemsById: Map<Int, SolarSystem> = emptyMap()
    private var currentAnsiblexConnections: List<AnsiblexConnection> = emptyList()
    private var currentWormholeConnections: List<WormholeConnection> = wormholeSessionStore.connections.value
    private var graph: RouteGraph? = null
    private var systemSearchJob: Job? = null
    private var fromSearchJob: Job? = null
    private var toSearchJob: Job? = null
    private var pendingPlanningSnapshot: NormalRoutePlanningSnapshot? = null

    init {
        observeWormholeConnections()
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

    fun setRouteStart(systemId: Int) {
        systemsById[systemId]?.let(::selectFrom)
    }

    fun setRouteDestination(systemId: Int) {
        systemsById[systemId]?.let(::selectTo)
    }

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
                val waypoint = reordered.removeAt(fromIndex)
                reordered.add(toIndex, waypoint)
                current.copy(waypoints = reordered)
            }
        }
    }

    fun setUseAnsiblex(enabled: Boolean) {
        mutableState.update { current ->
            current.copy(
                useAnsiblex = enabled && current.isAnsiblexAvailable,
                isRouteStale = current.activeRoute != null,
                navigationMessage = null,
            )
        }
    }

    fun setUseWormholes(enabled: Boolean) {
        mutableState.update { current ->
            current.copy(
                useWormholes = enabled,
                isRouteStale = current.activeRoute != null,
                navigationMessage = null,
            )
        }
    }

    fun setShowAnsiblexLayer(show: Boolean) {
        mutableState.update { it.copy(showAnsiblexLayer = show) }
    }

    fun setCurrentAllianceId(value: String?) {
        val normalized = normalizeAllianceId(value)
        if (mutableState.value.currentAllianceId == normalized) return
        mutableState.update { current -> current.copy(currentAllianceId = normalized) }
        if (staticData == null) return
        val graphResult = runCatching {
            rebuildGraph().getOrThrow()
        }
        if (graphResult.isFailure) {
            graphResult.exceptionOrNull()?.let { AppDiagnostics.warning("Ansiblex access graph rebuild failed", it) }
            handleAnsiblexUnavailable()
            return
        }
        mutableState.update { current ->
            val invalidatesRoute = current.activeRoute?.edges?.any { it.type == RouteEdgeType.ANSIBLEX } == true
            current.copy(
                routeOutcome = if (invalidatesRoute) null else current.routeOutcome,
                activeRoute = if (invalidatesRoute) null else current.activeRoute,
                routeSystemNames = if (invalidatesRoute) emptyList() else current.routeSystemNames,
                calculatedWaypointSystemIds = if (invalidatesRoute) emptyList() else current.calculatedWaypointSystemIds,
                calculatedExplicitDestinationSystemId = if (invalidatesRoute) {
                    null
                } else {
                    current.calculatedExplicitDestinationSystemId
                },
                isRouteStale = if (invalidatesRoute) false else current.isRouteStale,
                navigationMessage = null,
            )
        }
    }

    fun calculateRoute() {
        val current = mutableState.value
        val start = current.selectedFrom ?: return
        val intent = NavigationIntent(start.id, current.waypoints.map(SolarSystem::id), current.selectedTo?.id)
        val routeGraph = graph ?: return
        val outcome = navigationPlanner.calculate(
            routeGraph,
            intent,
            RouteOptions(
                useAnsiblex = current.useAnsiblex,
                useWormholes = current.useWormholes,
            ),
        )
        when (outcome) {
            is NormalNavigationOutcome.Found -> mutableState.update {
                it.copy(
                    routeOutcome = RouteCalculationOutcome.Found(outcome.route),
                    activeRoute = outcome.route,
                    routeSystemNames = outcome.route.systems.map { id -> systemsById[id]?.name ?: id.toString() },
                    calculatedWaypointSystemIds = intent.waypointSystemIds,
                    calculatedExplicitDestinationSystemId = intent.destinationSystemId,
                    isRouteStale = false,
                    navigationMessage = null,
                )
            }
            is NormalNavigationOutcome.InvalidIntent -> mutableState.update {
                it.copy(
                    isRouteStale = true,
                    navigationMessage = validationMessage(outcome.validation),
                )
            }
            is NormalNavigationOutcome.SegmentFailed -> mutableState.update {
                it.copy(
                    routeOutcome = if (it.activeRoute == null) outcome.cause else it.routeOutcome,
                    isRouteStale = true,
                    navigationMessage = segmentFailureMessage(outcome.segment),
                )
            }
        }
    }

    fun clearRoute() {
        mutableState.update {
            it.copy(
                routeOutcome = null,
                activeRoute = null,
                routeSystemNames = emptyList(),
                calculatedWaypointSystemIds = emptyList(),
                calculatedExplicitDestinationSystemId = null,
                isRouteStale = false,
                navigationMessage = null,
            )
        }
    }

    override fun planningSnapshot(): NormalRoutePlanningSnapshot = mutableState.value.let { current ->
        NormalRoutePlanningSnapshot(
            fromSystemId = current.selectedFrom?.id,
            toSystemId = current.selectedTo?.id,
            waypointSystemIds = current.waypoints.map(SolarSystem::id),
            useAnsiblex = current.useAnsiblex,
            useWormholes = current.useWormholes,
            calculated = current.routeOutcome != null,
            routeOutcome = current.routeOutcome,
            activeRoute = current.activeRoute,
            routeSystemNames = current.routeSystemNames,
            calculatedWaypointSystemIds = current.calculatedWaypointSystemIds,
            calculatedExplicitDestinationSystemId = current.calculatedExplicitDestinationSystemId,
            isRouteStale = current.isRouteStale,
            navigationMessage = current.navigationMessage,
        )
    }

    override fun restorePlanningSnapshot(snapshot: NormalRoutePlanningSnapshot) {
        systemSearchJob?.cancel()
        fromSearchJob?.cancel()
        toSearchJob?.cancel()
        if (systemsById.isEmpty() || graph == null) {
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
                    routeOutcome = null,
                    activeRoute = null,
                    routeSystemNames = emptyList(),
                    calculatedWaypointSystemIds = emptyList(),
                    calculatedExplicitDestinationSystemId = null,
                    isRouteStale = false,
                    navigationMessage = null,
                )
            }
            return
        }
        applyPlanningSnapshot(snapshot)
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
                    mutableState.update {
                        it.copy(
                            isImportBusy = false,
                            importError = AnsiblexUiMessage(
                                AnsiblexMessage.PREVIEW_FAILED,
                                technicalDetail = error.message,
                            ),
                        )
                    }
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
                        AnsiblexUiMessage(
                            AnsiblexMessage.IMPORT_APPLIED,
                            count = result.addedCount,
                            updatedCount = result.updatedCount,
                            removedCount = result.removedCount,
                        ),
                    )
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isImportBusy = false,
                            importError = AnsiblexUiMessage(
                                AnsiblexMessage.APPLY_FAILED,
                                technicalDetail = error.message,
                            ),
                        )
                    }
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
        ownerAllianceId: String? = null,
    ) {
        val repository = ansiblexRepository ?: return
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val fromSystem = resolveExact(from) ?: throw AnsiblexEndpointException(true, from)
                    val toSystem = resolveExact(to) ?: throw AnsiblexEndpointException(false, to)
                    repository.addManual(
                        AnsiblexDraft(
                            fromSystemId = fromSystem.id,
                            toSystemId = toSystem.id,
                            bidirectional = bidirectional,
                            displayName = displayName,
                            notes = notes,
                            ownerAllianceId = ownerAllianceId,
                        ),
                    )
                }
            }.onSuccess {
                refreshAnsiblex(AnsiblexUiMessage(AnsiblexMessage.MANUAL_ADDED))
            }.onFailure { error ->
                val message = when (error) {
                    is AnsiblexEndpointException -> AnsiblexUiMessage(
                        if (error.fromEndpoint) AnsiblexMessage.UNKNOWN_FROM else AnsiblexMessage.UNKNOWN_TO,
                        value = error.value,
                    )
                    else -> AnsiblexUiMessage(AnsiblexMessage.ADD_FAILED, technicalDetail = error.message)
                }
                mutableState.update { it.copy(managerMessage = message) }
            }
        }
    }

    fun setConnectionEnabled(id: String, enabled: Boolean) = mutateConnections {
        check(it.setEnabled(id, enabled)) { "Connection no longer exists" }
        AnsiblexUiMessage(if (enabled) AnsiblexMessage.ENABLED else AnsiblexMessage.DISABLED)
    }

    fun deleteConnection(id: String) = mutateConnections {
        check(it.delete(id)) { "Connection no longer exists" }
        AnsiblexUiMessage(AnsiblexMessage.DELETED)
    }

    fun clearImported() = mutateConnections {
        AnsiblexUiMessage(AnsiblexMessage.CLEARED_IMPORTED, count = it.clearImported())
    }

    fun clearAll() = mutateConnections {
        AnsiblexUiMessage(AnsiblexMessage.CLEARED_ALL, count = it.clearAll())
    }

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
                    val ansiblex = runCatching { ansiblexRepository?.getAll().orEmpty() }
                    data to ansiblex
                }
            }.onSuccess { (data, ansiblexResult) ->
                staticData = data
                systemsById = data.systems.associateBy(SolarSystem::id)
                currentAnsiblexConnections = ansiblexResult.getOrElse { emptyList() }
                val combinedGraphResult = rebuildGraph()
                val graphResult = if (combinedGraphResult.isFailure && currentAnsiblexConnections.isNotEmpty()) {
                    currentAnsiblexConnections = emptyList()
                    rebuildGraph()
                } else {
                    combinedGraphResult
                }
                val ansiblexFailure = ansiblexResult.exceptionOrNull()
                    ?: combinedGraphResult.exceptionOrNull()
                    ?: graphResult.exceptionOrNull()
                ansiblexFailure?.let { AppDiagnostics.warning("Ansiblex route data unavailable", it) }
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        userDatabaseError = it.userDatabaseError
                            ?: ansiblexFailure?.let { AnsiblexDataUnavailableUiMessage },
                        ansiblexConnections = if (
                            ansiblexResult.isSuccess && combinedGraphResult.isSuccess && graphResult.isSuccess
                        ) {
                            currentAnsiblexConnections
                        } else {
                            emptyList()
                        },
                        useAnsiblex = it.useAnsiblex && ansiblexResult.isSuccess && combinedGraphResult.isSuccess &&
                            graphResult.isSuccess,
                    )
                }
                pendingPlanningSnapshot?.let {
                    pendingPlanningSnapshot = null
                    applyPlanningSnapshot(it)
                }
            }.onFailure { error ->
                AppDiagnostics.warning("Normal route graph load failed", error)
                mutableState.update { it.copy(isLoading = false, error = UnableToLoadRouteGraphUiMessage) }
            }
        }
    }

    private fun applyPlanningSnapshot(snapshot: NormalRoutePlanningSnapshot) {
        val from = snapshot.fromSystemId?.let(systemsById::get)
        val to = snapshot.toSystemId?.let(systemsById::get)
        val waypoints = snapshot.waypointSystemIds.mapNotNull(systemsById::get)
        val restorableRoute = snapshot.activeRoute?.takeIf(::usesOnlyCurrentlyAccessibleAnsiblex)
        val routeRejected = snapshot.activeRoute != null && restorableRoute == null
        mutableState.update {
            it.copy(
                fromQuery = from?.name.orEmpty(),
                toQuery = to?.name.orEmpty(),
                selectedFrom = from,
                selectedTo = to,
                waypoints = waypoints,
                fromResults = emptyList(),
                toResults = emptyList(),
                useAnsiblex = snapshot.useAnsiblex && it.isAnsiblexAvailable,
                useWormholes = snapshot.useWormholes,
                routeOutcome = if (routeRejected) null else snapshot.routeOutcome,
                activeRoute = restorableRoute,
                routeSystemNames = if (routeRejected) emptyList() else snapshot.routeSystemNames,
                calculatedWaypointSystemIds = if (routeRejected) emptyList() else snapshot.calculatedWaypointSystemIds,
                calculatedExplicitDestinationSystemId = if (routeRejected) {
                    null
                } else {
                    snapshot.calculatedExplicitDestinationSystemId
                },
                isRouteStale = if (routeRejected) false else snapshot.isRouteStale,
                navigationMessage = if (routeRejected) null else snapshot.navigationMessage,
            )
        }
    }

    private fun usesOnlyCurrentlyAccessibleAnsiblex(route: RouteResult): Boolean {
        val accessibleConnectionIds = AnsiblexAccessPolicy.usableConnections(
            currentAnsiblexConnections,
            mutableState.value.currentAllianceId,
        ).mapTo(hashSetOf()) { "ansiblex:${it.id}" }
        return route.edges.asSequence()
            .filter { it.type == RouteEdgeType.ANSIBLEX }
            .all { it.connectionId.value in accessibleConnectionIds }
    }

    private fun updateDraft(transform: (RoutePlannerUiState) -> RoutePlannerUiState) {
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

    private fun RoutePlannerUiState.navigationIntentOrNull(): NavigationIntent? = selectedFrom?.let {
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

    private fun mutateConnections(block: (AnsiblexRepository) -> UiMessage) {
        val repository = ansiblexRepository ?: return
        scope.launch {
            runCatching { withContext(ioDispatcher) { block(repository) } }
                .onSuccess(::refreshAnsiblex)
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            managerMessage = AnsiblexUiMessage(
                                AnsiblexMessage.UPDATE_FAILED,
                                technicalDetail = error.message,
                            ),
                        )
                    }
                }
        }
    }

    private fun refreshAnsiblex(message: UiMessage) {
        val repository = ansiblexRepository ?: return
        scope.launch {
            val connectionsResult = runCatching { withContext(ioDispatcher) { repository.getAll() } }
            connectionsResult.fold(
                onSuccess = { connections ->
                    currentAnsiblexConnections = connections
                    val graphResult = rebuildGraph()
                    if (graphResult.isSuccess) {
                        mutableState.update {
                            it.copy(
                                ansiblexConnections = connections,
                                routeOutcome = null,
                                activeRoute = null,
                                routeSystemNames = emptyList(),
                                calculatedWaypointSystemIds = emptyList(),
                                calculatedExplicitDestinationSystemId = null,
                                isRouteStale = false,
                                navigationMessage = null,
                                importPreview = null,
                                importError = null,
                                isImportBusy = false,
                                managerMessage = message,
                            )
                        }
                    } else {
                        graphResult.exceptionOrNull()?.let {
                            AppDiagnostics.warning("Ansiblex route graph rebuild failed", it)
                        }
                        handleAnsiblexUnavailable()
                    }
                },
                onFailure = { error ->
                    AppDiagnostics.warning("Ansiblex data refresh failed", error)
                    handleAnsiblexUnavailable()
                },
            )
        }
    }

    private fun handleAnsiblexUnavailable() {
        currentAnsiblexConnections = emptyList()
        rebuildGraph()
        mutableState.update {
            it.copy(
                userDatabaseError = AnsiblexDataUnavailableUiMessage,
                ansiblexConnections = emptyList(),
                useAnsiblex = false,
                isImportBusy = false,
            )
        }
    }

    private fun observeWormholeConnections() {
        scope.launch {
            wormholeSessionStore.connections.collect { connections ->
                currentWormholeConnections = connections
                rebuildGraph()
                mutableState.update { current ->
                    val routeInvalidated = current.activeRoute
                        ?.edges
                        ?.asSequence()
                        ?.filter { it.type == RouteEdgeType.WORMHOLE }
                        ?.map { it.connectionId.value }
                        ?.any { usedId -> connections.none { it.id == usedId } }
                        ?: false
                    current.copy(
                        wormholeConnections = connections,
                        routeOutcome = if (routeInvalidated) null else current.routeOutcome,
                        activeRoute = if (routeInvalidated) null else current.activeRoute,
                        routeSystemNames = if (routeInvalidated) emptyList() else current.routeSystemNames,
                        calculatedWaypointSystemIds = if (routeInvalidated) {
                            emptyList()
                        } else {
                            current.calculatedWaypointSystemIds
                        },
                        calculatedExplicitDestinationSystemId = if (routeInvalidated) {
                            null
                        } else {
                            current.calculatedExplicitDestinationSystemId
                        },
                        isRouteStale = if (routeInvalidated) false else current.isRouteStale,
                    )
                }
            }
        }
    }

    private fun rebuildGraph(): Result<RouteGraph> {
        val data = staticData ?: return Result.failure(IllegalStateException("Static map data is not loaded"))
        return runCatching {
            buildDesktopRouteGraph(
                data,
                AnsiblexAccessPolicy.usableConnections(
                    currentAnsiblexConnections,
                    mutableState.value.currentAllianceId,
                ),
                currentWormholeConnections,
            )
        }.onSuccess { graph = it }
    }
}

private class AnsiblexEndpointException(
    val fromEndpoint: Boolean,
    val value: String,
) : IllegalArgumentException()

private fun NavigationStopRole.toUiRole(): NavigationStopUiRole = when (this) {
    NavigationStopRole.START -> NavigationStopUiRole.START
    NavigationStopRole.WAYPOINT -> NavigationStopUiRole.WAYPOINT
    NavigationStopRole.DESTINATION -> NavigationStopUiRole.DESTINATION
}
