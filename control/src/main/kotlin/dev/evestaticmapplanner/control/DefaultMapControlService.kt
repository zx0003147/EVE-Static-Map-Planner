package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.mission.MissionRegistry
import dev.evestaticmapplanner.control.mission.MissionRegistryFailure
import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.CapitalNavigationOutcome
import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.NavigationIntentValidation
import dev.evestaticmapplanner.core.route.NavigationStopRole
import dev.evestaticmapplanner.core.route.NormalNavigationOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class DefaultMapControlService(
    private val systemReadPort: SystemReadPort,
    private val routePlanningPort: RoutePlanningPort,
    private val jumpPlanningPort: JumpPlanningPort,
    private val viewportControlPort: ViewportControlPort,
    private val missionRenderStatePort: MissionRenderStatePort,
    scope: CoroutineScope,
    private val registry: MissionRegistry = MissionRegistry(),
    private val savedMarkerControlPort: SavedMarkerControlPort = DeniedSavedMarkerControlPort,
    private val planningViewControlPort: PlanningViewControlPort = SinglePlanningViewControlPort,
    private val wormholeControlPort: WormholeControlPort = UnavailableWormholeControlPort,
    wormholeConnectionIds: Flow<Set<String>>? = null,
) : MapControlService, AutoCloseable {
    private val dispatcher = MapControlCommandDispatcher(scope)
    private val idempotency = IdempotencyCache()
    private val expensiveQueries = Semaphore(ControlLimits.MAX_CONCURRENT_EXPENSIVE_QUERIES)
    private var wormholeObserver: Job? = null

    init {
        scope.launch {
            registry.retainViews(planningViewControlPort.listViews().mapTo(mutableSetOf(), PlanningViewDto::viewId))
            publishMissions()
        }
        wormholeConnectionIds?.let { connectionIds ->
            wormholeObserver = scope.launch {
                var previousIds: Set<String>? = null
                connectionIds.collect { currentIds ->
                    val removedIds = previousIds?.minus(currentIds).orEmpty()
                    previousIds = currentIds
                    if (removedIds.isNotEmpty() && registry.invalidateNormalRoutesUsingWormholes(removedIds) > 0) {
                        publishMissions()
                    }
                }
            }
        }
    }

    override suspend fun searchSystems(request: SearchSystemsRequest): ControlResult<List<SystemSummaryDto>> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            validateText("query", request.query, ControlLimits.MAX_SEARCH_QUERY_CODE_POINTS, allowBlank = false)
            if (request.limit !in 1..ControlLimits.MAX_SEARCH_RESULTS) invalid("Search result limit must be between 1 and 20")
            systemReadPort.searchSystems(request.query.trim(), request.limit)
        }

    override suspend fun getSystemInfo(request: GetSystemInfoRequest): ControlResult<SystemInfoDto> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            validateSystemId(request.systemId)
            systemReadPort.getSystemInfo(request.systemId)
                ?: throw ControlFailure(ControlErrorCode.NOT_FOUND, "Solar system was not found")
        }

    override suspend fun getSystemMarkers(request: GetSystemMarkersRequest): ControlResult<SystemMarkersDto> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            validateSystemId(request.systemId)
            val savedMarker = savedMarkerControlPort.getSystemMarker(request.systemId)
            val missionMarkers = registry.active(planningViewControlPort.currentView().viewId)
                .flatMap(Mission::markers)
                .filter { it.systemId == request.systemId }
                .map { marker ->
                    MissionMarkerSummaryDto(
                        missionId = marker.missionId,
                        markerId = marker.markerId,
                        systemId = marker.systemId,
                        role = marker.role,
                        label = marker.label,
                        notes = marker.notes,
                        color = marker.color,
                    )
                }
            SystemMarkersDto(request.systemId, savedMarker, missionMarkers)
        }

    override suspend fun calculateNormalRoute(request: CalculateNormalRouteRequest): ControlResult<NormalRouteDto> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            validateSystemId(request.startSystemId)
            request.destinationSystemId?.let(::validateSystemId)
            request.waypointSystemIds.forEach(::validateSystemId)
            val intent = request.navigationIntent().validated()
            expensiveQueries.withPermit {
                routePlanningPort.calculateNormalRoute(
                    intent,
                    request.useAnsiblex,
                    request.useWormholes,
                ).requireRoute().toDto(intent)
            }
        }

    override suspend fun listWormholes(request: ListWormholesRequest): ControlResult<List<WormholeConnectionDto>> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            val result = mutableListOf<WormholeConnectionDto>()
            val ordered = wormholeControlPort.listWormholes().sortedWith(
                compareBy(WormholeConnectionDto::firstSystemId, WormholeConnectionDto::secondSystemId),
            )
            for (connection in ordered) {
                result += withSystemNames(connection)
            }
            result
        }

    override suspend fun calculateCapitalRoute(request: CalculateCapitalRouteRequest): ControlResult<CapitalRouteDto> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            validateSystemId(request.startSystemId)
            request.destinationSystemId?.let(::validateSystemId)
            request.waypointSystemIds.forEach(::validateSystemId)
            validateRange(request.effectiveRangeLy)
            val intent = request.navigationIntent().validated()
            expensiveQueries.withPermit {
                routePlanningPort.calculateCapitalRoute(
                    intent,
                    request.effectiveRangeLy,
                ).requireRoute().toDto(intent)
            }
        }

    override suspend fun listViews(request: ListViewsRequest): ControlResult<List<PlanningViewDto>> = query(request.requestId) {
        validateRequestId(request.requestId)
        planningViewControlPort.listViews()
    }

    override suspend fun getCurrentView(request: GetCurrentViewRequest): ControlResult<PlanningViewDto> = query(request.requestId) {
        validateRequestId(request.requestId)
        planningViewControlPort.currentView()
    }

    override suspend fun getActiveMissions(request: GetActiveMissionsRequest): ControlResult<List<MissionSummaryDto>> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            registry.active(resolveViewId(request.viewId)).map(Mission::toSummary)
        }

    override suspend fun getMission(request: GetMissionRequest): ControlResult<Mission> = query(request.requestId) {
        validateRequestId(request.requestId)
        registry.get(request.missionId)
    }

    override suspend fun beginMission(command: BeginMissionCommand): ControlResult<MissionSummaryDto> = mutation(
        "beginMission",
        command,
        listOf(command.title, command.viewId),
    ) {
        validateText("title", command.title, ControlLimits.MAX_TITLE_CODE_POINTS, allowBlank = false)
        val mission = registry.begin(command.title.trim(), resolveViewId(command.viewId))
        publishMissions()
        success(command.requestId, mission.toSummary(), mission.revision)
    }

    override suspend fun createView(command: CreateViewCommand): ControlResult<PlanningViewDto> = mutation(
        "createView", command, listOf(command.label),
    ) {
        validateOptionalText("label", command.label, ControlLimits.MAX_LABEL_CODE_POINTS)
        val view = planningViewControlPort.createView(command.label)
        publishMissions()
        success(command.requestId, view)
    }

    override suspend fun renameView(command: RenameViewCommand): ControlResult<PlanningViewDto> = mutation(
        "renameView", command, listOf(command.viewId, command.label),
    ) {
        validateText("label", command.label, ControlLimits.MAX_LABEL_CODE_POINTS, allowBlank = false)
        success(command.requestId, planningViewControlPort.renameView(command.viewId, command.label.trim()))
    }

    override suspend fun switchView(command: SwitchViewCommand): ControlResult<PlanningViewDto> = mutation(
        "switchView", command, listOf(command.viewId),
    ) {
        val view = planningViewControlPort.switchView(command.viewId)
        publishMissions()
        success(command.requestId, view)
    }

    override suspend fun deleteView(command: DeleteViewCommand): ControlResult<PlanningViewDto> = mutation(
        "deleteView", command, listOf(command.viewId),
    ) {
        val view = planningViewControlPort.deleteView(command.viewId)
        registry.clearView(command.viewId)
        publishMissions()
        success(command.requestId, view)
    }

    override suspend fun createSavedMarker(
        command: CreateSavedMarkerCommand,
    ): ControlResult<CreateSavedMarkerReceipt> = mutation(
        "createSavedMarker",
        command,
        listOf(
            command.systemId,
            command.name,
            command.notes,
            command.color,
            command.tags.distinctBy { it.key }.map { it.key },
        ),
    ) {
        validateSystemId(command.systemId)
        validateOptionalText("name", command.name, ControlLimits.MAX_LABEL_CODE_POINTS)
        validateOptionalText("notes", command.notes, ControlLimits.MAX_NOTES_CODE_POINTS)
        val marker = savedMarkerControlPort.createSavedMarker(
            SavedMarkerCreatePortRequest(
                systemId = command.systemId,
                name = command.name,
                notes = command.notes,
                color = command.color,
                tags = command.tags.distinctBy { it.key },
            ),
        )
        success(command.requestId, CreateSavedMarkerReceipt(marker))
    }

    override suspend fun focusSystem(command: FocusSystemCommand): ControlResult<SystemSummaryDto> = mutation(
        "focusSystem",
        command,
        listOf(command.systemId),
    ) {
        validateSystemId(command.systemId)
        val system = systemReadPort.getSystemInfo(command.systemId)?.system
            ?: throw ControlFailure(ControlErrorCode.NOT_FOUND, "Solar system was not found")
        viewportControlPort.focusSystem(command.systemId).requireCompleted()
        success(command.requestId, system)
    }

    override suspend fun createWormhole(command: CreateWormholeCommand): ControlResult<CreateWormholeReceipt> = mutation(
        "createWormhole",
        command,
        listOf(
            minOf(command.fromSystemId, command.toSystemId),
            maxOf(command.fromSystemId, command.toSystemId),
        ),
    ) {
        validateSystemId(command.fromSystemId)
        validateSystemId(command.toSystemId)
        if (command.fromSystemId == command.toSystemId) invalid("Wormhole endpoints must be different solar systems")
        requireExistingSystem(command.fromSystemId)
        requireExistingSystem(command.toSystemId)
        val result = wormholeControlPort.createWormhole(command.fromSystemId, command.toSystemId)
        success(
            command.requestId,
            CreateWormholeReceipt(
                connection = withSystemNames(result.connection),
                created = result.status == WormholeCreateStatus.CREATED,
                status = result.status.name.lowercase(),
            ),
        )
    }

    override suspend fun showNormalRoute(command: ShowNormalRouteCommand): ControlResult<MissionRouteReceipt> = mutation(
        "showNormalRoute",
        command,
        listOf(
            command.missionId,
            command.startSystemId,
            command.destinationSystemId,
            command.waypointSystemIds,
            command.useAnsiblex,
            command.useWormholes,
        ),
    ) {
        validateSystemId(command.startSystemId)
        command.destinationSystemId?.let(::validateSystemId)
        command.waypointSystemIds.forEach(::validateSystemId)
        val intent = command.navigationIntent().validated()
        ensureRouteCapacity(command.missionId)
        val route = routePlanningPort.calculateNormalRoute(
            intent,
            command.useAnsiblex,
            command.useWormholes,
        ).requireRoute()
        val owned = registry.addNormalRoute(command.missionId, route, intent)
        publishMissions()
        val revision = registry.get(command.missionId).revision
        success(
            command.requestId,
            MissionRouteReceipt(command.missionId, owned.routeId, AnyRouteDto.Normal(route.toDto(intent))),
            revision,
        )
    }

    override suspend fun showCapitalRoute(command: ShowCapitalRouteCommand): ControlResult<MissionRouteReceipt> = mutation(
        "showCapitalRoute",
        command,
        listOf(
            command.missionId,
            command.startSystemId,
            command.destinationSystemId,
            command.waypointSystemIds,
            command.effectiveRangeLy,
        ),
    ) {
        validateSystemId(command.startSystemId)
        command.destinationSystemId?.let(::validateSystemId)
        command.waypointSystemIds.forEach(::validateSystemId)
        validateRange(command.effectiveRangeLy)
        val intent = command.navigationIntent().validated()
        ensureRouteCapacity(command.missionId)
        val route = routePlanningPort.calculateCapitalRoute(
            intent,
            command.effectiveRangeLy,
        ).requireRoute()
        val owned = registry.addCapitalRoute(command.missionId, route, intent)
        publishMissions()
        val revision = registry.get(command.missionId).revision
        success(
            command.requestId,
            MissionRouteReceipt(command.missionId, owned.routeId, AnyRouteDto.Capital(route.toDto(intent))),
            revision,
        )
    }

    override suspend fun removeMissionRoute(command: RemoveMissionRouteCommand) = mutationReceipt(
        "removeMissionRoute", command, listOf(command.missionId, command.routeId),
    ) { registry.removeRoute(command.missionId, command.routeId) }

    override suspend fun clearMissionRoutes(command: ClearMissionRoutesCommand) = mutationReceipt(
        "clearMissionRoutes", command, listOf(command.missionId),
    ) { registry.clearRoutes(command.missionId) }

    override suspend fun showJumpRange(command: ShowJumpRangeCommand): ControlResult<MissionJumpRangeReceipt> = mutation(
        "showJumpRange",
        command,
        listOf(command.missionId, command.originSystemId, command.effectiveRangeLy, command.label),
    ) {
        validateSystemId(command.originSystemId)
        validateRange(command.effectiveRangeLy)
        validateOptionalText("label", command.label, ControlLimits.MAX_LABEL_CODE_POINTS)
        val mission = registry.get(command.missionId)
        if (mission.jumpRanges.size >= ControlLimits.MAX_JUMP_RANGES_PER_MISSION) {
            limit("The mission jump range limit has been reached")
        }
        val range = jumpPlanningPort.calculateJumpRange(command.originSystemId, command.effectiveRangeLy)
        when (val verdict = range.originVerdict) {
            EligibilityVerdict.Eligible -> Unit
            is EligibilityVerdict.Ineligible -> invalid("Origin solar system is not eligible for this jump calculation")
            is EligibilityVerdict.Unknown -> invalid("Origin solar system eligibility is unknown")
        }
        val owned = registry.addJumpRange(
            command.missionId,
            command.originSystemId,
            range.profile,
            range.reachableSystemIds,
            command.label?.trim()?.takeIf(String::isNotEmpty),
        )
        publishMissions()
        val revision = registry.get(command.missionId).revision
        success(
            command.requestId,
            MissionJumpRangeReceipt(
                command.missionId,
                owned.jumpRangeId,
                owned.originSystemId,
                owned.profile.maxRangeLy,
                owned.reachableSystemIds.size,
            ),
            revision,
        )
    }

    override suspend fun removeJumpRange(command: RemoveJumpRangeCommand) = mutationReceipt(
        "removeJumpRange", command, listOf(command.missionId, command.jumpRangeId),
    ) { registry.removeJumpRange(command.missionId, command.jumpRangeId) }

    override suspend fun clearMissionJumpRanges(command: ClearMissionJumpRangesCommand) = mutationReceipt(
        "clearMissionJumpRanges", command, listOf(command.missionId),
    ) { registry.clearJumpRanges(command.missionId) }

    override suspend fun addMissionMarker(command: AddMissionMarkerCommand): ControlResult<MissionMarkerReceipt> = mutation(
        "addMissionMarker",
        command,
        listOf(command.missionId, command.systemId, command.role, command.label, command.notes, command.colorOverride),
    ) {
        validateSystemId(command.systemId)
        validateOptionalText("label", command.label, ControlLimits.MAX_LABEL_CODE_POINTS)
        validateOptionalText("notes", command.notes, ControlLimits.MAX_NOTES_CODE_POINTS)
        val mission = registry.get(command.missionId)
        if (mission.markers.size >= ControlLimits.MAX_MARKERS_PER_MISSION) {
            limit("The mission marker limit has been reached")
        }
        if (systemReadPort.getSystemInfo(command.systemId) == null) {
            throw ControlFailure(ControlErrorCode.NOT_FOUND, "Solar system was not found")
        }
        val marker = registry.addMarker(
            command.missionId,
            command.systemId,
            command.role,
            command.label.normalized(),
            command.notes.normalized(),
            command.colorOverride,
        )
        publishMissions()
        val revision = registry.get(command.missionId).revision
        success(
            command.requestId,
            MissionMarkerReceipt(command.missionId, marker.markerId, marker.systemId, marker.role),
            revision,
        )
    }

    override suspend fun removeMissionMarker(command: RemoveMissionMarkerCommand) = mutationReceipt(
        "removeMissionMarker", command, listOf(command.missionId, command.markerId),
    ) { registry.removeMarker(command.missionId, command.markerId) }

    override suspend fun clearMissionMarkers(command: ClearMissionMarkersCommand) = mutationReceipt(
        "clearMissionMarkers", command, listOf(command.missionId),
    ) { registry.clearMarkers(command.missionId) }

    override suspend fun fitMission(command: FitMissionCommand): ControlResult<MissionMutationReceipt> = mutation(
        "fitMission", command, listOf(command.missionId),
    ) {
        val mission = registry.get(command.missionId)
        if (mission.visualFitSystemIds.isEmpty()) invalid("Mission has no visual solar systems")
        viewportControlPort.fitSystems(mission.visualFitSystemIds).requireCompleted()
        success(command.requestId, MissionMutationReceipt(command.missionId), mission.revision)
    }

    override suspend fun clearMission(command: ClearMissionCommand): ControlResult<MissionMutationReceipt> = mutation(
        "clearMission", command, listOf(command.missionId),
    ) {
        registry.clearMission(command.missionId)
        publishMissions()
        success(command.requestId, MissionMutationReceipt(command.missionId))
    }

    override fun close() {
        wormholeObserver?.cancel()
        dispatcher.close()
    }

    private suspend fun mutationReceipt(
        operation: String,
        command: MutationCommand,
        canonicalInput: Any,
        block: () -> Mission,
    ): ControlResult<MissionMutationReceipt> = mutation(operation, command, canonicalInput) {
        val mission = block()
        publishMissions()
        success(command.requestId, MissionMutationReceipt(mission.missionId), mission.revision)
    }

    private suspend fun <T> mutation(
        operation: String,
        command: MutationCommand,
        canonicalInput: Any,
        block: suspend () -> ControlResult<T>,
    ): ControlResult<T> {
        val metadataFailure = runCatching {
            validateRequestId(command.requestId)
            validateText("idempotencyKey", command.idempotencyKey, 120, allowBlank = false)
        }.exceptionOrNull()
        if (metadataFailure != null) return failure(command.requestId, metadataFailure)

        return when (val dispatched = dispatcher.dispatch {
            when (val lookup = idempotency.lookup(operation, command.idempotencyKey, canonicalInput)) {
                is IdempotencyLookup.Hit -> @Suppress("UNCHECKED_CAST") (lookup.result as ControlResult<T>)
                IdempotencyLookup.Conflict -> failure(
                    command.requestId,
                    ControlFailure(ControlErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency key was reused with different input"),
                )
                IdempotencyLookup.Miss -> {
                    val result = runCatching { block() }.getOrElse { failure(command.requestId, it) }
                    idempotency.put(operation, command.idempotencyKey, canonicalInput, result)
                    result
                }
            }
        }) {
            is DispatchOutcome.Completed -> dispatched.value
            DispatchOutcome.QueueFull -> failure(
                command.requestId,
                ControlFailure(ControlErrorCode.RATE_LIMITED, "Control command queue is full"),
            )
        }
    }

    private suspend fun <T> query(requestId: String, block: suspend () -> T): ControlResult<T> =
        runCatching { success(requestId, block()) }.getOrElse { failure(requestId, it) }

    private suspend fun publishMissions() = missionRenderStatePort.publish(registry.active())

    private suspend fun resolveViewId(requested: String?): String {
        if (requested == null) return planningViewControlPort.currentView().viewId
        validateText("viewId", requested, ControlLimits.MAX_LABEL_CODE_POINTS, allowBlank = false)
        return planningViewControlPort.listViews().firstOrNull { it.viewId == requested }?.viewId
            ?: throw ControlFailure(ControlErrorCode.NOT_FOUND, "View was not found")
    }

    private fun ensureRouteCapacity(missionId: dev.evestaticmapplanner.control.mission.MissionId) {
        if (registry.get(missionId).routes.size >= ControlLimits.MAX_ROUTES_PER_MISSION) {
            limit("The mission route limit has been reached")
        }
    }

    private suspend fun requireExistingSystem(systemId: Int): SystemSummaryDto =
        systemReadPort.getSystemInfo(systemId)?.system
            ?: throw ControlFailure(ControlErrorCode.SYSTEM_NOT_FOUND, "Solar system was not found")

    private suspend fun withSystemNames(connection: WormholeConnectionDto): WormholeConnectionDto = connection.copy(
        firstSystemName = requireExistingSystem(connection.firstSystemId).name,
        secondSystemName = requireExistingSystem(connection.secondSystemId).name,
    )
}

class ControlPortFailure(val code: ControlErrorCode, override val message: String) : RuntimeException(message)

private class ControlFailure(val code: ControlErrorCode, override val message: String) : RuntimeException(message)

private fun validateRequestId(value: String) = validateText("requestId", value, 120, allowBlank = false)

private fun validateSystemId(systemId: Int) {
    if (systemId <= 0) invalid("Solar system ID must be positive")
}

private fun validateRange(value: Double) {
    if (!value.isFinite() || value <= 0.0 || value > ControlLimits.MAX_EFFECTIVE_RANGE_LY) {
        invalid("Effective range must be greater than 0 and at most 20 LY")
    }
}

private fun validateOptionalText(name: String, value: String?, maxCodePoints: Int) {
    if (value != null) validateText(name, value, maxCodePoints, allowBlank = true)
}

private fun validateText(name: String, value: String, maxCodePoints: Int, allowBlank: Boolean) {
    if (!allowBlank && value.isBlank()) invalid("$name must not be blank")
    if (value.codePointCount(0, value.length) > maxCodePoints) invalid("$name exceeds its length limit")
}

private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun invalid(message: String): Nothing = throw ControlFailure(ControlErrorCode.INVALID_ARGUMENT, message)
private fun limit(message: String): Nothing = throw ControlFailure(ControlErrorCode.MISSION_LIMIT_EXCEEDED, message)

private fun ViewportOperationOutcome.requireCompleted() {
    when (this) {
        ViewportOperationOutcome.COMPLETED -> Unit
        ViewportOperationOutcome.NOT_FOUND -> throw ControlFailure(ControlErrorCode.NOT_FOUND, "Solar system was not found")
        ViewportOperationOutcome.APP_NOT_READY -> throw ControlFailure(ControlErrorCode.APP_NOT_READY, "Map is not ready")
    }
}

private fun RouteCalculationOutcome.requireRoute(): RouteResult = when (this) {
    is RouteCalculationOutcome.Found -> route
    is RouteCalculationOutcome.SameSystem -> route
    is RouteCalculationOutcome.Unreachable -> throw ControlFailure(ControlErrorCode.ROUTE_NOT_FOUND, "No normal route was found")
    is RouteCalculationOutcome.InvalidEndpoint -> throw ControlFailure(ControlErrorCode.NOT_FOUND, "A route endpoint was not found")
}

private fun CapitalRouteOutcome.requireRoute(): CapitalRouteResult = when (this) {
    is CapitalRouteOutcome.Found -> route
    is CapitalRouteOutcome.SameSystem -> route
    is CapitalRouteOutcome.Unreachable -> throw ControlFailure(ControlErrorCode.ROUTE_NOT_FOUND, "No capital route was found")
    is CapitalRouteOutcome.InvalidEndpoint -> throw ControlFailure(ControlErrorCode.NOT_FOUND, "A route endpoint was not found")
    is CapitalRouteOutcome.IneligibleEndpoint -> throw ControlFailure(
        ControlErrorCode.INVALID_ARGUMENT,
        "A capital route endpoint is not eligible",
    )
}

private fun NormalNavigationOutcome.requireRoute(): RouteResult = when (this) {
    is NormalNavigationOutcome.Found -> route
    is NormalNavigationOutcome.InvalidIntent -> throw ControlFailure(
        ControlErrorCode.INVALID_ARGUMENT,
        validation.controlMessage(),
    )
    is NormalNavigationOutcome.SegmentFailed -> throw ControlFailure(
        if (cause is RouteCalculationOutcome.InvalidEndpoint) ControlErrorCode.NOT_FOUND else ControlErrorCode.ROUTE_NOT_FOUND,
        "Unable to calculate ${segment.controlLabel()}",
    )
}

private fun CapitalNavigationOutcome.requireRoute(): CapitalRouteResult = when (this) {
    is CapitalNavigationOutcome.Found -> route
    is CapitalNavigationOutcome.InvalidIntent -> throw ControlFailure(
        ControlErrorCode.INVALID_ARGUMENT,
        validation.controlMessage(),
    )
    is CapitalNavigationOutcome.SegmentFailed -> throw ControlFailure(
        when (cause) {
            is CapitalRouteOutcome.InvalidEndpoint -> ControlErrorCode.NOT_FOUND
            is CapitalRouteOutcome.IneligibleEndpoint -> ControlErrorCode.INVALID_ARGUMENT
            else -> ControlErrorCode.ROUTE_NOT_FOUND
        },
        "Unable to calculate ${segment.controlLabel()}",
    )
}

private fun RouteResult.toDto(intent: NavigationIntent? = null) = NormalRouteDto(
    startSystemId,
    destinationSystemId,
    systems,
    totalJumps,
    stargateJumps,
    ansiblexJumps,
    wormholeJumps,
    intent?.waypointSystemIds.orEmpty(),
    if (intent == null) destinationSystemId else intent.destinationSystemId,
)

private fun CapitalRouteResult.toDto(intent: NavigationIntent? = null) = CapitalRouteDto(
    startSystemId,
    destinationSystemId,
    profile.maxRangeLy,
    systems,
    legs.map { CapitalRouteLegDto(it.fromSystemId, it.toSystemId, it.distanceLy) },
    totalJumps,
    totalDistanceLy,
    intent?.waypointSystemIds.orEmpty(),
    if (intent == null) destinationSystemId else intent.destinationSystemId,
)

private fun CalculateNormalRouteRequest.navigationIntent() =
    NavigationIntent(startSystemId, waypointSystemIds, destinationSystemId)

private fun CalculateCapitalRouteRequest.navigationIntent() =
    NavigationIntent(startSystemId, waypointSystemIds, destinationSystemId)

private fun ShowNormalRouteCommand.navigationIntent() =
    NavigationIntent(startSystemId, waypointSystemIds, destinationSystemId)

private fun ShowCapitalRouteCommand.navigationIntent() =
    NavigationIntent(startSystemId, waypointSystemIds, destinationSystemId)

private fun NavigationIntent.validated(): NavigationIntent {
    when (val validation = validate()) {
        NavigationIntentValidation.Valid -> return this
        else -> throw ControlFailure(ControlErrorCode.INVALID_ARGUMENT, validation.controlMessage())
    }
}

private fun NavigationIntentValidation.controlMessage(): String = when (this) {
    NavigationIntentValidation.Valid -> "Navigation intent is valid"
    NavigationIntentValidation.MissingTerminalStop -> "A Destination or at least one ordered Waypoint is required"
    NavigationIntentValidation.InvalidSystemId -> "Every navigation stop must use a positive solar system ID"
    is NavigationIntentValidation.AdjacentDuplicate ->
        "Adjacent navigation stops cannot use the same solar system ID ($systemId)"
}

private fun dev.evestaticmapplanner.core.route.NavigationSegment.controlLabel(): String =
    "${fromRole.controlName()} $fromSystemId -> ${toRole.controlName()} $toSystemId"

private fun NavigationStopRole.controlName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

private fun Mission.toSummary() = MissionSummaryDto(
    missionId,
    title,
    createdAt.toEpochMilli(),
    revision,
    routes.size,
    jumpRanges.size,
    markers.size,
    referencedSystemIds.size,
    viewId,
)

private fun <T> success(requestId: String, value: T, revision: Long? = null): ControlResult.Success<T> =
    ControlResult.Success(requestId, value, revision)

private fun failure(requestId: String, throwable: Throwable): ControlResult.Failure {
    val error = when (throwable) {
        is ControlFailure -> ControlError(throwable.code, throwable.message)
        is ControlPortFailure -> ControlError(throwable.code, throwable.message)
        is MissionRegistryFailure -> ControlError(throwable.code, throwable.message)
        else -> ControlError(ControlErrorCode.INTERNAL_ERROR, "The control operation failed")
    }
    return ControlResult.Failure(requestId.take(120), error)
}
