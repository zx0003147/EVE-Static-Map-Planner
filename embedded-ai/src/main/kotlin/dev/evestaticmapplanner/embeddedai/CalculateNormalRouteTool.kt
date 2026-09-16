package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.CalculateNormalRouteRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.NormalRouteDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CalculateNormalRouteTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<CalculateNormalRouteTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Calculate an authoritative normal route when the visit order is already known. " +
        "The route follows the supplied ordered waypoints and optional destination, using Stargates plus enabled Ansiblex " +
        "and current temporary Wormholes only when their flags request them. Use optimize_multi_point_route instead when " +
        "the Planner must choose the best order for an unordered target set. This read-only tool never displays a route.",
) {
    @Serializable
    data class Args(
        val startSystemId: Int,
        val destinationSystemId: Int? = null,
        val waypointSystemIds: List<Int> = emptyList(),
        val useAnsiblex: Boolean,
        val useWormholes: Boolean = false,
    )

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.calculateNormalRoute(
            CalculateNormalRouteRequest(
                requestId = embeddedAiRequestId(),
                startSystemId = args.startSystemId,
                destinationSystemId = args.destinationSystemId,
                useAnsiblex = args.useAnsiblex,
                useWormholes = args.useWormholes,
                waypointSystemIds = args.waypointSystemIds,
            ),
        )
    }, serialize = NormalRouteDto::toToolJson)

    companion object {
        const val NAME = "calculate_normal_route"
        val TOOL_CALL_DIAGNOSTIC = toolCallDiagnostic(NAME)
        val TOOL_SUCCESS_DIAGNOSTIC = toolSuccessDiagnostic()
        val TOOL_FAILURE_DIAGNOSTIC = toolFailureDiagnostic()
    }
}

private fun NormalRouteDto.toToolJson(): String = buildJsonObject {
    put("startSystemId", startSystemId)
    putNullableInt("destinationSystemId", destinationSystemId)
    put("systemIds", systemIds.toJsonArray())
    put("totalJumps", totalJumps)
    put("stargateJumps", stargateJumps)
    put("ansiblexJumps", ansiblexJumps)
    put("wormholeJumps", wormholeJumps)
    put("waypointSystemIds", waypointSystemIds.toJsonArray())
    putNullableInt("explicitDestinationSystemId", explicitDestinationSystemId)
}.toString()

internal fun List<Int>.toJsonArray() = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

internal fun kotlinx.serialization.json.JsonObjectBuilder.putNullableInt(name: String, value: Int?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}
