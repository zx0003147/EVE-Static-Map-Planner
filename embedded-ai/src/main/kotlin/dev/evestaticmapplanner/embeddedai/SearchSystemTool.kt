package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.ControlLimits
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemSummaryDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SearchSystemTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<SearchSystemTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Search authoritative Planner solar-system data by exact name, partial name, or ID text. " +
        "Use this first to resolve user-supplied names to canonical systemId values before calling other Planner tools. " +
        "If multiple systems match and context does not identify one, ask the user to clarify.",
) {
    @Serializable
    data class Args(
        val query: String,
        val limit: Int = ControlLimits.MAX_SEARCH_RESULTS,
    )

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.searchSystems(
            SearchSystemsRequest(
                requestId = embeddedAiRequestId(),
                query = args.query,
                limit = args.limit,
            ),
        )
    }, serialize = { systems ->
        buildJsonObject {
            put("systems", buildJsonArray {
                systems.forEach { add(it.toToolJson()) }
            })
        }.toString()
    })

    companion object {
        const val NAME = "search_system"
        val TOOL_CALL_DIAGNOSTIC = toolCallDiagnostic(NAME)
        val TOOL_SUCCESS_DIAGNOSTIC = toolSuccessDiagnostic()
        val TOOL_FAILURE_DIAGNOSTIC = toolFailureDiagnostic()
    }
}

private fun SystemSummaryDto.toToolJson() = buildJsonObject {
    put("systemId", systemId)
    put("name", name)
    put("regionId", regionId)
    put("constellationId", constellationId)
    put("securityStatus", securityStatus)
}
