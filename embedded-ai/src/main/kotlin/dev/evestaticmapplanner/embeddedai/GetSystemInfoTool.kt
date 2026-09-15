package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.MapControlService
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetSystemInfoTool(
    private val mapControlService: MapControlService,
) : SimpleTool<GetSystemInfoTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Get authoritative EVE Static Map Planner information for one solar system ID.",
) {
    @Serializable
    data class Args(val systemId: Int)

    override suspend fun execute(args: Args): String = when (
        val result = mapControlService.getSystemInfo(
            GetSystemInfoRequest(
                requestId = "embedded-ai-${UUID.randomUUID()}",
                systemId = args.systemId,
            ),
        )
    ) {
        is ControlResult.Success -> result.value.toToolJson()
        is ControlResult.Failure -> throw EmbeddedAiToolException(
            "${result.error.code}: ${result.error.message}",
        )
    }

    companion object {
        const val NAME = "get_system_info"
    }
}

internal class EmbeddedAiToolException(message: String) : RuntimeException(message)

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
}.toString()
