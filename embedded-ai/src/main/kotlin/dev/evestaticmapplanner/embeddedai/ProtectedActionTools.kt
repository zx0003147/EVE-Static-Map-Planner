package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.CreateSavedMarkerCommand
import dev.evestaticmapplanner.control.ControlLimits
import dev.evestaticmapplanner.control.DeleteViewCommand
import dev.evestaticmapplanner.control.GetMissionRequest
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.ListEveNavigationTargetsRequest
import dev.evestaticmapplanner.control.ListViewsRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.NavigationActionExecutionStatus
import dev.evestaticmapplanner.control.SendMissionNavigationToEveCommand
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.control.mission.MissionRouteId
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CreateSavedMarkerTool(
    private val mapControlService: MapControlService,
    private val confirmations: AiActionConfirmationService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<CreateSavedMarkerTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Create one permanent Saved Marker in the Planner user database. Use only when the user explicitly says " +
        "save, permanent, or keep for later. Never use this for a temporary marker; use add_mission_marker instead. " +
        "Execution always requires a separate Planner confirmation dialog.",
), ConfirmationRequiredPlannerTool {
    @Serializable
    enum class Color {
        RED,
        ORANGE,
        YELLOW,
        GREEN,
        BLUE,
        PURPLE,
        WHITE,
    }

    @Serializable
    enum class Tag {
        STAGING,
        RALLY,
        DANGER,
        LOGISTICS,
        HOME,
        BACKUP,
        INDUSTRIAL,
        STRATEGIC,
        FORTIZAR,
        KEEPSTAR,
    }

    @Serializable
    data class Args(
        val systemId: Int,
        val name: String? = null,
        val notes: String? = null,
        val color: Color = Color.YELLOW,
        val tags: List<Tag> = emptyList(),
    )

    override suspend fun execute(args: Args): String {
        val normalizedName = args.name.normalizedOptionalText()
        val normalizedNotes = args.notes.normalizedOptionalText()
        validateOptionalText("name", normalizedName, ControlLimits.MAX_LABEL_CODE_POINTS)
        validateOptionalText("notes", normalizedNotes, ControlLimits.MAX_NOTES_CODE_POINTS)
        val normalizedTags = args.tags.distinct()
        val system = requirePlannerValue(
            query = {
                mapControlService.getSystemInfo(GetSystemInfoRequest(embeddedAiRequestId(), args.systemId))
            },
        ).system
        val normalizedArguments = buildJsonObject {
            put("systemId", args.systemId)
            if (normalizedName == null) put("name", JsonNull) else put("name", normalizedName)
            if (normalizedNotes == null) put("notes", JsonNull) else put("notes", normalizedNotes)
            put("color", args.color.name)
            put("tags", buildJsonArray { normalizedTags.forEach { add(JsonPrimitive(it.name)) } })
        }.toString()
        return confirmations.confirmAndExecute(
            AiActionRequest(
                toolName = NAME,
                risk = PlannerToolRisk.PERSISTENT_WRITE,
                normalizedArguments = normalizedArguments,
                action = "Create saved marker",
                target = "${system.name} (${system.systemId})",
                details = listOf(
                    AiActionDetail("Name", normalizedName ?: "(none)"),
                    AiActionDetail("Color", args.color.name.lowercase(Locale.ROOT)),
                    AiActionDetail("Tags", normalizedTags.joinToString { it.name.lowercase(Locale.ROOT) }.ifEmpty { "(none)" }),
                    AiActionDetail("Notes", normalizedNotes ?: "(none)"),
                ),
                effect = "This permanently writes a Saved Marker to the Planner user database.",
            ),
        ) { idempotencyKey ->
            executePlannerQuery(NAME, diagnostics, query = {
                mapControlService.createSavedMarker(
                    CreateSavedMarkerCommand(
                        requestId = embeddedAiRequestId(),
                        idempotencyKey = idempotencyKey,
                        systemId = args.systemId,
                        name = normalizedName,
                        notes = normalizedNotes,
                        color = MarkerColor.valueOf(args.color.name),
                        tags = normalizedTags.map { SavedMarkerChildType.of(it.name) },
                    ),
                )
            }, serialize = { receipt ->
                buildJsonObject {
                    put("success", true)
                    put("systemId", receipt.marker.systemId)
                    if (receipt.marker.name == null) put("name", JsonNull) else put("name", receipt.marker.name)
                    put("color", receipt.marker.color.name)
                    put("tags", buildJsonArray { receipt.marker.children.forEach { add(JsonPrimitive(it.type)) } })
                    put("createdBy", receipt.marker.createdBy.name)
                }.toString()
            })
        }
    }

    companion object {
        const val NAME = "create_saved_marker"
    }
}

class DeleteViewTool(
    private val mapControlService: MapControlService,
    private val confirmations: AiActionConfirmationService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<DeleteViewTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Delete one in-memory Planner View and its temporary planning state. This is destructive within the current " +
        "session and always requires a separate Planner confirmation dialog. Never infer the viewId.",
), ConfirmationRequiredPlannerTool {
    @Serializable
    data class Args(val viewId: String)

    override suspend fun execute(args: Args): String {
        val view = requirePlannerValue(query = {
            mapControlService.listViews(ListViewsRequest(embeddedAiRequestId()))
        }).singleOrNull { it.viewId == args.viewId }
            ?: throw EmbeddedAiToolException("NOT_FOUND: View was not found")
        val normalizedArguments = buildJsonObject { put("viewId", args.viewId) }.toString()
        return confirmations.confirmAndExecute(
            AiActionRequest(
                toolName = NAME,
                risk = PlannerToolRisk.DESTRUCTIVE_WRITE,
                normalizedArguments = normalizedArguments,
                action = "Delete View",
                target = "${view.label} (${view.viewId})",
                details = listOf(AiActionDetail("Current View", if (view.current) "yes" else "no")),
                effect = "This removes the View and its temporary routes and Missions for the current app session.",
            ),
        ) { idempotencyKey ->
            executePlannerQuery(NAME, diagnostics, query = {
                mapControlService.deleteView(
                    DeleteViewCommand(embeddedAiRequestId(), idempotencyKey, args.viewId),
                )
            }, serialize = { current ->
                buildJsonObject {
                    put("success", true)
                    put("deletedViewId", args.viewId)
                    put("currentViewId", current.viewId)
                    put("currentViewLabel", current.label)
                }.toString()
            })
        }
    }

    companion object {
        const val NAME = "delete_view"
    }
}

class ListEveNavigationTargetsTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ListEveNavigationTargetsTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "List EVE characters currently offered by the navigation Feature Pack. Call before requesting an EVE navigation " +
        "send. If multiple characters are available and the user did not choose one, ask the user; never choose for them.",
) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.listEveNavigationTargets(ListEveNavigationTargetsRequest(embeddedAiRequestId()))
    }, serialize = { targets ->
        buildJsonObject {
            put("targets", buildJsonArray {
                targets.forEach { target ->
                    add(buildJsonObject {
                        put("characterId", target.characterId)
                        put("label", target.label)
                        if (target.description == null) put("description", JsonNull) else put("description", target.description)
                        put("available", target.available)
                    })
                }
            })
        }.toString()
    })

    companion object {
        const val NAME = "list_eve_navigation_targets"
    }
}

class SendMissionNavigationToEveTool(
    private val mapControlService: MapControlService,
    private val confirmations: AiActionConfirmationService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<SendMissionNavigationToEveTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Send one Mission-owned Normal route to one explicitly selected EVE character. Call " +
        "list_eve_navigation_targets first and never guess the character. Use only when the user explicitly asks to send or set " +
        "navigation in EVE. Execution always requires a separate Planner confirmation dialog.",
), ConfirmationRequiredPlannerTool {
    @Serializable
    data class Args(
        val missionId: String,
        val routeId: String,
        val characterId: String,
    )

    override suspend fun execute(args: Args): String {
        val targets = requirePlannerValue(query = {
            mapControlService.listEveNavigationTargets(ListEveNavigationTargetsRequest(embeddedAiRequestId()))
        })
        val target = targets.singleOrNull { it.characterId == args.characterId && it.available }
            ?: throw EmbeddedAiToolException("INVALID_ARGUMENT: The selected EVE character is unavailable")
        val mission = requirePlannerValue(
            query = {
                mapControlService.getMission(GetMissionRequest(embeddedAiRequestId(), MissionId(args.missionId)))
            },
            mapErrorCode = ::mapMissionToolError,
        )
        val route = mission.routes.singleOrNull { it.routeId.value == args.routeId }
            ?: throw EmbeddedAiToolException("NOT_FOUND: Mission route was not found")
        if (route !is MissionRoute.Normal) {
            throw EmbeddedAiToolException("INVALID_ARGUMENT: Only Normal routes can be sent to EVE")
        }
        val targetSystemIds = route.navigationIntent.waypointSystemIds +
            listOfNotNull(route.navigationIntent.destinationSystemId)
        val normalizedArguments = buildJsonObject {
            put("missionId", args.missionId)
            put("routeId", args.routeId)
            put("characterId", args.characterId)
        }.toString()
        return confirmations.confirmAndExecute(
            AiActionRequest(
                toolName = NAME,
                risk = PlannerToolRisk.EXTERNAL_ACTION,
                normalizedArguments = normalizedArguments,
                action = "Send navigation to EVE",
                target = target.label,
                details = listOf(
                    AiActionDetail("Character", "${target.label} (${target.characterId})"),
                    AiActionDetail("Mission", mission.title),
                    AiActionDetail("Route", route.systemIds.joinToString(" → ")),
                    AiActionDetail("Target count", targetSystemIds.size.toString()),
                ),
                effect = "This sends navigation targets outside the Planner to the selected EVE character.",
            ),
        ) { idempotencyKey ->
            executePlannerQuery(NAME, diagnostics, query = {
                mapControlService.sendMissionNavigationToEve(
                    SendMissionNavigationToEveCommand(
                        requestId = embeddedAiRequestId(),
                        idempotencyKey = idempotencyKey,
                        missionId = MissionId(args.missionId),
                        routeId = MissionRouteId(args.routeId),
                        characterId = args.characterId,
                    ),
                )
            }, serialize = { receipt ->
                buildJsonObject {
                    put("success", receipt.status == NavigationActionExecutionStatus.SUCCEEDED)
                    put("status", receipt.status.name.lowercase(Locale.ROOT))
                    put("missionId", receipt.missionId.value)
                    put("routeId", receipt.routeId.value)
                    put("characterId", receipt.characterId)
                    put("targetCount", receipt.targetSystemIds.size)
                    if (receipt.message == null) put("message", JsonNull) else put("message", receipt.message)
                }.toString()
            })
        }
    }

    companion object {
        const val NAME = "send_mission_navigation_to_eve"
    }
}

private fun String?.normalizedOptionalText(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun validateOptionalText(name: String, value: String?, maxCodePoints: Int) {
    if (value != null && value.codePointCount(0, value.length) > maxCodePoints) {
        throw EmbeddedAiToolException("INVALID_ARGUMENT: $name exceeds its length limit")
    }
}
