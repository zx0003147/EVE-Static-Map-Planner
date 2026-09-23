package dev.evestaticmapplanner.embeddedai

import ai.koog.prompt.llm.LLMProvider
import dev.evestaticmapplanner.control.MapControlService
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedAiControllerTest {
    @Test
    fun `explicit mutation promise receives one recovery and completes only after mutation success`() = runTest {
        val agent = ScriptedExecutionAgent(
            attempts = listOf(
                ScriptedAttempt("好的，我马上创建。", AgentTurnExecution()),
                ScriptedAttempt(
                    "已创建 Jita 临时标记。",
                    AgentTurnExecution(toolCallCount = 2, mutationCallCount = 1, successfulMutationCount = 1),
                ),
            ),
            expectation = AgentMutationExpectation.REQUIRED,
        )
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory { agent },
            StandardTestDispatcher(testScheduler),
        )

        controller.send("帮我在 Jita 创建一个临时标记")
        advanceUntilIdle()

        assertEquals(2, agent.prompts.size)
        assertTrue(agent.prompts.last().contains("only recovery attempt"))
        assertEquals("已创建 Jita 临时标记。", controller.state.value.response)
        assertEquals(EmbeddedAiMessageStatus.COMPLETE, controller.state.value.chatSession.messages.last().status)
        assertEquals(1, controller.state.value.lastTurnExecution.successfulMutationCount)
        controller.shutdown()
    }

    @Test
    fun `explicit mutation with two promise-only attempts becomes incomplete without looping`() = runTest {
        val agent = ScriptedExecutionAgent(
            attempts = listOf(
                ScriptedAttempt("我马上创建。", AgentTurnExecution()),
                ScriptedAttempt("我现在就添加。", AgentTurnExecution()),
            ),
            expectation = AgentMutationExpectation.REQUIRED,
        )
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory { agent },
            StandardTestDispatcher(testScheduler),
        )

        controller.send("创建红色标记")
        advanceUntilIdle()

        assertEquals(2, agent.prompts.size)
        assertEquals(MAP_ACTION_INCOMPLETE_MESSAGE, controller.state.value.response)
        assertEquals(EmbeddedAiMessageStatus.INCOMPLETE, controller.state.value.chatSession.messages.last().status)
        assertEquals(0, controller.state.value.lastTurnExecution.successfulMutationCount)
        controller.shutdown()
    }

    @Test
    fun `ordinary knowledge answer does not require a mutation or recovery`() = runTest {
        val agent = ScriptedExecutionAgent(
            attempts = listOf(ScriptedAttempt("Jita 位于 The Forge。", AgentTurnExecution())),
            expectation = AgentMutationExpectation.NOT_REQUIRED,
        )
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory { agent },
            StandardTestDispatcher(testScheduler),
        )

        controller.send("Jita 位于哪个区域？")
        advanceUntilIdle()

        assertEquals(1, agent.prompts.size)
        assertEquals(1, agent.expectationPrompts.size)
        assertEquals(EmbeddedAiMessageStatus.COMPLETE, controller.state.value.chatSession.messages.last().status)
        assertEquals("Jita 位于 The Forge。", controller.state.value.response)
        controller.shutdown()
    }

    @Test
    fun `read-only route calculation remains complete without mutation`() = runTest {
        val agent = ScriptedExecutionAgent(
            attempts = listOf(
                ScriptedAttempt("Jita 到 Amarr 共 11 跳。", AgentTurnExecution(toolCallCount = 3)),
            ),
            expectation = AgentMutationExpectation.NOT_REQUIRED,
        )
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory { agent },
            StandardTestDispatcher(testScheduler),
        )

        controller.send("Jita 到 Amarr 的路线有几跳？")
        advanceUntilIdle()

        assertEquals(1, agent.prompts.size)
        assertEquals(3, controller.state.value.lastTurnExecution.toolCallCount)
        assertEquals(0, controller.state.value.lastTurnExecution.mutationCallCount)
        assertEquals(EmbeddedAiMessageStatus.COMPLETE, controller.state.value.chatSession.messages.last().status)
        controller.shutdown()
    }

    @Test
    fun `display route recovers after read-only first attempt and records mutation success`() = runTest {
        val agent = ScriptedExecutionAgent(
            attempts = listOf(
                ScriptedAttempt("路线已计算，我马上显示。", AgentTurnExecution(toolCallCount = 3)),
                ScriptedAttempt(
                    "路线已显示。",
                    AgentTurnExecution(toolCallCount = 3, mutationCallCount = 3, successfulMutationCount = 3),
                ),
            ),
            expectation = AgentMutationExpectation.REQUIRED,
        )
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory { agent },
            StandardTestDispatcher(testScheduler),
        )

        controller.send("帮我规划路线并显示在地图上")
        advanceUntilIdle()

        assertEquals(2, agent.prompts.size)
        assertEquals("路线已显示。", controller.state.value.response)
        assertEquals(6, controller.state.value.lastTurnExecution.toolCallCount)
        assertEquals(3, controller.state.value.lastTurnExecution.successfulMutationCount)
        controller.shutdown()
    }

    @Test
    fun `failed mutation recovery cannot be reported as complete`() = runTest {
        val failed = AgentTurnExecution(toolCallCount = 1, mutationCallCount = 1, failedMutationCount = 1)
        val agent = ScriptedExecutionAgent(
            attempts = listOf(
                ScriptedAttempt("创建失败。", failed),
                ScriptedAttempt("仍然无法创建。", failed),
            ),
            expectation = AgentMutationExpectation.REQUIRED,
        )
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory { agent },
            StandardTestDispatcher(testScheduler),
        )

        controller.send("在 Jita 创建一个标记")
        advanceUntilIdle()

        assertEquals(MAP_ACTION_INCOMPLETE_MESSAGE, controller.state.value.response)
        assertEquals(EmbeddedAiMessageStatus.INCOMPLETE, controller.state.value.chatSession.messages.last().status)
        assertEquals(2, controller.state.value.lastTurnExecution.failedMutationCount)
        assertEquals(0, controller.state.value.lastTurnExecution.successfulMutationCount)
        controller.shutdown()
    }

    @Test
    fun `default OpenRouter model remains DeepSeek V4 Flash 0731`() {
        assertEquals(LLMProvider.OpenRouter, OPENROUTER_MODEL.provider)
        assertEquals("deepseek/deepseek-v4-flash-0731", OPENROUTER_MODEL.id)
    }

    @Test
    fun `agent is lazy and send never blocks the caller`() = runTest {
        var createCount = 0
        val gate = CompletableDeferred<String>()
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                createCount++
                testAgent { gate.await() }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(0, createCount)
        controller.send("Tell me about system 30000142")
        assertTrue(controller.state.value.isLoading)
        assertEquals(0, createCount)

        runCurrent()
        assertEquals(1, createCount)
        gate.complete("Jita")
        advanceUntilIdle()
        assertEquals("Jita", controller.state.value.response)
        assertFalse(controller.state.value.isLoading)
        controller.shutdown()
    }

    @Test
    fun `one controller session runs at most one main request concurrently`() = runTest {
        val prompts = mutableListOf<String>()
        val gate = CompletableDeferred<String>()
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                testAgent { prompt ->
                    prompts += prompt
                    gate.await()
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.send("first")
        controller.send("second")
        runCurrent()
        assertEquals(listOf("first"), prompts)
        gate.complete("done")
        advanceUntilIdle()
        assertEquals("done", controller.state.value.response)
        controller.shutdown()
    }

    @Test
    fun `completed turns become bounded context for the next request`() = runTest {
        val prompts = mutableListOf<String>()
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                testAgent { prompt ->
                    prompts += prompt
                    if (prompts.size == 1) "已显示 Jita 到 Amarr。" else "已删除刚才的路线。"
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.send("显示 Jita 到 Amarr。")
        advanceUntilIdle()
        controller.send("把刚才那条删掉。")
        advanceUntilIdle()

        assertEquals(2, prompts.size)
        assertEquals("显示 Jita 到 Amarr。", prompts.first())
        assertTrue(prompts.last().contains("显示 Jita 到 Amarr。"))
        assertTrue(prompts.last().contains("已显示 Jita 到 Amarr。"))
        assertTrue(prompts.last().contains("把刚才那条删掉。"))
        assertTrue(prompts.last().contains("context only"))
        assertEquals(
            listOf(
                EmbeddedAiMessageRole.USER,
                EmbeddedAiMessageRole.ASSISTANT,
                EmbeddedAiMessageRole.USER,
                EmbeddedAiMessageRole.ASSISTANT,
            ),
            controller.state.value.chatSession.messages.map(EmbeddedAiMessage::role),
        )
        controller.shutdown()
    }

    @Test
    fun `cancel stops an in-flight request and returns to idle`() = runTest {
        val gate = CompletableDeferred<String>()
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory { testAgent { gate.await() } },
            StandardTestDispatcher(testScheduler),
        )

        controller.send("Tell me about system 30000142")
        runCurrent()
        controller.cancel()
        advanceUntilIdle()

        assertEquals("Request cancelled.", controller.state.value.response)
        assertFalse(controller.state.value.isLoading)
        assertNull(controller.state.value.errorMessage)
        assertEquals(2, controller.state.value.chatSession.messages.size)
        assertEquals(EmbeddedAiMessageRole.USER, controller.state.value.chatSession.messages.first().role)
        assertEquals(EmbeddedAiMessageStatus.CANCELLED, controller.state.value.chatSession.messages.last().status)
        controller.shutdown()
    }

    @Test
    fun `New Chat clears UI and agent context without touching provider configuration`() = runTest {
        val prompts = mutableListOf<String>()
        var closeCount = 0
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                object : EmbeddedAiAgent {
                    override val runtimeInfo = EmbeddedAiRuntimeInfo(
                        AiProviderType.DEEPSEEK,
                        "deepseek-flash",
                        AiCredentialSource.SECURE_STORAGE,
                    )

                    override suspend fun run(prompt: String): String {
                        prompts += prompt
                        return "answer"
                    }

                    override suspend fun close() {
                        closeCount++
                    }
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.send("first")
        advanceUntilIdle()
        val oldSession = controller.state.value.chatSession.id
        assertEquals(2, controller.state.value.chatSession.messages.size)
        assertEquals(AiProviderType.DEEPSEEK, controller.state.value.runtimeInfo?.providerType)

        controller.newChat()
        advanceUntilIdle()

        assertTrue(controller.state.value.chatSession.messages.isEmpty())
        assertTrue(controller.state.value.chatSession.id != oldSession)
        assertEquals(1, controller.state.value.sessions.size)
        assertTrue(controller.state.value.sessions.any { it.id == oldSession && it.messages.size == 2 })
        assertEquals(AiProviderType.DEEPSEEK, controller.state.value.runtimeInfo?.providerType)
        assertEquals(1, closeCount)
        controller.send("second")
        advanceUntilIdle()
        assertEquals(2, controller.state.value.sessions.size)
        assertEquals("second", prompts.last(), "New Chat must not reuse the previous conversation context")
        controller.shutdown()
    }

    @Test
    fun `empty chats stay out of the session list until their first user message`() = runTest {
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory { testAgent { "answer" } },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(controller.state.value.sessions.isEmpty())
        controller.newChat()
        controller.newChat()
        advanceUntilIdle()
        assertTrue(controller.state.value.sessions.isEmpty())

        controller.send("Build a Jita route")
        advanceUntilIdle()

        assertEquals(1, controller.state.value.sessions.size)
        assertEquals("Build a Jita route", controller.state.value.sessions.single().title)
        controller.shutdown()
    }

    @Test
    fun `renamed chat remains renamed for the runtime but a new controller starts empty`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory { testAgent { "answer" } },
            dispatcher = dispatcher,
        )
        controller.send("把1dq1-a标注在地图上")
        advanceUntilIdle()
        val sessionId = controller.state.value.chatSession.id
        val originalMessages = controller.state.value.chatSession.messages

        assertTrue(controller.renameChat(sessionId, "  1DQ Route Planning  "))
        assertEquals("1DQ Route Planning", controller.state.value.chatSession.title)
        assertEquals(originalMessages, controller.state.value.chatSession.messages)

        controller.send("continue")
        advanceUntilIdle()
        assertEquals("1DQ Route Planning", controller.state.value.chatSession.title)
        assertFalse(controller.renameChat(sessionId, "   "))
        assertEquals("1DQ Route Planning", controller.state.value.chatSession.title)
        controller.shutdown()

        val restarted = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory { testAgent { "unused" } },
            dispatcher = dispatcher,
        )
        assertTrue(restarted.state.value.sessions.isEmpty())
        assertTrue(restarted.state.value.chatSession.messages.isEmpty())
        restarted.shutdown()
    }

    @Test
    fun `multiple chats restore only their own bounded runtime context`() = runTest {
        val prompts = mutableListOf<String>()
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                testAgent { prompt -> prompts += prompt; "answer-${prompts.size}" }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.send("Jita to Amarr")
        advanceUntilIdle()
        val chatA = controller.state.value.chatSession.id
        controller.newChat()
        advanceUntilIdle()
        controller.send("1DQ1-A jump range")
        advanceUntilIdle()
        val chatB = controller.state.value.chatSession.id

        assertEquals("1DQ1-A jump range", prompts[1])
        controller.selectChat(chatA)
        advanceUntilIdle()
        controller.send("show it")
        advanceUntilIdle()
        assertTrue(prompts[2].contains("Jita to Amarr"))
        assertFalse(prompts[2].contains("1DQ1-A jump range"))

        controller.selectChat(chatB)
        advanceUntilIdle()
        controller.send("show that range")
        advanceUntilIdle()
        assertTrue(prompts[3].contains("1DQ1-A jump range"))
        assertFalse(prompts[3].contains("Jita to Amarr"))
        controller.shutdown()
    }

    @Test
    fun `missing OpenRouter API key has a safe actionable message`() = runTest {
        var environmentReads = 0
        val controller = EmbeddedAiController(
            OpenRouterKoogAgentFactory(
                unusedMapControlService,
                environment = {
                    environmentReads++
                    null
                },
            ),
            StandardTestDispatcher(testScheduler),
        )

        assertEquals(0, environmentReads)
        controller.send("Tell me about system 30000142")
        advanceUntilIdle()

        assertEquals(1, environmentReads)
        assertEquals(
            "AI API Key is not configured.",
            controller.state.value.errorMessage,
        )
        controller.shutdown()
    }

    @Test
    fun `provider and Planner tool failures are displayed without exception details`() = runTest {
        val provider = EmbeddedAiController(
            EmbeddedAiAgentFactory { testAgent { throw IOException("secret transport details") } },
            StandardTestDispatcher(testScheduler),
        )
        provider.send("Tell me about system 30000142")
        advanceUntilIdle()
        val providerMessage = assertNotNull(provider.state.value.errorMessage)
        assertEquals("The provider request failed.", providerMessage)
        assertFalse(providerMessage.contains("secret transport details"))
        provider.shutdown()

        val tool = EmbeddedAiController(
            EmbeddedAiAgentFactory { testAgent { throw EmbeddedAiToolException("NOT_FOUND: Solar system was not found") } },
            StandardTestDispatcher(testScheduler),
        )
        tool.send("Tell me about system 30000142")
        advanceUntilIdle()
        assertEquals(
            "Planner tool failed: NOT_FOUND: Solar system was not found",
            tool.state.value.errorMessage,
        )
        tool.shutdown()
    }

    @Test
    fun `close cancels work and closes a lazily created agent once`() = runTest {
        var closeCount = 0
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory {
                object : EmbeddedAiAgent {
                    override suspend fun run(prompt: String) = "done"
                    override suspend fun close() {
                        closeCount++
                    }
                }
            },
            StandardTestDispatcher(testScheduler),
        )
        controller.send("question")
        advanceUntilIdle()

        controller.shutdown()
        controller.shutdown()

        assertEquals(1, closeCount)
    }

    @Test
    fun `configuration change keeps current request then closes old runtime before next request`() = runTest {
        val firstGate = CompletableDeferred<String>()
        var createCount = 0
        var closeCount = 0
        val controller = EmbeddedAiController(
            EmbeddedAiAgentFactory {
                createCount++
                val instance = createCount
                object : EmbeddedAiAgent {
                    override suspend fun run(prompt: String): String = if (instance == 1) firstGate.await() else "new"
                    override suspend fun close() { closeCount++ }
                }
            },
            StandardTestDispatcher(testScheduler),
        )

        controller.send("old request")
        runCurrent()
        controller.configurationChanged()
        firstGate.complete("old")
        advanceUntilIdle()

        assertEquals("old", controller.state.value.response)
        assertEquals(1, closeCount)
        controller.send("new request")
        advanceUntilIdle()
        assertEquals("new", controller.state.value.response)
        assertEquals(2, createCount)
        controller.shutdown()
        assertEquals(2, closeCount)
    }

    @Test
    fun `switching every provider keeps exactly one lazy runtime`() = runTest {
        val sequence = listOf(
            AiProviderType.OPENROUTER,
            AiProviderType.ANTHROPIC,
            AiProviderType.DEEPSEEK,
            AiProviderType.OPENAI,
            AiProviderType.GOOGLE,
            AiProviderType.OPENAI_COMPATIBLE,
        )
        var selected = sequence.first()
        var live = 0
        var maxLive = 0
        val created = mutableListOf<AiProviderType>()
        val closedProviders = mutableListOf<AiProviderType>()
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                val provider = selected
                created += provider
                live++
                maxLive = maxOf(maxLive, live)
                object : EmbeddedAiAgent {
                    override val runtimeInfo = EmbeddedAiRuntimeInfo(
                        provider,
                        "fixture-${provider.name.lowercase()}",
                        AiCredentialSource.SECURE_STORAGE,
                    )
                    override suspend fun run(prompt: String) = provider.displayName
                    override suspend fun close() {
                        live--
                        closedProviders += provider
                    }
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        sequence.forEachIndexed { index, provider ->
            if (index > 0) {
                selected = provider
                controller.configurationChanged()
                advanceUntilIdle()
                assertEquals(index, created.size, "Provider change must not eagerly create a client")
                assertEquals(0, live)
            }
            controller.send("request-$index")
            advanceUntilIdle()
            assertEquals(provider.displayName, controller.state.value.response)
            assertEquals(provider, controller.state.value.runtimeInfo?.providerType)
            assertEquals(1, live)
        }

        controller.shutdown()
        assertEquals(sequence, created)
        assertEquals(sequence, closedProviders)
        assertEquals(1, maxLive)
        assertEquals(0, live)
    }

    @Test
    fun `cancel invalidates a pending confirmation and prevents execution`() = runTest {
        val confirmations = AiActionConfirmationService()
        var executed = false
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                testAgent {
                    confirmations.confirmAndExecute(testConfirmationRequest()) {
                        executed = true
                        "done"
                    }
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
            confirmationService = confirmations,
        )

        controller.send("save this")
        runCurrent()
        assertNotNull(controller.confirmation.value)
        controller.cancel()
        advanceUntilIdle()

        assertNull(controller.confirmation.value)
        assertFalse(executed)
        assertEquals("Request cancelled.", controller.state.value.response)
        controller.shutdown()
    }

    @Test
    fun `New Chat invalidates a pending confirmation and prevents execution`() = runTest {
        val confirmations = AiActionConfirmationService()
        var executed = false
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                testAgent {
                    confirmations.confirmAndExecute(testConfirmationRequest()) {
                        executed = true
                        "done"
                    }
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
            confirmationService = confirmations,
        )

        controller.send("save this")
        runCurrent()
        assertNotNull(controller.confirmation.value)
        controller.newChat()
        advanceUntilIdle()

        assertNull(controller.confirmation.value)
        assertFalse(executed)
        assertTrue(controller.state.value.chatSession.messages.isEmpty())
        assertFalse(controller.state.value.isLoading)
        controller.shutdown()
    }

    @Test
    fun `switching chats invalidates the previous chat confirmation`() = runTest {
        val confirmations = AiActionConfirmationService()
        var executed = false
        var confirmationTurn = false
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                testAgent {
                    if (confirmationTurn) {
                        confirmations.confirmAndExecute(testConfirmationRequest()) {
                            executed = true
                            "done"
                        }
                    } else {
                        "first answer"
                    }
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
            confirmationService = confirmations,
        )

        controller.send("create Chat A")
        advanceUntilIdle()
        val chatA = controller.state.value.chatSession.id
        controller.newChat()
        advanceUntilIdle()
        confirmationTurn = true
        controller.send("save this in Chat B")
        runCurrent()
        assertNotNull(controller.confirmation.value)

        controller.selectChat(chatA)
        advanceUntilIdle()

        assertNull(controller.confirmation.value)
        assertFalse(executed)
        assertEquals(chatA, controller.state.value.chatSession.id)
        controller.shutdown()
    }

    @Test
    fun `provider change invalidates pending confirmation without executing it`() = runTest {
        val confirmations = AiActionConfirmationService()
        var executed = false
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                testAgent {
                    confirmations.confirmAndExecute(testConfirmationRequest()) {
                        executed = true
                        "done"
                    }
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
            confirmationService = confirmations,
        )

        controller.send("save this")
        runCurrent()
        assertNotNull(controller.confirmation.value)
        controller.configurationChanged()
        advanceUntilIdle()

        assertNull(controller.confirmation.value)
        assertFalse(executed)
        assertTrue(controller.state.value.response.contains("provider configuration changed"))
        controller.shutdown()
    }

    @Test
    fun `oversized prompt is rejected before lazy agent creation`() = runTest {
        var createCount = 0
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                createCount++
                testAgent { "unexpected" }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.send("x".repeat(MAX_PROMPT_CODE_POINTS + 1))
        advanceUntilIdle()

        assertEquals(0, createCount)
        assertTrue(controller.state.value.errorMessage.orEmpty().contains("too long"))
        controller.shutdown()
    }
}

private fun testConfirmationRequest() = AiActionRequest(
    toolName = "create_saved_marker",
    risk = PlannerToolRisk.PERSISTENT_WRITE,
    normalizedArguments = "{\"systemId\":30000142}",
    action = "Create saved marker",
    target = "Jita",
    effect = "Writes user data.",
)

private fun testAgent(run: suspend (String) -> String) = object : EmbeddedAiAgent {
    override suspend fun run(prompt: String): String = run(prompt)
    override suspend fun close() = Unit
}

private data class ScriptedAttempt(
    val response: String,
    val execution: AgentTurnExecution,
)

private class ScriptedExecutionAgent(
    private val attempts: List<ScriptedAttempt>,
    private val expectation: AgentMutationExpectation,
) : EmbeddedAiAgent {
    val prompts = mutableListOf<String>()
    val expectationPrompts = mutableListOf<String>()
    private var attemptIndex = 0
    private var execution = AgentTurnExecution()

    override val lastTurnExecution: AgentTurnExecution get() = execution

    override suspend fun run(prompt: String): String {
        prompts += prompt
        val attempt = attempts.getOrElse(attemptIndex) { error("Unexpected recovery attempt") }
        attemptIndex++
        execution = attempt.execution
        return attempt.response
    }

    override suspend fun mutationExpectation(prompt: String): AgentMutationExpectation {
        expectationPrompts += prompt
        return expectation
    }

    override suspend fun close() = Unit
}

private val unusedMapControlService: MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, _ -> error("Unexpected MapControlService call: ${method.name}") } as MapControlService
