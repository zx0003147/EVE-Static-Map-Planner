package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.control.ClearMissionCommand
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CreateViewCommand
import dev.evestaticmapplanner.control.CreateWormholeCommand
import dev.evestaticmapplanner.control.CreateWormholeReceipt
import dev.evestaticmapplanner.control.GetActiveMissionsRequest
import dev.evestaticmapplanner.control.GetCurrentViewRequest
import dev.evestaticmapplanner.control.ListViewsRequest
import dev.evestaticmapplanner.control.ListWormholesRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.MissionMutationReceipt
import dev.evestaticmapplanner.control.MissionSummaryDto
import dev.evestaticmapplanner.control.PlanningViewDto
import dev.evestaticmapplanner.control.RenameViewCommand
import dev.evestaticmapplanner.control.SwitchViewCommand
import dev.evestaticmapplanner.control.WormholeConnectionDto
import dev.evestaticmapplanner.control.mission.MissionId
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionManagementToolsTest {
    @Test
    fun `View Mission and Wormhole tools stay on the in-process MapControlService`() = runBlocking {
        val calls = SessionCalls()
        val service = sessionService(calls)

        assertTrue(ListViewsTool(service).execute(ListViewsTool.Args()).contains("\"label\":\"Primary\""))
        assertTrue(GetCurrentViewTool(service).execute(GetCurrentViewTool.Args()).contains("\"current\":true"))
        CreateViewTool(service).execute(CreateViewTool.Args("Scout"))
        RenameViewTool(service).execute(RenameViewTool.Args("view-2", "Intel"))
        SwitchViewTool(service).execute(SwitchViewTool.Args("view-2"))
        assertTrue(GetActiveMissionsTool(service).execute(GetActiveMissionsTool.Args()).contains("mission-1"))
        ClearMissionTool(service).execute(ClearMissionTool.Args("mission-1"))
        assertTrue(ListWormholesTool(service).execute(ListWormholesTool.Args()).contains("wormhole:1:2"))
        assertTrue(CreateWormholeTool(service).execute(CreateWormholeTool.Args(1, 2)).contains("\"created\":true"))

        assertEquals("Scout", calls.created.single().label)
        assertEquals("Intel", calls.renamed.single().label)
        assertEquals("view-2", calls.switched.single().viewId)
        assertEquals(MissionId("mission-1"), calls.cleared.single().missionId)
        assertEquals(1 to 2, calls.wormholes.single().let { it.fromSystemId to it.toSystemId })
    }
}

private data class SessionCalls(
    val created: MutableList<CreateViewCommand> = mutableListOf(),
    val renamed: MutableList<RenameViewCommand> = mutableListOf(),
    val switched: MutableList<SwitchViewCommand> = mutableListOf(),
    val cleared: MutableList<ClearMissionCommand> = mutableListOf(),
    val wormholes: MutableList<CreateWormholeCommand> = mutableListOf(),
)

private fun sessionService(calls: SessionCalls): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "listViews" -> {
            val request = arguments!!.first() as ListViewsRequest
            ControlResult.Success(request.requestId, listOf(PlanningViewDto("view-1", "Primary", true)))
        }
        "getCurrentView" -> {
            val request = arguments!!.first() as GetCurrentViewRequest
            ControlResult.Success(request.requestId, PlanningViewDto("view-1", "Primary", true))
        }
        "createView" -> {
            val command = arguments!!.first() as CreateViewCommand
            calls.created += command
            ControlResult.Success(command.requestId, PlanningViewDto("view-2", command.label ?: "View 2", true))
        }
        "renameView" -> {
            val command = arguments!!.first() as RenameViewCommand
            calls.renamed += command
            ControlResult.Success(command.requestId, PlanningViewDto(command.viewId, command.label, true))
        }
        "switchView" -> {
            val command = arguments!!.first() as SwitchViewCommand
            calls.switched += command
            ControlResult.Success(command.requestId, PlanningViewDto(command.viewId, "Scout", true))
        }
        "getActiveMissions" -> {
            val request = arguments!!.first() as GetActiveMissionsRequest
            ControlResult.Success(
                request.requestId,
                listOf(MissionSummaryDto(MissionId("mission-1"), "Temp", 0, 1, 1, 0, 0, 2, "view-1")),
            )
        }
        "clearMission" -> {
            val command = arguments!!.first() as ClearMissionCommand
            calls.cleared += command
            ControlResult.Success(command.requestId, MissionMutationReceipt(command.missionId), missionRevision = 2)
        }
        "listWormholes" -> {
            val request = arguments!!.first() as ListWormholesRequest
            ControlResult.Success(
                request.requestId,
                listOf(WormholeConnectionDto("wormhole:1:2", 1, 2, "One", "Two")),
            )
        }
        "createWormhole" -> {
            val command = arguments!!.first() as CreateWormholeCommand
            calls.wormholes += command
            ControlResult.Success(
                command.requestId,
                CreateWormholeReceipt(WormholeConnectionDto("wormhole:1:2", 1, 2, "One", "Two"), true, "created"),
            )
        }
        "toString" -> "SessionMapControlService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService
