package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.AddMissionMarkerCommand
import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.GetMissionRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BeginMissionTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<BeginMissionTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Create a temporary Planner Mission in the current view and return its generated missionId. " +
        "Use one Mission to contain all map overlays and temporary markers for one explicit user display task. " +
        "Never invent a missionId.",
) {
    @Serializable
    data class Args(val title: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.beginMission(
            BeginMissionCommand(
                requestId = embeddedAiRequestId(),
                idempotencyKey = embeddedAiIdempotencyKey(),
                title = args.title,
            ),
        )
    }, serialize = { mission ->
        buildJsonObject {
            put("missionId", mission.missionId.value)
            put("title", mission.title)
            put("revision", mission.revision)
        }.toString()
    }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "begin_mission"
    }
}

class GetMissionTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<GetMissionTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Read the current state of a Planner Mission by an authoritative missionId returned by begin_mission. " +
        "Use it when a multi-step operation needs to verify Mission contents; it never changes the map.",
) {
    @Serializable
    data class Args(val missionId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.getMission(
            GetMissionRequest(
                requestId = embeddedAiRequestId(),
                missionId = MissionId(args.missionId),
            ),
        )
    }, serialize = Mission::toToolJson, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "get_mission"
    }
}

class AddMissionMarkerTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<AddMissionMarkerTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Add a temporary marker to a Mission at one canonical solar-system ID. " +
        "This never creates a Saved Marker and the marker exists only with its Mission.",
) {
    @Serializable
    enum class Role {
        RALLY,
        DESTINATION,
        DANGER,
        BACKUP,
        WAYPOINT,
        INFO,
    }

    @Serializable
    data class Args(
        val missionId: String,
        val systemId: Int,
        val role: Role = Role.RALLY,
        val label: String? = null,
        val notes: String? = null,
    )

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.addMissionMarker(
            AddMissionMarkerCommand(
                requestId = embeddedAiRequestId(),
                idempotencyKey = embeddedAiIdempotencyKey(),
                missionId = MissionId(args.missionId),
                systemId = args.systemId,
                role = MissionMarkerRole.valueOf(args.role.name),
                label = args.label,
                notes = args.notes,
            ),
        )
    }, serialize = { marker ->
        buildJsonObject {
            put("missionId", marker.missionId.value)
            put("markerId", marker.markerId.value)
            put("systemId", marker.systemId)
            put("role", marker.role.name)
        }.toString()
    }, mapErrorCode = ::mapMarkerToolError)

    companion object {
        const val NAME = "add_mission_marker"
    }
}

private fun Mission.toToolJson(): String = buildJsonObject {
    put("missionId", missionId.value)
    put("title", title)
    put("revision", revision)
    put("routeOverlays", buildJsonArray {
        routes.forEach { route ->
            add(buildJsonObject {
                put("routeId", route.routeId.value)
                put("type", if (route is MissionRoute.Normal) "NORMAL" else "CAPITAL")
                put("systemIds", route.systemIds.toJsonArray())
                when (route) {
                    is MissionRoute.Normal -> {
                        put("jumpCount", route.route.totalJumps)
                        put("stargateJumps", route.route.edges.count { it.type == RouteEdgeType.STARGATE })
                        put("ansiblexJumps", route.route.edges.count { it.type == RouteEdgeType.ANSIBLEX })
                        put("wormholeJumps", route.route.edges.count { it.type == RouteEdgeType.WORMHOLE })
                    }
                    is MissionRoute.Capital -> {
                        put("effectiveRangeLy", route.route.profile.maxRangeLy)
                        put("jumpCount", route.route.totalJumps)
                        put("totalDistanceLy", route.route.totalDistanceLy)
                    }
                }
            })
        }
    })
    put("jumpRanges", buildJsonArray {
        jumpRanges.forEach { range ->
            add(buildJsonObject {
                put("overlayId", range.jumpRangeId.value)
                put("originSystemId", range.originSystemId)
                put("effectiveRangeLy", range.profile.maxRangeLy)
                put("reachableSystemCount", range.reachableSystemIds.size)
                if (range.label == null) put("label", JsonNull) else put("label", range.label)
            })
        }
    })
    put("missionMarkers", buildJsonArray {
        markers.forEach { marker ->
            add(buildJsonObject {
                put("markerId", marker.markerId.value)
                put("systemId", marker.systemId)
                put("role", marker.role.name)
                if (marker.label == null) put("label", JsonNull) else put("label", marker.label)
            })
        }
    })
    put("referencedSystemIds", referencedSystemIds.sorted().toJsonArray())
}.toString()
