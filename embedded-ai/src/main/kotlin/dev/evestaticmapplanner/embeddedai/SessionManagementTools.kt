package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.ClearMissionCommand
import dev.evestaticmapplanner.control.CreateViewCommand
import dev.evestaticmapplanner.control.CreateWormholeCommand
import dev.evestaticmapplanner.control.GetActiveMissionsRequest
import dev.evestaticmapplanner.control.GetCurrentViewRequest
import dev.evestaticmapplanner.control.ListViewsRequest
import dev.evestaticmapplanner.control.ListWormholesRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.RenameViewCommand
import dev.evestaticmapplanner.control.SwitchViewCommand
import dev.evestaticmapplanner.control.mission.MissionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ListViewsTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ListViewsTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "List the Planner's in-memory Views. This is read-only; Views are session state and are not saved across app restarts.",
) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.listViews(ListViewsRequest(embeddedAiRequestId()))
    }, serialize = { views ->
        buildJsonObject {
            put("views", buildJsonArray {
                views.forEach { view ->
                    add(buildJsonObject {
                        put("viewId", view.viewId)
                        put("label", view.label)
                        put("current", view.current)
                    })
                }
            })
        }.toString()
    })

    companion object {
        const val NAME = "list_views"
    }
}

class GetCurrentViewTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<GetCurrentViewTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Read the current in-memory Planner View without changing it.",
) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.getCurrentView(GetCurrentViewRequest(embeddedAiRequestId()))
    }, serialize = { view -> viewJson(view.viewId, view.label, view.current) })

    companion object {
        const val NAME = "get_current_view"
    }
}

class CreateViewTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<CreateViewTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Create and switch to a new in-memory Planner View. Use only when the user explicitly asks for a new View. " +
        "The View is session-only and is not saved across app restarts.",
) {
    @Serializable
    data class Args(val label: String? = null)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.createView(
            CreateViewCommand(embeddedAiRequestId(), embeddedAiIdempotencyKey(), args.label),
        )
    }, serialize = { view -> viewJson(view.viewId, view.label, view.current) })

    companion object {
        const val NAME = "create_view"
    }
}

class RenameViewTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<RenameViewTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Rename an in-memory Planner View. Use only when the user explicitly asks to rename that exact View.",
) {
    @Serializable
    data class Args(val viewId: String, val label: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.renameView(
            RenameViewCommand(embeddedAiRequestId(), embeddedAiIdempotencyKey(), args.viewId, args.label),
        )
    }, serialize = { view -> viewJson(view.viewId, view.label, view.current) })

    companion object {
        const val NAME = "rename_view"
    }
}

class SwitchViewTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<SwitchViewTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Switch the visible Planner map to an existing in-memory View. Use only when the user explicitly asks to switch Views.",
) {
    @Serializable
    data class Args(val viewId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.switchView(
            SwitchViewCommand(embeddedAiRequestId(), embeddedAiIdempotencyKey(), args.viewId),
        )
    }, serialize = { view -> viewJson(view.viewId, view.label, view.current) })

    companion object {
        const val NAME = "switch_view"
    }
}

class GetActiveMissionsTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<GetActiveMissionsTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "List the bounded set of temporary Missions in one View, or the current View when viewId is omitted. This is read-only.",
) {
    @Serializable
    data class Args(val viewId: String? = null)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.getActiveMissions(GetActiveMissionsRequest(embeddedAiRequestId(), args.viewId))
    }, serialize = { missions ->
        buildJsonObject {
            put("missions", buildJsonArray {
                missions.forEach { mission ->
                    add(buildJsonObject {
                        put("missionId", mission.missionId.value)
                        put("title", mission.title)
                        put("revision", mission.revision)
                        put("routeCount", mission.routeCount)
                        put("jumpRangeCount", mission.jumpRangeCount)
                        put("markerCount", mission.markerCount)
                        put("viewId", mission.viewId)
                    })
                }
            })
        }.toString()
    }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "get_active_missions"
    }
}

class ClearMissionTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ClearMissionTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Remove one temporary Mission and all of its temporary routes, jump ranges, and Mission markers. " +
        "Use only when the user explicitly asks to clear that Mission. This never deletes Saved Markers.",
) {
    @Serializable
    data class Args(val missionId: String)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.clearMission(
            ClearMissionCommand(embeddedAiRequestId(), embeddedAiIdempotencyKey(), MissionId(args.missionId)),
        )
    }, serialize = { receipt ->
        buildJsonObject {
            put("success", true)
            put("missionId", receipt.missionId.value)
        }.toString()
    }, mapErrorCode = ::mapMissionToolError)

    companion object {
        const val NAME = "clear_mission"
    }
}

class ListWormholesTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<ListWormholesTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "List temporary Wormhole connections in the current app session. This is read-only and returns no persistent data.",
) {
    @Serializable
    class Args

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.listWormholes(ListWormholesRequest(embeddedAiRequestId()))
    }, serialize = { wormholes ->
        buildJsonObject {
            put("wormholes", buildJsonArray {
                wormholes.forEach { wormhole ->
                    add(buildJsonObject {
                        put("connectionId", wormhole.connectionId)
                        put("firstSystemId", wormhole.firstSystemId)
                        if (wormhole.firstSystemName == null) {
                            put("firstSystemName", JsonNull)
                        } else {
                            put("firstSystemName", wormhole.firstSystemName)
                        }
                        put("secondSystemId", wormhole.secondSystemId)
                        if (wormhole.secondSystemName == null) {
                            put("secondSystemName", JsonNull)
                        } else {
                            put("secondSystemName", wormhole.secondSystemName)
                        }
                    })
                }
            })
        }.toString()
    })

    companion object {
        const val NAME = "list_wormholes"
    }
}

class CreateWormholeTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<CreateWormholeTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Create one temporary, bidirectional Wormhole connection for the current app session. " +
        "Use only when the user explicitly asks to add this exact connection; it is not persisted across restarts.",
) {
    @Serializable
    data class Args(val fromSystemId: Int, val toSystemId: Int)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.createWormhole(
            CreateWormholeCommand(
                embeddedAiRequestId(),
                embeddedAiIdempotencyKey(),
                args.fromSystemId,
                args.toSystemId,
            ),
        )
    }, serialize = { receipt ->
        buildJsonObject {
            put("success", true)
            put("created", receipt.created)
            put("status", receipt.status)
            put("connectionId", receipt.connection.connectionId)
            put("firstSystemId", receipt.connection.firstSystemId)
            if (receipt.connection.firstSystemName == null) {
                put("firstSystemName", JsonNull)
            } else {
                put("firstSystemName", receipt.connection.firstSystemName)
            }
            put("secondSystemId", receipt.connection.secondSystemId)
            if (receipt.connection.secondSystemName == null) {
                put("secondSystemName", JsonNull)
            } else {
                put("secondSystemName", receipt.connection.secondSystemName)
            }
        }.toString()
    })

    companion object {
        const val NAME = "create_wormhole"
    }
}

private fun viewJson(viewId: String, label: String, current: Boolean): String = buildJsonObject {
    put("viewId", viewId)
    put("label", label)
    put("current", current)
}.toString()
