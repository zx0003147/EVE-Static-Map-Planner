package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.CalculateCapitalRouteRequest
import dev.evestaticmapplanner.control.CapitalRouteDto
import dev.evestaticmapplanner.control.MapControlService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CalculateCapitalRouteTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<CalculateCapitalRouteTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Calculate an authoritative capital jump route through ordered waypoints and an optional destination. " +
        "Use the user's effective jump range in light-years; do not infer or calculate a range. This read-only tool uses " +
        "the Planner capital-route engine and returns its route legs and distances.",
) {
    @Serializable
    data class Args(
        val startSystemId: Int,
        val destinationSystemId: Int? = null,
        val waypointSystemIds: List<Int> = emptyList(),
        val effectiveRangeLy: Double,
    )

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.calculateCapitalRoute(
            CalculateCapitalRouteRequest(
                requestId = embeddedAiRequestId(),
                startSystemId = args.startSystemId,
                destinationSystemId = args.destinationSystemId,
                effectiveRangeLy = args.effectiveRangeLy,
                waypointSystemIds = args.waypointSystemIds,
            ),
        )
    }, serialize = CapitalRouteDto::toToolJson)

    companion object {
        const val NAME = "calculate_capital_route"
        val TOOL_CALL_DIAGNOSTIC = toolCallDiagnostic(NAME)
        val TOOL_SUCCESS_DIAGNOSTIC = toolSuccessDiagnostic()
        val TOOL_FAILURE_DIAGNOSTIC = toolFailureDiagnostic()
    }
}

private fun CapitalRouteDto.toToolJson(): String = buildJsonObject {
    put("startSystemId", startSystemId)
    putNullableInt("destinationSystemId", destinationSystemId)
    put("effectiveRangeLy", effectiveRangeLy)
    put("systemIds", systemIds.toJsonArray())
    put("legs", buildJsonArray {
        legs.forEach { leg ->
            add(buildJsonObject {
                put("fromSystemId", leg.fromSystemId)
                put("toSystemId", leg.toSystemId)
                put("distanceLy", leg.distanceLy)
            })
        }
    })
    put("totalJumps", totalJumps)
    put("totalDistanceLy", totalDistanceLy)
    put("waypointSystemIds", waypointSystemIds.toJsonArray())
    putNullableInt("explicitDestinationSystemId", explicitDestinationSystemId)
}.toString()
