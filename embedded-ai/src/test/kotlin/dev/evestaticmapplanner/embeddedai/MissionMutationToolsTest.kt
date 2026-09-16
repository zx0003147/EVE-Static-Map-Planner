package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.control.ClearMissionJumpRangesCommand
import dev.evestaticmapplanner.control.ClearMissionMarkersCommand
import dev.evestaticmapplanner.control.ClearMissionRoutesCommand
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.MissionMutationReceipt
import dev.evestaticmapplanner.control.RemoveJumpRangeCommand
import dev.evestaticmapplanner.control.RemoveMissionMarkerCommand
import dev.evestaticmapplanner.control.RemoveMissionRouteCommand
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissionMutationToolsTest {
    @Test
    fun `six temporary tools are thin adapters and preserve authoritative IDs`() = runBlocking {
        val calls = MutationCalls()
        val tools = PlannerToolSet(recordingMutationService(calls))

        val removedRoute = tools.removeMissionRoute.execute(RemoveMissionRouteTool.Args(MISSION_ID, ROUTE_ID))
        val clearedRoutes = tools.clearMissionRoutes.execute(ClearMissionRoutesTool.Args(MISSION_ID))
        val removedRange = tools.removeJumpRange.execute(RemoveJumpRangeTool.Args(MISSION_ID, OVERLAY_ID))
        val clearedRanges = tools.clearMissionJumpRanges.execute(ClearMissionJumpRangesTool.Args(MISSION_ID))
        val removedMarker = tools.removeMissionMarker.execute(RemoveMissionMarkerTool.Args(MISSION_ID, MARKER_ID))
        val clearedMarkers = tools.clearMissionMarkers.execute(ClearMissionMarkersTool.Args(MISSION_ID))

        assertEquals(MISSION_ID, calls.removeRoute.single().missionId.value)
        assertEquals(ROUTE_ID, calls.removeRoute.single().routeId.value)
        assertEquals(MISSION_ID, calls.clearRoutes.single().missionId.value)
        assertEquals(OVERLAY_ID, calls.removeRange.single().jumpRangeId.value)
        assertEquals(MISSION_ID, calls.clearRanges.single().missionId.value)
        assertEquals(MARKER_ID, calls.removeMarker.single().markerId.value)
        assertEquals(MISSION_ID, calls.clearMarkers.single().missionId.value)

        assertTrue(removedRoute.contains("\"routeId\":\"$ROUTE_ID\""))
        assertEquals("{\"success\":true,\"missionId\":\"$MISSION_ID\"}", clearedRoutes)
        assertTrue(removedRange.contains("\"overlayId\":\"$OVERLAY_ID\""))
        assertEquals("{\"success\":true,\"missionId\":\"$MISSION_ID\"}", clearedRanges)
        assertTrue(removedMarker.contains("\"markerId\":\"$MARKER_ID\""))
        assertEquals("{\"success\":true,\"missionId\":\"$MISSION_ID\"}", clearedMarkers)
    }

    @Test
    fun `temporary deletion tools do not require the confirmation gateway`() {
        val temporaryNames = setOf(
            RemoveMissionRouteTool.NAME,
            ClearMissionRoutesTool.NAME,
            RemoveJumpRangeTool.NAME,
            ClearMissionJumpRangesTool.NAME,
            RemoveMissionMarkerTool.NAME,
            ClearMissionMarkersTool.NAME,
        )

        assertTrue(PlannerToolPermissions.registered.filter { it.name in temporaryNames }.all {
            it.risk == PlannerToolRisk.TEMPORARY_UI && !it.risk.requiresConfirmation
        })
    }
}

private data class MutationCalls(
    val removeRoute: MutableList<RemoveMissionRouteCommand> = mutableListOf(),
    val clearRoutes: MutableList<ClearMissionRoutesCommand> = mutableListOf(),
    val removeRange: MutableList<RemoveJumpRangeCommand> = mutableListOf(),
    val clearRanges: MutableList<ClearMissionJumpRangesCommand> = mutableListOf(),
    val removeMarker: MutableList<RemoveMissionMarkerCommand> = mutableListOf(),
    val clearMarkers: MutableList<ClearMissionMarkersCommand> = mutableListOf(),
)

private fun recordingMutationService(calls: MutationCalls): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    val command = arguments?.first()
    when (method.name) {
        "removeMissionRoute" -> (command as RemoveMissionRouteCommand).also(calls.removeRoute::add)
        "clearMissionRoutes" -> (command as ClearMissionRoutesCommand).also(calls.clearRoutes::add)
        "removeJumpRange" -> (command as RemoveJumpRangeCommand).also(calls.removeRange::add)
        "clearMissionJumpRanges" -> (command as ClearMissionJumpRangesCommand).also(calls.clearRanges::add)
        "removeMissionMarker" -> (command as RemoveMissionMarkerCommand).also(calls.removeMarker::add)
        "clearMissionMarkers" -> (command as ClearMissionMarkersCommand).also(calls.clearMarkers::add)
        "toString" -> return@newProxyInstance "MutationService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }.let { mutation ->
        ControlResult.Success(
            mutation.requestId,
            MissionMutationReceipt(
                when (mutation) {
                    is RemoveMissionRouteCommand -> mutation.missionId
                    is ClearMissionRoutesCommand -> mutation.missionId
                    is RemoveJumpRangeCommand -> mutation.missionId
                    is ClearMissionJumpRangesCommand -> mutation.missionId
                    is RemoveMissionMarkerCommand -> mutation.missionId
                    is ClearMissionMarkersCommand -> mutation.missionId
                    else -> error("Unexpected mutation")
                },
            ),
            missionRevision = 2,
        )
    }
} as MapControlService

private const val MISSION_ID = "mission-1"
private const val ROUTE_ID = "route-1"
private const val OVERLAY_ID = "overlay-1"
private const val MARKER_ID = "marker-1"
