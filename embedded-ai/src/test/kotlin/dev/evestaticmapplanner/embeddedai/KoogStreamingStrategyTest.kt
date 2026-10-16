package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.agents.testing.tools.mockLLMStream
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.buildStreamFrameFlow
import dev.evestaticmapplanner.control.ControlError
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.DeleteViewCommand
import dev.evestaticmapplanner.control.ListViewsRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.PlanningViewDto
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemSummaryDto
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoogStreamingStrategyTest {
    @Test
    fun `native Koog streaming frames are forwarded as text deltas and aggregated exactly`() = runBlocking {
        val question = "Say hello"
        val executor = getMockExecutor {
            mockLLMStream(buildStreamFrameFlow {
                emitTextDelta("Hel")
                emitTextDelta("lo")
                emitEnd()
            }) onRequestEquals question
        }
        val deltas = mutableListOf<String>()
        val observer = object : EmbeddedAiTurnObserver {
            override fun textDelta(text: String) {
                deltas += text
            }
        }
        val tracker = AgentTurnExecutionTracker().also { it.reset(observer) }
        try {
            val result = withContext(AgentTurnContext(tracker, observer)) {
                createKoogAgent(PlannerToolSet(unusedService), executor).run(question)
            }

            assertEquals(listOf("Hel", "lo"), deltas)
            assertEquals("Hello", result)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `streamed tool call is persisted once before its result and follow-up stays streaming`() = runBlocking {
        val question = "Find Jita"
        val tools = PlannerToolSet(searchService())
        val delegate = getMockExecutor {
            mockLLMStream(searchToolCallStream(TOOL_CALL_A, tools.searchSystem.name, "Jita")) onRequestEquals question
            mockLLMStream(textStream("Jita found.")) onRequestContains "\"name\":\"Jita\""
        }
        val executor = CapturingPromptExecutor(delegate)
        try {
            val answer = createKoogAgent(tools, executor).run(question)

            assertEquals("Jita found.", answer)
            assertEquals(2, executor.streamingCalls)
            assertEquals(0, executor.nonStreamingCalls)
            assertEquals(2, executor.prompts.size)
            assertToolExchange(executor.prompts[1], listOf(TOOL_CALL_A))
        } finally {
            executor.close()
        }
    }

    @Test
    fun `consecutive streamed tool calls retain ordered unique exchanges`() = runBlocking {
        val question = "Find Jita and Amarr"
        val tools = PlannerToolSet(searchService())
        val delegate = getMockExecutor {
            mockLLMStream(searchToolCallStream(TOOL_CALL_A, tools.searchSystem.name, "Jita")) onRequestEquals question
            mockLLMStream(searchToolCallStream(TOOL_CALL_B, tools.searchSystem.name, "Amarr")) onRequestContains
                "\"name\":\"Jita\""
            mockLLMStream(textStream("Both systems found.")) onRequestContains "\"name\":\"Amarr\""
        }
        val executor = CapturingPromptExecutor(delegate)
        try {
            val answer = createKoogAgent(tools, executor).run(question)

            assertEquals("Both systems found.", answer)
            assertEquals(3, executor.streamingCalls)
            assertEquals(0, executor.nonStreamingCalls)
            assertEquals(3, executor.prompts.size)
            assertToolExchange(executor.prompts[1], listOf(TOOL_CALL_A))
            assertToolExchange(executor.prompts[2], listOf(TOOL_CALL_A, TOOL_CALL_B))
        } finally {
            executor.close()
        }
    }

    @Test
    fun `failed streamed tool call remains failure and its result reaches the next stream`() = runBlocking {
        val question = "Find a missing system"
        val tracker = AgentTurnExecutionTracker()
        val observer = object : EmbeddedAiTurnObserver {}
        tracker.reset(observer)
        val tools = PlannerToolSet(failingSearchService(), tracker)
        val delegate = getMockExecutor {
            mockLLMStream(searchToolCallStream(TOOL_CALL_A, tools.searchSystem.name, "Missing")) onRequestEquals question
            mockLLMStream(textStream("The system could not be found.")) onRequestContains "NOT_FOUND"
        }
        val executor = CapturingPromptExecutor(delegate)
        try {
            val answer = withContext(AgentTurnContext(tracker, observer)) {
                createKoogAgent(tools, executor).run(question)
            }

            assertEquals("The system could not be found.", answer)
            assertEquals(AgentToolCallStatus.FAILURE, tracker.snapshot().toolCalls.single().status)
            assertEquals(2, executor.streamingCalls)
            assertEquals(0, executor.nonStreamingCalls)
            assertToolExchange(executor.prompts[1], listOf(TOOL_CALL_A))
        } finally {
            executor.close()
        }
    }

    @Test
    fun `approved confirmation preserves lifecycle then streams after the tool result`() = runBlocking {
        val question = "Delete the scout view"
        val calls = ConfirmationCalls()
        val confirmations = AiActionConfirmationService()
        confirmations.startRequest("stream-confirm-approve")
        val lifecycle = mutableListOf<AgentToolCallStatus>()
        val waiting = CompletableDeferred<Unit>()
        val observer = lifecycleObserver(lifecycle, waiting)
        val tracker = AgentTurnExecutionTracker().also { it.reset(observer) }
        val tools = PlannerToolSet(confirmationService(calls), tracker, confirmations)
        val delegate = getMockExecutor {
            mockLLMStream(
                toolCallStream(TOOL_CALL_A, tools.deleteView.name, """{"viewId":"view-2"}"""),
            ) onRequestEquals question
            mockLLMStream(textStream("The view was deleted.")) onRequestContains "\"success\":true"
        }
        val executor = CapturingPromptExecutor(delegate)
        try {
            val result = async {
                withContext(AgentTurnContext(tracker, observer)) {
                    createKoogAgent(tools, executor).run(question)
                }
            }
            waiting.await()
            val pending = checkNotNull(confirmations.pending.value)
            assertTrue(confirmations.approve(pending.actionId))

            assertEquals("The view was deleted.", result.await())
            assertEquals(listOf("view-2"), calls.deletedViewIds)
            assertTrue(AgentToolCallStatus.WAITING_CONFIRMATION in lifecycle)
            assertTrue(AgentToolCallStatus.RUNNING in lifecycle)
            assertEquals(AgentToolCallStatus.SUCCESS, tracker.snapshot().toolCalls.single().status)
            assertEquals(2, executor.streamingCalls)
            assertEquals(0, executor.nonStreamingCalls)
            assertToolExchange(executor.prompts[1], listOf(TOOL_CALL_A))
        } finally {
            executor.close()
            confirmations.finishRequest("stream-confirm-approve")
        }
    }

    @Test
    fun `denied confirmation stays cancelled and streams the cancellation result`() = runBlocking {
        val question = "Delete the scout view"
        val calls = ConfirmationCalls()
        val confirmations = AiActionConfirmationService()
        confirmations.startRequest("stream-confirm-deny")
        val lifecycle = mutableListOf<AgentToolCallStatus>()
        val waiting = CompletableDeferred<Unit>()
        val observer = lifecycleObserver(lifecycle, waiting)
        val tracker = AgentTurnExecutionTracker().also { it.reset(observer) }
        val tools = PlannerToolSet(confirmationService(calls), tracker, confirmations)
        val delegate = getMockExecutor {
            mockLLMStream(
                toolCallStream(TOOL_CALL_A, tools.deleteView.name, """{"viewId":"view-2"}"""),
            ) onRequestEquals question
            mockLLMStream(textStream("The deletion was cancelled.")) onRequestContains "\"status\":\"cancelled\""
        }
        val executor = CapturingPromptExecutor(delegate)
        try {
            val result = async {
                withContext(AgentTurnContext(tracker, observer)) {
                    createKoogAgent(tools, executor).run(question)
                }
            }
            waiting.await()
            val pending = checkNotNull(confirmations.pending.value)
            assertTrue(confirmations.deny(pending.actionId))

            assertEquals("The deletion was cancelled.", result.await())
            assertTrue(calls.deletedViewIds.isEmpty())
            assertEquals(AgentToolCallStatus.CANCELLED, tracker.snapshot().toolCalls.single().status)
            assertTrue(AgentToolCallStatus.SUCCESS !in lifecycle)
            assertEquals(2, executor.streamingCalls)
            assertEquals(0, executor.nonStreamingCalls)
            assertToolExchange(executor.prompts[1], listOf(TOOL_CALL_A))
        } finally {
            executor.close()
            confirmations.finishRequest("stream-confirm-deny")
        }
    }

    @Test
    fun `cancelling a partial stream does not append assistant history or start another request`() = runBlocking {
        val question = "Start a long answer"
        val deltaReceived = CompletableDeferred<Unit>()
        val delegate = getMockExecutor {
            mockLLMStream(buildStreamFrameFlow {
                emitTextDelta("Partial")
                awaitCancellation()
            }) onRequestEquals question
        }
        val executor = CapturingPromptExecutor(delegate)
        val deltas = mutableListOf<String>()
        val observer = object : EmbeddedAiTurnObserver {
            override fun textDelta(text: String) {
                deltas += text
                deltaReceived.complete(Unit)
            }
        }
        val tracker = AgentTurnExecutionTracker().also { it.reset(observer) }
        try {
            val job = launch {
                withContext(AgentTurnContext(tracker, observer)) {
                    createKoogAgent(PlannerToolSet(unusedService), executor).run(question)
                }
            }
            deltaReceived.await()
            job.cancelAndJoin()

            assertEquals(listOf("Partial"), deltas)
            assertEquals(1, executor.streamingCalls)
            assertEquals(0, executor.nonStreamingCalls)
            assertTrue(executor.prompts.single().messages.none { it is Message.Assistant })
            assertTrue(tracker.snapshot().toolCalls.isEmpty())
        } finally {
            executor.close()
        }
    }
}

private fun searchToolCallStream(id: String, toolName: String, query: String) =
    toolCallStream(id, toolName, """{"query":"$query"}""")

private fun toolCallStream(id: String, toolName: String, arguments: String) = buildStreamFrameFlow {
    emitToolCallDelta(id, toolName, arguments)
    emitEnd()
}

private fun textStream(text: String) = buildStreamFrameFlow {
    emitTextDelta(text)
    emitEnd()
}

private fun assertToolExchange(prompt: Prompt, expectedIds: List<String>) {
    val calls = prompt.messages.flatMapIndexed { messageIndex, message ->
        message.parts.filterIsInstance<MessagePart.Tool.Call>().map { messageIndex to it }
    }
    val results = prompt.messages.flatMapIndexed { messageIndex, message ->
        message.parts.filterIsInstance<MessagePart.Tool.Result>().map { messageIndex to it }
    }

    assertEquals(expectedIds, calls.map { it.second.id })
    assertEquals(expectedIds, results.map { it.second.id })
    expectedIds.indices.forEach { index ->
        assertTrue(calls[index].first < results[index].first, "assistant tool call must precede its tool result")
    }
    assertEquals(expectedIds.size, prompt.messages.filterIsInstance<Message.Assistant>().sumOf { message ->
        message.parts.count { it is MessagePart.Tool.Call }
    })
    assertEquals(expectedIds.size, prompt.messages.filterIsInstance<Message.User>().sumOf { message ->
        message.parts.count { it is MessagePart.Tool.Result }
    })
}

private class CapturingPromptExecutor(
    private val delegate: PromptExecutor,
) : PromptExecutor() {
    val prompts = mutableListOf<Prompt>()
    var streamingCalls = 0
        private set
    var nonStreamingCalls = 0
        private set

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        nonStreamingCalls++
        return delegate.execute(prompt, model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<ai.koog.prompt.streaming.StreamFrame> {
        streamingCalls++
        prompts += prompt
        return delegate.executeStreaming(prompt, model, tools)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = delegate.moderate(prompt, model)

    override fun close() = delegate.close()
}

private fun searchService(): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "searchSystems" -> {
            val request = arguments?.first() as SearchSystemsRequest
            val name = request.query
            val systemId = if (name == "Jita") 30_000_142 else 30_002_187
            ControlResult.Success(
                request.requestId,
                listOf(SystemSummaryDto(systemId, name, 10_000_002, 20_000_020, 0.9)),
            )
        }
        "toString" -> "StreamingSearchService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun failingSearchService(): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "searchSystems" -> {
            val request = arguments?.first() as SearchSystemsRequest
            ControlResult.Failure(request.requestId, ControlError(ControlErrorCode.NOT_FOUND, "System is missing"))
        }
        "toString" -> "FailingStreamingSearchService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private data class ConfirmationCalls(
    val deletedViewIds: MutableList<String> = mutableListOf(),
)

private fun confirmationService(calls: ConfirmationCalls): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "listViews" -> {
            val request = arguments?.first() as ListViewsRequest
            ControlResult.Success(
                request.requestId,
                listOf(
                    PlanningViewDto("view-1", "Primary", true),
                    PlanningViewDto("view-2", "Scout", false),
                ),
            )
        }
        "deleteView" -> {
            val command = arguments?.first() as DeleteViewCommand
            calls.deletedViewIds += command.viewId
            ControlResult.Success(command.requestId, PlanningViewDto("view-1", "Primary", true))
        }
        "toString" -> "StreamingConfirmationService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private fun lifecycleObserver(
    lifecycle: MutableList<AgentToolCallStatus>,
    waiting: CompletableDeferred<Unit>,
) = object : EmbeddedAiTurnObserver {
    override fun toolExecutionChanged(execution: AgentTurnExecution) {
        execution.toolCalls.lastOrNull()?.status?.let { status ->
            lifecycle += status
            if (status == AgentToolCallStatus.WAITING_CONFIRMATION) waiting.complete(Unit)
        }
    }
}

private const val TOOL_CALL_A = "call-search-jita"
private const val TOOL_CALL_B = "call-search-amarr"

private val unusedService: MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, _ -> error("Unexpected MapControlService call: ${method.name}") } as MapControlService
