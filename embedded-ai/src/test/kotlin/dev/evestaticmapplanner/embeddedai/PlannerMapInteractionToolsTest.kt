package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.testing.tools.getMockExecutor
import dev.evestaticmapplanner.control.AddMissionMarkerCommand
import dev.evestaticmapplanner.control.AnyRouteDto
import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.CapitalRouteDto
import dev.evestaticmapplanner.control.CapitalRouteLegDto
import dev.evestaticmapplanner.control.ControlError
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.FitMissionCommand
import dev.evestaticmapplanner.control.FocusSystemCommand
import dev.evestaticmapplanner.control.GetMissionRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.MissionJumpRangeReceipt
import dev.evestaticmapplanner.control.MissionMarkerReceipt
import dev.evestaticmapplanner.control.MissionMutationReceipt
import dev.evestaticmapplanner.control.MissionRouteReceipt
import dev.evestaticmapplanner.control.MissionSummaryDto
import dev.evestaticmapplanner.control.NormalRouteDto
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.ShowCapitalRouteCommand
import dev.evestaticmapplanner.control.ShowJumpRangeCommand
import dev.evestaticmapplanner.control.ShowNormalRouteCommand
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionJumpRangeId
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.control.mission.MissionRouteId
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlannerMapInteractionToolsTest {
    @Test
    fun `all eight Phase 4 tools are thin MapControlService adapters with compact results`() = runBlocking {
        val calls = MapCalls()
        val tools = PlannerToolSet(recordingMapService(calls))

        val focused = tools.focusSystem.execute(FocusSystemTool.Args(JITA.systemId))
        val begun = tools.beginMission.execute(BeginMissionTool.Args("Jita to Amarr"))
        val mission = tools.getMission.execute(GetMissionTool.Args(MISSION_ID.value))
        val normal = tools.showNormalRoute.execute(
            ShowNormalRouteTool.Args(MISSION_ID.value, JITA.systemId, AMARR.systemId),
        )
        val capital = tools.showCapitalRoute.execute(
            ShowCapitalRouteTool.Args(MISSION_ID.value, ONE_DQ.systemId, NOL.systemId, effectiveRangeLy = 6.0),
        )
        val range = tools.showJumpRange.execute(
            ShowJumpRangeTool.Args(MISSION_ID.value, ONE_DQ.systemId, 6.0, "6 LY"),
        )
        val marker = tools.addMissionMarker.execute(
            AddMissionMarkerTool.Args(MISSION_ID.value, JITA.systemId, label = "Rally"),
        )
        val fitted = tools.fitMission.execute(FitMissionTool.Args(MISSION_ID.value))

        assertEquals(listOf(JITA.systemId), calls.focus.map(FocusSystemCommand::systemId))
        assertEquals(listOf("Jita to Amarr"), calls.begin.map(BeginMissionCommand::title))
        assertEquals(listOf(MISSION_ID), calls.getMission.map(GetMissionRequest::missionId))
        assertFalse(calls.normal.single().useAnsiblex)
        assertFalse(calls.normal.single().useWormholes)
        assertEquals(6.0, calls.capital.single().effectiveRangeLy)
        assertEquals(6.0, calls.jumpRange.single().effectiveRangeLy)
        assertEquals(MissionMarkerRole.RALLY, calls.marker.single().role)
        assertEquals(listOf(MISSION_ID), calls.fit.map(FitMissionCommand::missionId))

        assertTrue(focused.contains("\"name\":\"Jita\""))
        assertTrue(begun.contains("\"missionId\":\"mission-1\""))
        assertTrue(mission.contains("\"routeOverlays\":[]"))
        assertTrue(normal.contains("\"routeType\":\"NORMAL\""))
        assertTrue(normal.contains("\"jumpCount\":11"))
        assertTrue(capital.contains("\"totalDistanceLy\":9.75"))
        assertTrue(range.contains("\"reachableSystemCount\":42"))
        assertTrue(marker.contains("\"role\":\"RALLY\""))
        assertEquals("{\"success\":true,\"missionId\":\"mission-1\"}", fitted)
    }

    @Test
    fun `tool failures preserve safe Planner error codes`() = runBlocking {
        val failure = assertFailsWith<EmbeddedAiToolException> {
            GetMissionTool(failingMissionService()).execute(GetMissionTool.Args("missing"))
        }

        assertEquals("MISSION_NOT_FOUND: Mission was not found", failure.message)
        assertFalse(failure.stackTraceToString().contains("database", ignoreCase = true))
    }

    @Test
    fun `Phase 4 failures expose stable safe operation error codes`() = runBlocking {
        suspend fun failure(block: suspend () -> String) = assertFailsWith<EmbeddedAiToolException> { block() }.message

        assertTrue(
            failure {
                FocusSystemTool(failingOperationService("focusSystem", ControlErrorCode.APP_NOT_READY, "Map is not ready"))
                    .execute(FocusSystemTool.Args(JITA.systemId))
            }!!.startsWith("MAP_OPERATION_FAILED:"),
        )
        assertTrue(
            failure {
                ShowCapitalRouteTool(
                    failingOperationService("showCapitalRoute", ControlErrorCode.INVALID_ARGUMENT, "Effective range is invalid"),
                ).execute(ShowCapitalRouteTool.Args(MISSION_ID.value, ONE_DQ.systemId, NOL.systemId, effectiveRangeLy = -1.0))
            }!!.startsWith("INVALID_RANGE:"),
        )
        assertTrue(
            failure {
                ShowNormalRouteTool(
                    failingOperationService("showNormalRoute", ControlErrorCode.ROUTE_NOT_FOUND, "No normal route was found"),
                ).execute(ShowNormalRouteTool.Args(MISSION_ID.value, JITA.systemId, AMARR.systemId))
            }!!.startsWith("ROUTE_UNREACHABLE:"),
        )
        assertTrue(
            failure {
                AddMissionMarkerTool(
                    failingOperationService("addMissionMarker", ControlErrorCode.INVALID_MARKER_DATA, "Marker role is invalid"),
                ).execute(AddMissionMarkerTool.Args(MISSION_ID.value, JITA.systemId))
            }!!.startsWith("INVALID_MARKER_ROLE:"),
        )
        assertTrue(
            failure {
                BeginMissionTool(
                    failingOperationService("beginMission", ControlErrorCode.INTERNAL_ERROR, "The control operation failed"),
                ).execute(BeginMissionTool.Args("Mission"))
            }!!.startsWith("MISSION_OPERATION_FAILED:"),
        )
        assertTrue(
            failure {
                ShowJumpRangeTool(
                    failingOperationService("showJumpRange", ControlErrorCode.NOT_FOUND, "Solar system was not found"),
                ).execute(ShowJumpRangeTool.Args(MISSION_ID.value, ONE_DQ.systemId, 6.0))
            }!!.startsWith("SYSTEM_NOT_FOUND:"),
        )
    }

    @Test
    fun `Koog focus request resolves the name and changes only the viewport`() = runBlocking {
        val calls = MapCalls()
        val tools = PlannerToolSet(recordingMapService(calls))
        val question = "Focus Jita on the map."
        val executor = getMockExecutor {
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("Jita")) onRequestEquals question
            mockLLMToolCall(tools.focusSystem, FocusSystemTool.Args(JITA.systemId)) onRequestContains "\"name\":\"Jita\""
            mockLLMAnswer("Focused Jita on the map.") onRequestContains "\"success\":true"
        }
        try {
            assertEquals("Focused Jita on the map.", createKoogAgent(tools, executor).run(question))
            assertEquals(1, calls.focus.size)
            assertTrue(calls.begin.isEmpty())
        } finally {
            executor.close()
        }
    }

    @Test
    fun `Koog displays a normal route in one Mission then fits it`() = runBlocking {
        val calls = MapCalls()
        val tools = PlannerToolSet(recordingMapService(calls))
        val question = "Show the Jita to Amarr route on the map."
        val executor = getMockExecutor {
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("Jita")) onRequestEquals question
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("Amarr")) onRequestContains "\"name\":\"Jita\""
            mockLLMToolCall(tools.beginMission, BeginMissionTool.Args("Jita to Amarr")) onRequestContains "\"name\":\"Amarr\""
            mockLLMToolCall(
                tools.showNormalRoute,
                ShowNormalRouteTool.Args(MISSION_ID.value, JITA.systemId, AMARR.systemId),
            ) onRequestContains "\"title\":\"Jita to Amarr\""
            mockLLMToolCall(tools.fitMission, FitMissionTool.Args(MISSION_ID.value)) onRequestContains "\"jumpCount\":11"
            mockLLMAnswer("Displayed the 11-jump route and fitted the map.") onRequestContains "\"success\":true"
        }
        try {
            assertEquals(
                "Displayed the 11-jump route and fitted the map.",
                createKoogAgent(tools, executor).run(question),
            )
            assertEquals(1, calls.begin.size)
            assertEquals(1, calls.normal.size)
            assertEquals(1, calls.fit.size)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `multi-turn pronoun displays the route described by recent conversation`() = runBlocking {
        val calls = MapCalls()
        val tools = PlannerToolSet(recordingMapService(calls))
        val question = buildAgentPrompt(
            history = listOf(
                AgentConversationMessage(EmbeddedAiMessageRole.USER, "Jita 到 Amarr 几跳？"),
                AgentConversationMessage(EmbeddedAiMessageRole.ASSISTANT, "Jita 到 Amarr 共 11 跳。"),
            ),
            currentUserMessage = "把它显示出来。",
        )
        val executor = getMockExecutor {
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("Jita")) onRequestEquals question
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("Amarr")) onRequestContains "\"name\":\"Jita\""
            mockLLMToolCall(tools.beginMission, BeginMissionTool.Args("Jita to Amarr")) onRequestContains "\"name\":\"Amarr\""
            mockLLMToolCall(
                tools.showNormalRoute,
                ShowNormalRouteTool.Args(MISSION_ID.value, JITA.systemId, AMARR.systemId),
            ) onRequestContains "\"title\":\"Jita to Amarr\""
            mockLLMToolCall(tools.fitMission, FitMissionTool.Args(MISSION_ID.value)) onRequestContains "\"jumpCount\":11"
            mockLLMAnswer("已显示 Jita → Amarr 路线。") onRequestContains "\"success\":true"
        }
        try {
            assertEquals("已显示 Jita → Amarr 路线。", createKoogAgent(tools, executor).run(question))
            assertEquals(1, calls.normal.size)
            assertEquals(1, calls.fit.size)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `Koog displays capital route only with explicit range`() = runBlocking {
        val calls = MapCalls()
        val tools = PlannerToolSet(recordingMapService(calls))
        val question = "Show the 1DQ1-A to NOL-M9 capital route at 6 LY on the map."
        val executor = getMockExecutor {
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("1DQ1-A")) onRequestEquals question
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("NOL-M9")) onRequestContains "\"name\":\"1DQ1-A\""
            mockLLMToolCall(tools.beginMission, BeginMissionTool.Args("1DQ1-A to NOL-M9")) onRequestContains "\"name\":\"NOL-M9\""
            mockLLMToolCall(
                tools.showCapitalRoute,
                ShowCapitalRouteTool.Args(MISSION_ID.value, ONE_DQ.systemId, NOL.systemId, effectiveRangeLy = 6.0),
            ) onRequestContains "\"title\":\"1DQ1-A to NOL-M9\""
            mockLLMToolCall(tools.fitMission, FitMissionTool.Args(MISSION_ID.value)) onRequestContains "\"totalDistanceLy\":9.75"
            mockLLMAnswer("Displayed the capital route and fitted the map.") onRequestContains "\"success\":true"
        }
        try {
            createKoogAgent(tools, executor).run(question)
            assertEquals(6.0, calls.capital.single().effectiveRangeLy)
            assertEquals(1, calls.fit.size)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `Koog displays explicit jump range and fits its Mission`() = runBlocking {
        val calls = MapCalls()
        val tools = PlannerToolSet(recordingMapService(calls))
        val question = "Show a 6 LY jump range from 1DQ1-A."
        val executor = getMockExecutor {
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("1DQ1-A")) onRequestEquals question
            mockLLMToolCall(tools.beginMission, BeginMissionTool.Args("1DQ1-A 6 LY jump range")) onRequestContains "\"name\":\"1DQ1-A\""
            mockLLMToolCall(
                tools.showJumpRange,
                ShowJumpRangeTool.Args(MISSION_ID.value, ONE_DQ.systemId, 6.0, "6 LY"),
            ) onRequestContains "\"title\":\"1DQ1-A 6 LY jump range\""
            mockLLMToolCall(tools.fitMission, FitMissionTool.Args(MISSION_ID.value)) onRequestContains "\"reachableSystemCount\":42"
            mockLLMAnswer("Displayed the 6 LY range and fitted the map.") onRequestContains "\"success\":true"
        }
        try {
            createKoogAgent(tools, executor).run(question)
            assertEquals(6.0, calls.jumpRange.single().effectiveRangeLy)
            assertEquals(1, calls.fit.size)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `Koog adds only a temporary Mission marker`() = runBlocking {
        val calls = MapCalls()
        val tools = PlannerToolSet(recordingMapService(calls))
        val question = "Put a temporary rally marker at Jita."
        val executor = getMockExecutor {
            mockLLMToolCall(tools.searchSystem, SearchSystemTool.Args("Jita")) onRequestEquals question
            mockLLMToolCall(tools.beginMission, BeginMissionTool.Args("Jita rally point")) onRequestContains "\"name\":\"Jita\""
            mockLLMToolCall(
                tools.addMissionMarker,
                AddMissionMarkerTool.Args(MISSION_ID.value, JITA.systemId, label = "Rally"),
            ) onRequestContains "\"title\":\"Jita rally point\""
            mockLLMAnswer("Added a temporary rally marker at Jita.") onRequestContains "\"role\":\"RALLY\""
        }
        try {
            createKoogAgent(tools, executor).run(question)
            assertEquals(MissionMarkerRole.RALLY, calls.marker.single().role)
            assertEquals(1, calls.begin.size)
        } finally {
            executor.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancel prevents later work without rolling back a completed map tool`() = runTest {
        val calls = MapCalls()
        val tools = PlannerToolSet(recordingMapService(calls))
        val committed = CompletableDeferred<Unit>()
        val continueAgent = CompletableDeferred<Unit>()
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                object : EmbeddedAiAgent {
                    override suspend fun run(prompt: String): String {
                        tools.beginMission.execute(BeginMissionTool.Args("Committed Mission"))
                        committed.complete(Unit)
                        continueAgent.await()
                        tools.fitMission.execute(FitMissionTool.Args(MISSION_ID.value))
                        return "done"
                    }

                    override suspend fun close() = Unit
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.send("Show something")
        runCurrent()
        assertTrue(committed.isCompleted)
        controller.cancel()
        advanceUntilIdle()

        assertEquals(1, calls.begin.size)
        assertTrue(calls.fit.isEmpty())
        assertEquals("Request cancelled.", controller.state.value.response)
        controller.shutdown()
    }
}

private data class MapCalls(
    val searches: MutableList<SearchSystemsRequest> = mutableListOf(),
    val focus: MutableList<FocusSystemCommand> = mutableListOf(),
    val begin: MutableList<BeginMissionCommand> = mutableListOf(),
    val getMission: MutableList<GetMissionRequest> = mutableListOf(),
    val normal: MutableList<ShowNormalRouteCommand> = mutableListOf(),
    val capital: MutableList<ShowCapitalRouteCommand> = mutableListOf(),
    val jumpRange: MutableList<ShowJumpRangeCommand> = mutableListOf(),
    val marker: MutableList<AddMissionMarkerCommand> = mutableListOf(),
    val fit: MutableList<FitMissionCommand> = mutableListOf(),
)

private fun recordingMapService(calls: MapCalls): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "searchSystems" -> {
            val request = arguments?.first() as SearchSystemsRequest
            calls.searches += request
            ControlResult.Success(
                request.requestId,
                MAP_SYSTEMS.filter { it.name.contains(request.query, ignoreCase = true) },
            )
        }
        "focusSystem" -> {
            val command = arguments?.first() as FocusSystemCommand
            calls.focus += command
            ControlResult.Success(command.requestId, MAP_SYSTEMS.single { it.systemId == command.systemId })
        }
        "beginMission" -> {
            val command = arguments?.first() as BeginMissionCommand
            calls.begin += command
            ControlResult.Success(command.requestId, missionSummary(command.title), missionRevision = 1)
        }
        "getMission" -> {
            val request = arguments?.first() as GetMissionRequest
            calls.getMission += request
            ControlResult.Success(request.requestId, emptyMission())
        }
        "showNormalRoute" -> {
            val command = arguments?.first() as ShowNormalRouteCommand
            calls.normal += command
            ControlResult.Success(
                command.requestId,
                MissionRouteReceipt(
                    MISSION_ID,
                    MissionRouteId("normal-1"),
                    AnyRouteDto.Normal(normalRoute(command)),
                ),
                missionRevision = 2,
            )
        }
        "showCapitalRoute" -> {
            val command = arguments?.first() as ShowCapitalRouteCommand
            calls.capital += command
            ControlResult.Success(
                command.requestId,
                MissionRouteReceipt(
                    MISSION_ID,
                    MissionRouteId("capital-1"),
                    AnyRouteDto.Capital(capitalRoute(command)),
                ),
                missionRevision = 2,
            )
        }
        "showJumpRange" -> {
            val command = arguments?.first() as ShowJumpRangeCommand
            calls.jumpRange += command
            ControlResult.Success(
                command.requestId,
                MissionJumpRangeReceipt(MISSION_ID, MissionJumpRangeId("range-1"), command.originSystemId, 6.0, 42),
                missionRevision = 2,
            )
        }
        "addMissionMarker" -> {
            val command = arguments?.first() as AddMissionMarkerCommand
            calls.marker += command
            ControlResult.Success(
                command.requestId,
                MissionMarkerReceipt(MISSION_ID, MissionMarkerId("marker-1"), command.systemId, command.role),
                missionRevision = 2,
            )
        }
        "fitMission" -> {
            val command = arguments?.first() as FitMissionCommand
            calls.fit += command
            ControlResult.Success(command.requestId, MissionMutationReceipt(command.missionId), missionRevision = 2)
        }
        "toString" -> "RecordingMapService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun failingMissionService(): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "getMission" -> {
            val request = arguments?.first() as GetMissionRequest
            ControlResult.Failure(
                request.requestId,
                ControlError(ControlErrorCode.MISSION_NOT_FOUND, "Mission was not found"),
            )
        }
        "toString" -> "FailingMissionService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun failingOperationService(
    operation: String,
    code: ControlErrorCode,
    message: String,
): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        operation -> {
            val requestId = when (val request = arguments?.first()) {
                is FocusSystemCommand -> request.requestId
                is ShowCapitalRouteCommand -> request.requestId
                is ShowNormalRouteCommand -> request.requestId
                is AddMissionMarkerCommand -> request.requestId
                is BeginMissionCommand -> request.requestId
                is ShowJumpRangeCommand -> request.requestId
                else -> error("Unsupported fixture request: $request")
            }
            ControlResult.Failure(requestId, ControlError(code, message))
        }
        "toString" -> "FailingOperationService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun missionSummary(title: String) = MissionSummaryDto(
    missionId = MISSION_ID,
    title = title,
    createdAtEpochMillis = 0,
    revision = 1,
    routeCount = 0,
    jumpRangeCount = 0,
    markerCount = 0,
    referencedSystemCount = 0,
)

private fun emptyMission() = Mission(
    missionId = MISSION_ID,
    title = "Mission",
    createdAt = Instant.EPOCH,
    revision = 1,
    routes = emptyList(),
    jumpRanges = emptyList(),
    markers = emptyList(),
    referencedSystemIds = emptySet(),
)

private fun normalRoute(command: ShowNormalRouteCommand): NormalRouteDto {
    val destinationSystemId = checkNotNull(command.destinationSystemId)
    return NormalRouteDto(
        startSystemId = command.startSystemId,
        destinationSystemId = destinationSystemId,
        systemIds = listOf(command.startSystemId, 30_001_111, destinationSystemId),
        totalJumps = 11,
        stargateJumps = 11,
        ansiblexJumps = 0,
        wormholeJumps = 0,
    )
}

private fun capitalRoute(command: ShowCapitalRouteCommand): CapitalRouteDto {
    val destinationSystemId = checkNotNull(command.destinationSystemId)
    return CapitalRouteDto(
        startSystemId = command.startSystemId,
        destinationSystemId = destinationSystemId,
        effectiveRangeLy = command.effectiveRangeLy,
        systemIds = listOf(command.startSystemId, 30_004_742, destinationSystemId),
        legs = listOf(
            CapitalRouteLegDto(command.startSystemId, 30_004_742, 4.5),
            CapitalRouteLegDto(30_004_742, destinationSystemId, 5.25),
        ),
        totalJumps = 2,
        totalDistanceLy = 9.75,
    )
}

private val MISSION_ID = MissionId("mission-1")
private val JITA = SystemSummaryDto(30_000_142, "Jita", 10_000_002, 20_000_020, 0.9459)
private val AMARR = SystemSummaryDto(30_002_187, "Amarr", 10_000_043, 20_000_322, 1.0)
private val ONE_DQ = SystemSummaryDto(30_004_735, "1DQ1-A", 10_000_060, 20_000_457, -0.39)
private val NOL = SystemSummaryDto(30_004_347, "NOL-M9", 10_000_060, 20_000_430, -0.38)
private val MAP_SYSTEMS = listOf(JITA, AMARR, ONE_DQ, NOL)
