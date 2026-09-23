package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.control.ControlResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentTurnExecutionTest {
    @Test
    fun `tracker derives mutation outcomes from registered tool risk metadata`() {
        val tracker = AgentTurnExecutionTracker()

        tracker.plannerToolStarted(SearchSystemTool.NAME)
        tracker.plannerToolSucceeded(SearchSystemTool.NAME)
        repeat(3) {
            tracker.plannerToolStarted(AddMissionMarkerTool.NAME)
            tracker.plannerToolSucceeded(AddMissionMarkerTool.NAME)
        }
        tracker.plannerToolStarted(FitMissionTool.NAME)
        tracker.plannerToolFailed(FitMissionTool.NAME)

        assertEquals(
            AgentTurnExecution(
                toolCallCount = 5,
                mutationCallCount = 4,
                successfulMutationCount = 3,
                failedMutationCount = 1,
            ),
            tracker.snapshot(),
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

        val result = executePlannerQuery(
            toolName = AddMissionMarkerTool.NAME,
            diagnostics = tracker,
            query = { ControlResult.Success("request-1", "not-applied") },
            serialize = { it },
            resultSucceeded = { false },
        )

        assertEquals("not-applied", result)
        assertEquals(
            AgentTurnExecution(
                toolCallCount = 1,
                mutationCallCount = 1,
                failedMutationCount = 1,
            ),
            tracker.snapshot(),
        )
    }
}
