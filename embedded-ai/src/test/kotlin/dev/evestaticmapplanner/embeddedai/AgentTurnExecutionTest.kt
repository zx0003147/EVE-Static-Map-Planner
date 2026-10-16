package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.control.ControlResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTurnExecutionTest {
    @Test
    fun `tracker publishes ordered lifecycle snapshots without tool arguments`() {
        val snapshots = mutableListOf<AgentTurnExecution>()
        val tracker = AgentTurnExecutionTracker()
        tracker.reset(object : EmbeddedAiTurnObserver {
            override fun toolExecutionChanged(execution: AgentTurnExecution) {
                snapshots += execution
            }
        })

        val search = tracker.toolStarted(SearchSystemTool.NAME, "search-call")
        tracker.toolSucceeded(search)
        val marker = tracker.toolStarted(AddMissionMarkerTool.NAME, "marker-call")
        tracker.toolFailed(marker, "The marker was not applied.")

        assertEquals(
            listOf(
                listOf(AgentToolCallStatus.RUNNING),
                listOf(AgentToolCallStatus.SUCCESS),
                listOf(AgentToolCallStatus.SUCCESS, AgentToolCallStatus.RUNNING),
                listOf(AgentToolCallStatus.SUCCESS, AgentToolCallStatus.FAILURE),
            ),
            snapshots.map { snapshot -> snapshot.toolCalls.map(AgentToolCall::status) },
        )
        assertEquals(listOf(SearchSystemTool.NAME, AddMissionMarkerTool.NAME), snapshots.last().toolCalls.map(AgentToolCall::toolName))
        assertTrue(snapshots.toString().contains("The marker was not applied."))
        assertTrue(snapshots.toString().contains("systemId").not())
    }

    @Test
    fun `active tool becomes cancelled without ever becoming successful`() {
        val tracker = AgentTurnExecutionTracker()
        val callId = tracker.toolStarted(FitMissionTool.NAME, "fit-call")
        tracker.toolWaitingForConfirmation(callId)

        tracker.cancelActive("Cancelled with the AI request.")

        val call = tracker.snapshot().toolCalls.single()
        assertEquals(AgentToolCallStatus.CANCELLED, call.status)
        assertFalse(tracker.snapshot().hasSuccessfulMutation)
    }

    @Test
    fun `tracker derives mutation outcomes from registered tool risk metadata`() {
        val tracker = AgentTurnExecutionTracker()

        tracker.toolSucceeded(tracker.toolStarted(SearchSystemTool.NAME))
        repeat(3) {
            tracker.toolSucceeded(tracker.toolStarted(AddMissionMarkerTool.NAME))
        }
        tracker.toolFailed(tracker.toolStarted(FitMissionTool.NAME))

        val execution = tracker.snapshot()
        assertEquals(5, execution.toolCallCount)
        assertEquals(4, execution.mutationCallCount)
        assertEquals(3, execution.successfulMutationCount)
        assertEquals(1, execution.failedMutationCount)
        assertEquals(
            listOf(
                AgentToolCallStatus.SUCCESS,
                AgentToolCallStatus.SUCCESS,
                AgentToolCallStatus.SUCCESS,
                AgentToolCallStatus.SUCCESS,
                AgentToolCallStatus.FAILURE,
            ),
            execution.toolCalls.map(AgentToolCall::status),
        )
    }

    @Test
    fun `structured mutation expectation accepts exact JSON and rejects unstructured text`() {
        assertEquals(
            AgentMutationExpectation.REQUIRED,
            parseMutationExpectation("{\"requiresMapMutation\":true}"),
        )
        assertEquals(
            AgentMutationExpectation.NOT_REQUIRED,
            parseMutationExpectation("{\"requiresMapMutation\":false}"),
        )
        assertNull(parseMutationExpectation("I will do it soon."))
    }

    @Test
    fun `receipt outcome is authoritative for mutation success tracking`() = runBlocking {
        val tracker = AgentTurnExecutionTracker()

        val result = withContext(AgentTurnContext(tracker, object : EmbeddedAiTurnObserver {})) {
            executePlannerQuery(
                toolName = AddMissionMarkerTool.NAME,
                diagnostics = tracker,
                query = { ControlResult.Success("request-1", "not-applied") },
                serialize = { it },
                resultSucceeded = { false },
            )
        }

        assertEquals("not-applied", result)
        assertEquals(1, tracker.snapshot().toolCallCount)
        assertEquals(1, tracker.snapshot().mutationCallCount)
        assertEquals(1, tracker.snapshot().failedMutationCount)
        assertEquals(AgentToolCallStatus.FAILURE, tracker.snapshot().toolCalls.single().status)
    }

    @Test
    fun `successful map receipt creates a successful mutation record`() = runBlocking {
        val tracker = AgentTurnExecutionTracker()

        val result = withContext(AgentTurnContext(tracker, object : EmbeddedAiTurnObserver {})) {
            executePlannerQuery(
                toolName = AddMissionMarkerTool.NAME,
                diagnostics = tracker,
                query = { ControlResult.Success("request-1", "applied") },
                serialize = { it },
            )
        }

        assertEquals("applied", result)
        assertTrue(tracker.snapshot().hasSuccessfulMutation)
        assertEquals(AgentToolCallStatus.SUCCESS, tracker.snapshot().toolCalls.single().status)
    }

    @Test
    fun `multiple calls retain two successes followed by one failure`() {
        val tracker = AgentTurnExecutionTracker()
        tracker.toolSucceeded(tracker.toolStarted(SearchSystemTool.NAME, "a"))
        tracker.toolSucceeded(tracker.toolStarted(CalculateNormalRouteTool.NAME, "b"))
        tracker.toolFailed(tracker.toolStarted(ShowNormalRouteTool.NAME, "c"), "Map update failed.")

        assertEquals(
            listOf(AgentToolCallStatus.SUCCESS, AgentToolCallStatus.SUCCESS, AgentToolCallStatus.FAILURE),
            tracker.snapshot().toolCalls.map(AgentToolCall::status),
        )
    }
}
