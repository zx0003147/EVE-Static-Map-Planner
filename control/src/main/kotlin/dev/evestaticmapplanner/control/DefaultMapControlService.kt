package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.mission.MissionRegistry
import dev.evestaticmapplanner.control.mission.MissionRegistryFailure
import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteResult
import kotlinx.coroutines.CoroutineScope
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
) : MapControlService, AutoCloseable {
    private val dispatcher = MapControlCommandDispatcher(scope)
    private val idempotency = IdempotencyCache()
    private val expensiveQueries = Semaphore(ControlLimits.MAX_CONCURRENT_EXPENSIVE_QUERIES)

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

    override suspend fun calculateNormalRoute(request: CalculateNormalRouteRequest): ControlResult<NormalRouteDto> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            validateSystemId(request.startSystemId)
            validateSystemId(request.destinationSystemId)
            expensiveQueries.withPermit {
                routePlanningPort.calculateNormalRoute(
                    request.startSystemId,
                    request.destinationSystemId,
                    request.useAnsiblex,
                ).requireRoute().toDto()
            }
        }

    override suspend fun calculateCapitalRoute(request: CalculateCapitalRouteRequest): ControlResult<CapitalRouteDto> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            validateSystemId(request.startSystemId)
            validateSystemId(request.destinationSystemId)
            validateRange(request.effectiveRangeLy)
            expensiveQueries.withPermit {
                routePlanningPort.calculateCapitalRoute(
                    request.startSystemId,
                    request.destinationSystemId,
                    request.effectiveRangeLy,
                ).requireRoute().toDto()
            }
        }

    override suspend fun getActiveMissions(request: GetActiveMissionsRequest): ControlResult<List<MissionSummaryDto>> =
        query(request.requestId) {
            validateRequestId(request.requestId)
            registry.active().map(Mission::toSummary)
        }

    override suspend fun getMission(request: GetMissionRequest): ControlResult<Mission> = query(request.requestId) {
        validateRequestId(request.requestId)
        registry.get(request.missionId)
    }

    override suspend fun beginMission(command: BeginMissionCommand): ControlResult<MissionSummaryDto> = mutation(
        "beginMission",
        command,
        listOf(command.title),
    ) {
        validateText("title", command.title, ControlLimits.MAX_TITLE_CODE_POINTS, allowBlank = false)
        val mission = registry.begin(command.title.trim())
        publishMissions()
        success(command.requestId, mission.toSummary(), mission.revision)
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

    override suspend fun showNormalRoute(command: ShowNormalRouteCommand): ControlResult<MissionRouteReceipt> = mutation(
        "showNormalRoute",
        command,
        listOf(command.missionId, command.startSystemId, command.destinationSystemId, command.useAnsiblex),
    ) {
        validateSystemId(command.startSystemId)
        validateSystemId(command.destinationSystemId)
        ensureRouteCapacity(command.missionId)
        val route = routePlanningPort.calculateNormalRoute(
            command.startSystemId,
            command.destinationSystemId,
            command.useAnsiblex,
        ).requireRoute()
        val owned = registry.addNormalRoute(command.missionId, route)
        publishMissions()
        val revision = registry.get(command.missionId).revision
        success(
            command.requestId,
            MissionRouteReceipt(command.missionId, owned.routeId, AnyRouteDto.Normal(route.toDto())),
            revision,
        )
    }

    override suspend fun showCapitalRoute(command: ShowCapitalRouteCommand): ControlResult<MissionRouteReceipt> = mutation(
        "showCapitalRoute",
        command,
        listOf(command.missionId, command.startSystemId, command.destinationSystemId, command.effectiveRangeLy),
    ) {
        validateSystemId(command.startSystemId)
        validateSystemId(command.destinationSystemId)
        validateRange(command.effectiveRangeLy)
        ensureRouteCapacity(command.missionId)
        val route = routePlanningPort.calculateCapitalRoute(
            command.startSystemId,
            command.destinationSystemId,
            command.effectiveRangeLy,
        ).requireRoute()
        val owned = registry.addCapitalRoute(command.missionId, route)
        publishMissions()
        val revision = registry.get(command.missionId).revision
        success(
            command.requestId,
            MissionRouteReceipt(command.missionId, owned.routeId, AnyRouteDto.Capital(route.toDto())),
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

    override fun close() = dispatcher.close()

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

    private fun ensureRouteCapacity(missionId: dev.evestaticmapplanner.control.mission.MissionId) {
        if (registry.get(missionId).routes.size >= ControlLimits.MAX_ROUTES_PER_MISSION) {
            limit("The mission route limit has been reached")
        }
    }
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

private fun RouteResult.toDto() = NormalRouteDto(
    startSystemId,
    destinationSystemId,
    systems,
    totalJumps,
    stargateJumps,
    ansiblexJumps,
)

private fun CapitalRouteResult.toDto() = CapitalRouteDto(
    startSystemId,
    destinationSystemId,
    profile.maxRangeLy,
    systems,
    legs.map { CapitalRouteLegDto(it.fromSystemId, it.toSystemId, it.distanceLy) },
    totalJumps,
    totalDistanceLy,
)

private fun Mission.toSummary() = MissionSummaryDto(
    missionId,
    title,
    createdAt.toEpochMilli(),
    revision,
    routes.size,
    jumpRanges.size,
    markers.size,
    referencedSystemIds.size,
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
