package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.ShowJumpRangeCommand
import dev.evestaticmapplanner.control.mission.MissionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ShowJumpRangeTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ShowJumpRangeTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Calculate and display a temporary jump-range overlay owned by an existing Mission. " +
        "effectiveRangeLy is required and must be explicitly supplied by the user; never infer it from a ship name.",
) {
    @Serializable
    data class Args(
        val missionId: String,
        val originSystemId: Int,
        val effectiveRangeLy: Double,
        val label: String? = null,
    )

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.showJumpRange(
            ShowJumpRangeCommand(
                requestId = embeddedAiRequestId(),
                idempotencyKey = embeddedAiIdempotencyKey(),
                missionId = MissionId(args.missionId),
                originSystemId = args.originSystemId,
                effectiveRangeLy = args.effectiveRangeLy,
                label = args.label,
            ),
        )
    }, serialize = { overlay ->
        buildJsonObject {
            put("missionId", overlay.missionId.value)
            put("overlayId", overlay.jumpRangeId.value)
            put("originSystemId", overlay.originSystemId)
            put("effectiveRangeLy", overlay.effectiveRangeLy)
            put("reachableSystemCount", overlay.reachableSystemCount)
            if (args.label == null) put("label", JsonNull) else put("label", args.label)
        }.toString()
    }, mapErrorCode = ::mapJumpRangeToolError)

    companion object {
        const val NAME = "show_jump_range"
    }
}
