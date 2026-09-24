package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.MapControlService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetSystemInfoTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<GetSystemInfoTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Get authoritative EVE Static Map Planner information for one solar system ID.",
) {
    @Serializable
    data class Args(val systemId: Int)

    override suspend fun execute(args: Args): String {
        return executePlannerQuery(NAME, diagnostics, query = {
            mapControlService.getSystemInfo(
                GetSystemInfoRequest(
                    requestId = embeddedAiRequestId(),
                    systemId = args.systemId,
                ),
            )
        }, serialize = { it.toToolJson() })
    }

    companion object {
        const val NAME = "get_system_info"
        val TOOL_CALL_DIAGNOSTIC = toolCallDiagnostic(NAME)
        val TOOL_SUCCESS_DIAGNOSTIC = toolSuccessDiagnostic()
        val TOOL_FAILURE_DIAGNOSTIC = toolFailureDiagnostic()
    }
}

private fun dev.evestaticmapplanner.control.SystemInfoDto.toToolJson(): String = buildJsonObject {
    put("systemId", system.systemId)
    put("name", system.name)
    put("regionId", system.regionId)
    put("regionName", regionName)
    put("constellationId", system.constellationId)
    put("constellationName", constellationName)
    put("securityStatus", system.securityStatus)
    put("x", x)
    put("y", y)
    put("z", z)
    put("stargateCount", stargateCount)
    sovereignty?.let { sovereignty ->
        put("sovereignty", buildJsonObject {
            put("ownerKind", sovereignty.ownerKind)
            sovereignty.allianceId?.let { put("allianceId", it) }
            sovereignty.allianceName?.let { put("allianceName", it) }
            sovereignty.corporationId?.let { put("corporationId", it) }
            sovereignty.corporationName?.let { put("corporationName", it) }
            sovereignty.factionId?.let { put("factionId", it) }
            sovereignty.factionName?.let { put("factionName", it) }
            put("status", sovereignty.status)
            sovereignty.observedAtEpochMillis?.let { put("observedAtEpochMillis", it) }
            sovereignty.source?.let { put("source", it) }
            put("freshness", sovereignty.freshness)
            sovereignty.errorMessage?.let { put("errorMessage", it) }
        })
    }
}.toString()
