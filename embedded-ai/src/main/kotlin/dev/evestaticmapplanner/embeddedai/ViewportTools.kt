package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.FitMissionCommand
import dev.evestaticmapplanner.control.FocusSystemCommand
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.mission.MissionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FocusSystemTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<FocusSystemTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Focus the current Planner map viewport on one canonical solar-system ID. " +
        "Use only when the user explicitly asks to focus, locate, or show that system on the map.",
) {
    @Serializable
    data class Args(val systemId: Int)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.focusSystem(
            FocusSystemCommand(
                requestId = embeddedAiRequestId(),
                idempotencyKey = embeddedAiIdempotencyKey(),
                systemId = args.systemId,
            ),
        )
    }, serialize = { system ->
        buildJsonObject {
            put("success", true)
            put("systemId", system.systemId)
            put("name", system.name)
        }.toString()
    }, mapErrorCode = ::mapViewportToolError)

    companion object {
        const val NAME = "focus_system"
    }
}

class FitMissionTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<FitMissionTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Fit the current Planner map viewport around all visual content owned by a Mission. " +
        "Call after adding the Mission's requested routes, jump ranges, and markers.",
) {
    @Serializable
    data class Args(val missionId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.fitMission(
            FitMissionCommand(
                requestId = embeddedAiRequestId(),
                idempotencyKey = embeddedAiIdempotencyKey(),
                missionId = MissionId(args.missionId),
            ),
        )
    }, serialize = { receipt ->
        buildJsonObject {
            put("success", true)
            put("missionId", receipt.missionId.value)
        }.toString()
    }, mapErrorCode = ::mapViewportToolError)

    companion object {
        const val NAME = "fit_mission"
    }
}
