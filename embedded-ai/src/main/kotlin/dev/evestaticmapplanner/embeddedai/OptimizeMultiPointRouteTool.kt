package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.MultiPointRouteOptimizationDto
import dev.evestaticmapplanner.control.OptimizeMultiPointRouteRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OptimizeMultiPointRouteTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<OptimizeMultiPointRouteTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Choose the authoritative visit order for an unordered set of 1 to 50 normal-route targets from a " +
        "fixed start, with a free final target. Use this when the user asks the Planner to find the shortest or a near-shortest " +
        "order. It uses Stargates and optionally enabled Ansiblex, never Wormholes. Do not replace it with repeated calls to " +
        "calculate_normal_route; use calculate_normal_route only when the visit order is already known.",
) {
    @Serializable
    data class Args(
        val startSystemId: Int,
        val targetSystemIds: List<Int>,
        val useAnsiblex: Boolean,
    )

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.optimizeMultiPointRoute(
            OptimizeMultiPointRouteRequest(
                requestId = embeddedAiRequestId(),
                startSystemId = args.startSystemId,
                targetSystemIds = args.targetSystemIds,
                useAnsiblex = args.useAnsiblex,
            ),
        )
    }, serialize = MultiPointRouteOptimizationDto::toToolJson)

    companion object {
        const val NAME = "optimize_multi_point_route"
        val TOOL_CALL_DIAGNOSTIC = toolCallDiagnostic(NAME)
        val TOOL_SUCCESS_DIAGNOSTIC = toolSuccessDiagnostic()
        val TOOL_FAILURE_DIAGNOSTIC = toolFailureDiagnostic()
    }
}

private fun MultiPointRouteOptimizationDto.toToolJson(): String = when (this) {
    is MultiPointRouteOptimizationDto.Succeeded -> buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("success", success)
        put("startSystemId", startSystemId)
        put("inputTargetCount", inputTargetCount)
        put("uniqueTargetCount", uniqueTargetCount)
        put("startWasTarget", startWasTarget)
        put("useAnsiblex", useAnsiblex)
        put("optimization", buildJsonObject {
            put("method", optimization.method)
            put("guaranteedOptimal", optimization.guaranteedOptimal)
        })
        put("orderedTargets", buildJsonArray {
            orderedTargets.forEach { target ->
                add(buildJsonObject {
                    put("systemId", target.systemId)
                    put("systemName", target.systemName)
                })
            }
        })
        put("segments", buildJsonArray {
            segments.forEach { segment ->
                add(buildJsonObject {
                    put("fromSystemId", segment.fromSystemId)
                    put("toSystemId", segment.toSystemId)
                    put("jumps", segment.jumps)
                })
            }
        })
        put("totalJumps", totalJumps)
        put("coverage", buildJsonObject {
            put("required", coverage.required)
            put("visited", coverage.visited)
            put("missingSystemIds", coverage.missingSystemIds.toJsonArray())
        })
        put("stats", buildJsonObject {
            put("graphNodes", stats.graphNodes)
            put("graphEdges", stats.graphEdges)
            put("bfsRuns", stats.bfsRuns)
        })
    }.toString()
    is MultiPointRouteOptimizationDto.Failed -> buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("success", success)
        put("error", error)
        put("message", message)
        put("missingTargetSystemIds", missingTargetSystemIds.toJsonArray())
        put("unreachableSystemIds", unreachableSystemIds.toJsonArray())
        put("missingSystemIds", missingSystemIds.toJsonArray())
        uniqueTargetCount?.let { put("uniqueTargetCount", it) }
        maximumTargetCount?.let { put("maximumTargetCount", it) }
    }.toString()
}
