package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.AnyRouteDto
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.ShowCapitalRouteCommand
import dev.evestaticmapplanner.control.ShowNormalRouteCommand
import dev.evestaticmapplanner.control.mission.MissionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ShowNormalRouteTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ShowNormalRouteTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Calculate an authoritative normal route and atomically add it to an existing temporary Mission for map display. " +
        "Use only when the user explicitly asks to show, draw, or put the route on the map. Stargates-only is the safe default; " +
        "enable Ansiblex or temporary Wormholes only when the user explicitly requests them.",
) {
    @Serializable
    data class Args(
        val missionId: String,
        val startSystemId: Int,
        val destinationSystemId: Int? = null,
        val waypointSystemIds: List<Int> = emptyList(),
        val useAnsiblex: Boolean = false,
        val useWormholes: Boolean = false,
    )

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.showNormalRoute(
            ShowNormalRouteCommand(
                requestId = embeddedAiRequestId(),
                idempotencyKey = embeddedAiIdempotencyKey(),
                missionId = MissionId(args.missionId),
                startSystemId = args.startSystemId,
                destinationSystemId = args.destinationSystemId,
                waypointSystemIds = args.waypointSystemIds,
                useAnsiblex = args.useAnsiblex,
                useWormholes = args.useWormholes,
            ),
        )
    }, serialize = { receipt ->
        val route = (receipt.route as AnyRouteDto.Normal).value
        buildJsonObject {
            put("missionId", receipt.missionId.value)
            put("routeId", receipt.routeId.value)
            put("routeType", "NORMAL")
            put("jumpCount", route.totalJumps)
            put("systemIds", route.systemIds.toJsonArray())
            put("stargateJumps", route.stargateJumps)
            put("ansiblexJumps", route.ansiblexJumps)
            put("wormholeJumps", route.wormholeJumps)
        }.toString()
    }, mapErrorCode = ::mapRouteDisplayToolError)

    companion object {
        const val NAME = "show_normal_route"
    }
}

class ShowCapitalRouteTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ShowCapitalRouteTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Calculate an authoritative capital route and atomically add it to an existing temporary Mission for map display. " +
        "Use only on an explicit display request. effectiveRangeLy is required and must come from the user; never infer it from a ship name.",
) {
    @Serializable
    data class Args(
        val missionId: String,
        val startSystemId: Int,
        val destinationSystemId: Int? = null,
        val waypointSystemIds: List<Int> = emptyList(),
        val effectiveRangeLy: Double,
    )

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.showCapitalRoute(
            ShowCapitalRouteCommand(
                requestId = embeddedAiRequestId(),
                idempotencyKey = embeddedAiIdempotencyKey(),
                missionId = MissionId(args.missionId),
                startSystemId = args.startSystemId,
                destinationSystemId = args.destinationSystemId,
                waypointSystemIds = args.waypointSystemIds,
                effectiveRangeLy = args.effectiveRangeLy,
            ),
        )
    }, serialize = { receipt ->
        val route = (receipt.route as AnyRouteDto.Capital).value
        buildJsonObject {
            put("missionId", receipt.missionId.value)
            put("routeId", receipt.routeId.value)
            put("routeType", "CAPITAL")
            put("jumpCount", route.totalJumps)
            put("systemIds", route.systemIds.toJsonArray())
            put("effectiveRangeLy", route.effectiveRangeLy)
            put("totalDistanceLy", route.totalDistanceLy)
            put("legs", buildJsonArray {
                route.legs.forEach { leg ->
                    add(buildJsonObject {
                        put("fromSystemId", leg.fromSystemId)
                        put("toSystemId", leg.toSystemId)
                        put("distanceLy", leg.distanceLy)
                    })
                }
            })
        }.toString()
    }, mapErrorCode = ::mapCapitalRouteDisplayToolError)

    companion object {
        const val NAME = "show_capital_route"
    }
}
