package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.evestaticmapplanner.control.MapControlService

interface EmbeddedAiAgent {
    suspend fun run(prompt: String): String
    suspend fun close()
}

fun interface EmbeddedAiAgentFactory {
    fun create(): EmbeddedAiAgent
}

class OpenRouterKoogAgentFactory(
    private val mapControlService: MapControlService,
    private val environment: (String) -> String? = System::getenv,
    private val diagnostics: (String) -> Unit = {},
) : EmbeddedAiAgentFactory {
    override fun create(): EmbeddedAiAgent {
        val apiKey = environment(OPENROUTER_API_KEY)?.trim().orEmpty()
        if (apiKey.isEmpty()) throw MissingOpenRouterApiKeyException()

        diagnostics(PROVIDER_DIAGNOSTIC)
        diagnostics(MODEL_DIAGNOSTIC)
        val client = OpenRouterLLMClient(
            apiKey = apiKey,
            httpClientFactory = JavaKoogHttpClient.Factory(),
        )
        val executor = MultiLLMPromptExecutor(client)
        val koogAgent = createKoogAgent(GetSystemInfoTool(mapControlService, diagnostics), executor)
        return object : EmbeddedAiAgent {
            override suspend fun run(prompt: String): String = koogAgent.run(prompt)

            override suspend fun close() {
                executor.close()
            }
        }
    }

    companion object {
        const val OPENROUTER_API_KEY = "OPENROUTER_API_KEY"
        const val MODEL_ID = "deepseek/deepseek-v4-flash-0731"
        const val PROVIDER_DIAGNOSTIC = "Provider: OpenRouter"
        const val MODEL_DIAGNOSTIC = "Model: $MODEL_ID"

        internal val SYSTEM_PROMPT = """
            You are the embedded assistant for EVE Static Map Planner.
            When the user asks about a solar system using a numeric system ID, you must call get_system_info.
            Treat the tool result as authoritative Planner data. Never invent system information.
            Give a concise, readable answer using only fields returned by the tool.
        """.trimIndent()
    }
}

internal fun createKoogAgent(
    getSystemInfoTool: GetSystemInfoTool,
    promptExecutor: PromptExecutor,
) = AIAgent(
    promptExecutor = promptExecutor,
    llmModel = OPENROUTER_MODEL,
    toolRegistry = ToolRegistry {
        tool(getSystemInfoTool)
    },
    systemPrompt = OpenRouterKoogAgentFactory.SYSTEM_PROMPT,
    strategy = singleRunStrategy(),
    maxIterations = 6,
)

internal val OPENROUTER_MODEL = LLModel(
    provider = LLMProvider.OpenRouter,
    id = OpenRouterKoogAgentFactory.MODEL_ID,
    capabilities = listOf(
        LLMCapability.Completion,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
    ),
)

class MissingOpenRouterApiKeyException : IllegalStateException(
    "OPENROUTER_API_KEY is not configured",
)
