package dev.evestaticmapplanner.embeddedai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiActionConfirmationServiceTest {
    @Test
    fun `protected call reports waiting running and success around confirmation`() = runTest {
        val snapshots = mutableListOf<AgentTurnExecution>()
        val tracker = AgentTurnExecutionTracker()
        val observer = object : EmbeddedAiTurnObserver {
            override fun toolExecutionChanged(execution: AgentTurnExecution) {
                snapshots += execution
            }
        }
        tracker.reset(observer)
        val service = AiActionConfirmationService()
        service.startRequest("request-1")

        val result = withContext(AgentTurnContext(tracker, observer)) {
            val pendingResult = async {
                service.confirmAndExecute(action("{\"systemId\":30000142}")) { "{\"success\":true}" }
            }
            runCurrent()
            assertEquals(AgentToolCallStatus.WAITING_CONFIRMATION, snapshots.last().toolCalls.single().status)
            assertTrue(service.approve(service.pending.value!!.actionId))
            pendingResult.await()
        }

        assertEquals("{\"success\":true}", result)
        assertTrue(snapshots.any { it.toolCalls.single().status == AgentToolCallStatus.RUNNING })
        assertEquals(AgentToolCallStatus.SUCCESS, snapshots.last().toolCalls.single().status)
    }

    @Test
    fun `denied protected call ends cancelled and never reports success`() = runTest {
        val snapshots = mutableListOf<AgentTurnExecution>()
        val tracker = AgentTurnExecutionTracker()
        val observer = object : EmbeddedAiTurnObserver {
            override fun toolExecutionChanged(execution: AgentTurnExecution) {
                snapshots += execution
            }
        }
        tracker.reset(observer)
        val service = AiActionConfirmationService()
        service.startRequest("request-1")
        var executed = false

        val output = withContext(AgentTurnContext(tracker, observer)) {
            val pendingResult = async {
                service.confirmAndExecute(action("{\"systemId\":30000142}")) {
                    executed = true
                    "{\"success\":true}"
                }
            }
            runCurrent()
            assertEquals(AgentToolCallStatus.WAITING_CONFIRMATION, snapshots.last().toolCalls.single().status)
            assertTrue(service.deny(service.pending.value!!.actionId))
            pendingResult.await()
        }

        assertTrue(output.contains("cancelled"))
        assertFalse(executed)
        assertEquals(AgentToolCallStatus.CANCELLED, snapshots.last().toolCalls.single().status)
        assertTrue(snapshots.none { it.toolCalls.single().status == AgentToolCallStatus.SUCCESS })
    }

    @Test
    fun `approval is bound to one request tool and normalized argument set`() = runTest {
        val audit = mutableListOf<String>()
        val service = AiActionConfirmationService(audit::add)
        service.startRequest("request-1")
        var executions = 0
        val first = async {
            service.confirmAndExecute(action("{\"systemId\":30000142}")) {
                executions++
                "{\"success\":true}"
            }
        }
        runCurrent()

        val pending = service.pending.value!!
        assertEquals("create_saved_marker", pending.toolName)
        assertFalse(first.isCompleted)
        assertTrue(service.approve(pending.actionId))
        assertEquals("{\"success\":true}", first.await())
        assertEquals(1, executions)

        val duplicate = service.confirmAndExecute(action("{\"systemId\":30000142}")) {
            executions++
            "unexpected"
        }
        assertEquals("{\"success\":true}", duplicate)
        assertEquals(1, executions)

        val changed = async {
            service.confirmAndExecute(action("{\"systemId\":30002187}")) {
                executions++
                "changed"
            }
        }
        runCurrent()
        val changedPending = service.pending.value!!
        assertFalse(service.approve(pending.actionId))
        assertFalse(changed.isCompleted)
        assertTrue(service.deny(changedPending.actionId))
        assertTrue(changed.await().contains("cancelled"))
        assertEquals(1, executions)
        assertTrue(audit.any { "status=deduplicated" in it })
    }

    @Test
    fun `deny and invalidation fail closed without executing`() = runTest {
        val service = AiActionConfirmationService()
        service.startRequest("request-1")
        var executions = 0
        val denied = async {
            service.confirmAndExecute(action("{\"name\":\"Trade\"}")) {
                executions++
                "unexpected"
            }
        }
        runCurrent()
        assertTrue(service.deny(service.pending.value!!.actionId))
        assertTrue(denied.await().contains("The action was cancelled."))

        service.startRequest("request-2")
        val invalidated = async {
            service.confirmAndExecute(action("{\"name\":\"Rally\"}")) {
                executions++
                "unexpected"
            }
        }
        runCurrent()
        service.invalidate("Provider changed.")
        assertTrue(invalidated.await().contains("Provider changed."))
        assertNull(service.pending.value)
        assertEquals(0, executions)
    }

    @Test
    fun `gateway cannot execute without an active controller request`() = runTest {
        var executed = false
        val result = AiActionConfirmationService().confirmAndExecute(action("{}")) {
            executed = true
            "unexpected"
        }

        assertFalse(executed)
        assertTrue(result.contains("confirmation is unavailable"))
    }

    @Test
    fun `a different protected action is rejected while an approved action executes`() = runTest {
        val service = AiActionConfirmationService()
        service.startRequest("request-1")
        val executionStarted = CompletableDeferred<Unit>()
        val releaseExecution = CompletableDeferred<Unit>()
        val first = async {
            service.confirmAndExecute(action("{\"systemId\":30000142}")) {
                executionStarted.complete(Unit)
                releaseExecution.await()
                "done"
            }
        }
        runCurrent()
        assertTrue(service.approve(service.pending.value!!.actionId))
        runCurrent()
        executionStarted.await()

        val second = service.confirmAndExecute(action("{\"systemId\":30002187}")) { "unexpected" }

        assertTrue(second.contains("another protected action is still active"))
        releaseExecution.complete(Unit)
        assertEquals("done", first.await())
    }

    @Test
    fun `audit records no normalized arguments or secrets`() = runTest {
        val audit = mutableListOf<String>()
        val service = AiActionConfirmationService(audit::add)
        service.startRequest("request-1")
        val result = async {
            service.confirmAndExecute(action("{\"apiKey\":\"super-secret\"}")) { "done" }
        }
        runCurrent()
        service.deny(service.pending.value!!.actionId)
        result.await()

        assertTrue(audit.isNotEmpty())
        assertTrue(audit.none { "super-secret" in it || "apiKey" in it })
    }

    private fun action(arguments: String) = AiActionRequest(
        toolName = "create_saved_marker",
        risk = PlannerToolRisk.PERSISTENT_WRITE,
        normalizedArguments = arguments,
        action = "Create saved marker",
        target = "Jita",
        effect = "Writes user data.",
    )
}
