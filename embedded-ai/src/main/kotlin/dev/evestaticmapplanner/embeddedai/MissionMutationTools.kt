package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.ClearMissionJumpRangesCommand
import dev.evestaticmapplanner.control.ClearMissionMarkersCommand
import dev.evestaticmapplanner.control.ClearMissionRoutesCommand
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.MissionMutationReceipt
import dev.evestaticmapplanner.control.RemoveJumpRangeCommand
import dev.evestaticmapplanner.control.RemoveMissionMarkerCommand
import dev.evestaticmapplanner.control.RemoveMissionRouteCommand
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionJumpRangeId
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionRouteId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RemoveMissionRouteTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<RemoveMissionRouteTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Remove exactly one temporary route from its owning Mission while preserving that Mission's other routes, " +
        "jump ranges, and markers. missionId and routeId must come from a Planner tool result; never guess either ID.",
) {
    @Serializable
    data class Args(val missionId: String, val routeId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.removeMissionRoute(
            RemoveMissionRouteCommand(
                embeddedAiRequestId(),
                embeddedAiIdempotencyKey(),
                MissionId(args.missionId),
                MissionRouteId(args.routeId),
            ),
        )
    }, serialize = { it.mutationJson("routeId", args.routeId) }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "remove_mission_route"
    }
}

class ClearMissionRoutesTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ClearMissionRoutesTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Clear only temporary routes owned by one Mission while preserving all of its jump ranges and markers. " +
        "Use a missionId returned by Planner tools; never substitute clear_mission for a route-only request.",
) {
    @Serializable
    data class Args(val missionId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.clearMissionRoutes(
            ClearMissionRoutesCommand(
                embeddedAiRequestId(),
                embeddedAiIdempotencyKey(),
                MissionId(args.missionId),
            ),
        )
    }, serialize = { it.mutationJson() }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "clear_mission_routes"
    }
}

class RemoveJumpRangeTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<RemoveJumpRangeTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Remove exactly one temporary jump-range overlay from its owning Mission while preserving routes and markers. " +
        "missionId and overlayId must come from a Planner tool result; never guess either ID.",
) {
    @Serializable
    data class Args(val missionId: String, val overlayId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.removeJumpRange(
            RemoveJumpRangeCommand(
                embeddedAiRequestId(),
                embeddedAiIdempotencyKey(),
                MissionId(args.missionId),
                MissionJumpRangeId(args.overlayId),
            ),
        )
    }, serialize = { it.mutationJson("overlayId", args.overlayId) }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "remove_jump_range"
    }
}

class ClearMissionJumpRangesTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ClearMissionJumpRangesTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Clear only temporary jump-range overlays owned by one Mission while preserving all routes and markers. " +
        "Use a missionId returned by Planner tools; never substitute clear_mission for a jump-range-only request.",
) {
    @Serializable
    data class Args(val missionId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.clearMissionJumpRanges(
            ClearMissionJumpRangesCommand(
                embeddedAiRequestId(),
                embeddedAiIdempotencyKey(),
                MissionId(args.missionId),
            ),
        )
    }, serialize = { it.mutationJson() }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "clear_mission_jump_ranges"
    }
}

class RemoveMissionMarkerTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<RemoveMissionMarkerTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Remove exactly one temporary Mission marker while preserving routes, jump ranges, and every Saved Marker. " +
        "This tool cannot delete persistent Saved Markers. missionId and markerId must come from Planner tool results; never guess them.",
) {
    @Serializable
    data class Args(val missionId: String, val markerId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.removeMissionMarker(
            RemoveMissionMarkerCommand(
                embeddedAiRequestId(),
                embeddedAiIdempotencyKey(),
                MissionId(args.missionId),
                MissionMarkerId(args.markerId),
            ),
        )
    }, serialize = { it.mutationJson("markerId", args.markerId) }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "remove_mission_marker"
    }
}

class ClearMissionMarkersTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ClearMissionMarkersTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Clear only temporary markers owned by one Mission while preserving routes, jump ranges, and every Saved Marker. " +
        "This tool cannot delete persistent Saved Markers; use an authoritative missionId returned by Planner tools.",
) {
    @Serializable
    data class Args(val missionId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.clearMissionMarkers(
            ClearMissionMarkersCommand(
                embeddedAiRequestId(),
                embeddedAiIdempotencyKey(),
                MissionId(args.missionId),
            ),
        )
    }, serialize = { it.mutationJson() }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "clear_mission_markers"
    }
}

private fun MissionMutationReceipt.mutationJson(
    objectIdName: String? = null,
    objectId: String? = null,
): String = buildJsonObject {
    put("success", true)
    put("missionId", missionId.value)
    if (objectIdName != null && objectId != null) put(objectIdName, objectId)
}.toString()
