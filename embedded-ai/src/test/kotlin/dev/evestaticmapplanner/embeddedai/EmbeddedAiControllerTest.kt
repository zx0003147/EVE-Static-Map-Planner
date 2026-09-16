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
        assertEquals(2, controller.state.value.sessions.size)
        assertTrue(controller.state.value.sessions.any { it.id == oldSession && it.messages.size == 2 })
        assertEquals(AiProviderType.DEEPSEEK, controller.state.value.runtimeInfo?.providerType)
        assertEquals(1, closeCount)
        controller.send("second")
        advanceUntilIdle()
        assertEquals("second", prompts.last(), "New Chat must not reuse the previous conversation context")
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

private val unusedMapControlService: MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, _ -> error("Unexpected MapControlService call: ${method.name}") } as MapControlService
