package dev.evestaticmapplanner.embeddedai

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
        controller.shutdown()
    }

    @Test
    fun `missing API key has a safe actionable message`() = runTest {
        var environmentReads = 0
        val controller = EmbeddedAiController(
            OpenAiKoogAgentFactory(unusedMapControlService) {
                environmentReads++
                null
            },
            StandardTestDispatcher(testScheduler),
        )

        assertEquals(0, environmentReads)
        controller.send("Tell me about system 30000142")
        advanceUntilIdle()

        assertEquals(1, environmentReads)
        assertEquals(
            "OPENAI_API_KEY is not set. Set it before using the embedded assistant.",
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
        assertTrue(providerMessage.startsWith("AI request failed (IOException)"))
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
}

private fun testAgent(run: suspend (String) -> String) = object : EmbeddedAiAgent {
    override suspend fun run(prompt: String): String = run(prompt)
    override suspend fun close() = Unit
}

private val unusedMapControlService: MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, _ -> error("Unexpected MapControlService call: ${method.name}") } as MapControlService
