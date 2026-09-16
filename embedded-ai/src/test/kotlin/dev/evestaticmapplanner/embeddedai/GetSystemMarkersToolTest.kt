package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.testing.tools.getMockExecutor
import dev.evestaticmapplanner.control.ControlError
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.GetSystemMarkersRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.MissionMarkerSummaryDto
import dev.evestaticmapplanner.control.SavedMarkerChildSummaryDto
import dev.evestaticmapplanner.control.SavedMarkerSummaryDto
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemMarkersDto
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GetSystemMarkersToolTest {
    @Test
    fun `tool uses permission-gated MapControlService query and returns only contract marker fields`() = runBlocking {
        val calls = mutableListOf<GetSystemMarkersRequest>()
        val result = GetSystemMarkersTool(markerService(calls)).execute(GetSystemMarkersTool.Args(JITA_ID))

        assertEquals(JITA_ID, calls.single().systemId)
        assertTrue(result.contains("\"savedMarker\""))
        assertTrue(result.contains("\"name\":\"Trade Hub\""))
        assertTrue(result.contains("\"type\":\"staging\""))
        assertTrue(result.contains("\"createdBy\":\"USER\""))
        assertTrue(result.contains("\"missionId\":\"mission-1\""))
        assertTrue(result.contains("\"markerId\":\"marker-1\""))
        assertTrue(result.contains("\"role\":\"RALLY\""))
        assertTrue(result.contains("\"color\":\"GREEN\""))
    }

    @Test
    fun `Saved Marker permission denial is not presented as an empty marker result`() = runBlocking {
        val failure = assertFailsWith<EmbeddedAiToolException> {
            GetSystemMarkersTool(deniedMarkerService()).execute(GetSystemMarkersTool.Args(JITA_ID))
        }

        assertEquals("CAPABILITY_DENIED: AI Saved Marker capability is denied", failure.message)
    }

    @Test
    fun `Koog resolves Jita before querying its markers`() = runBlocking {
        val calls = mutableListOf<GetSystemMarkersRequest>()
        val tools = PlannerToolSet(markerService(calls))
        val question = "Jita has which markers?"
        val answer = "Jita has one Saved Marker and one temporary Mission marker."
        val executor = getMockExecutor {
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("Jita")) onRequestEquals question
            mockLLMToolCall(tools.getSystemMarkers, GetSystemMarkersTool.Args(JITA_ID)) onRequestContains "\"name\":\"Jita\""
            mockLLMAnswer(answer) onRequestContains "\"markerId\":\"marker-1\""
        }
        try {
            assertEquals(answer, createKoogAgent(tools, executor).run(question))
            assertEquals(JITA_ID, calls.single().systemId)
        } finally {
            executor.close()
        }
    }
}

private fun markerService(calls: MutableList<GetSystemMarkersRequest>): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "searchSystems" -> {
            val request = arguments?.first() as SearchSystemsRequest
            ControlResult.Success(
                request.requestId,
                listOf(SystemSummaryDto(JITA_ID, "Jita", 10_000_002, 20_000_020, 0.9459)),
            )
        }
        "getSystemMarkers" -> {
            val request = arguments?.first() as GetSystemMarkersRequest
            calls += request
            ControlResult.Success(request.requestId, systemMarkers())
        }
        "toString" -> "MarkerService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun deniedMarkerService(): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "getSystemMarkers" -> {
            val request = arguments?.first() as GetSystemMarkersRequest
            ControlResult.Failure(
                request.requestId,
                ControlError(ControlErrorCode.CAPABILITY_DENIED, "AI Saved Marker capability is denied"),
            )
        }
        "toString" -> "DeniedMarkerService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun systemMarkers() = SystemMarkersDto(
    systemId = JITA_ID,
    savedMarker = SavedMarkerSummaryDto(
        systemId = JITA_ID,
        name = "Trade Hub",
        color = MarkerColor.BLUE,
        notes = "Persistent note",
        children = listOf(SavedMarkerChildSummaryDto("child-1", "staging", 0)),
        createdBy = SavedMarkerCreatedBy.USER,
    ),
    missionMarkers = listOf(
        MissionMarkerSummaryDto(
            missionId = MissionId("mission-1"),
            markerId = MissionMarkerId("marker-1"),
            systemId = JITA_ID,
            role = MissionMarkerRole.RALLY,
            label = "Temporary rally",
            notes = "Mission only",
            color = MarkerColor.GREEN,
        ),
    ),
)

private const val JITA_ID = 30_000_142
