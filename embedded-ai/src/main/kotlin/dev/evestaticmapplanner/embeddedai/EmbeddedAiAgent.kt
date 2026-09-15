package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import dev.evestaticmapplanner.control.MapControlService

interface EmbeddedAiAgent {
    suspend fun run(prompt: String): String
    suspend fun close()
}

fun interface EmbeddedAiAgentFactory {
    fun create(): EmbeddedAiAgent
}

class OpenAiKoogAgentFactory(
    private val mapControlService: MapControlService,
    private val environment: (String) -> String? = System::getenv,
) : EmbeddedAiAgentFactory {
    override fun create(): EmbeddedAiAgent {
        val apiKey = environment(OPENAI_API_KEY)?.trim().orEmpty()
        if (apiKey.isEmpty()) throw MissingOpenAiApiKeyException()

        val client = OpenAILLMClient(
            apiKey = apiKey,
            httpClientFactory = JavaKoogHttpClient.Factory(),
        )
        val executor = MultiLLMPromptExecutor(client)
        val koogAgent = createKoogAgent(GetSystemInfoTool(mapControlService), executor)
        return object : EmbeddedAiAgent {
            override suspend fun run(prompt: String): String = koogAgent.run(prompt)

            override suspend fun close() {
                executor.close()
            }
        }
    }

    companion object {
        const val OPENAI_API_KEY = "OPENAI_API_KEY"

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
    llmModel = OpenAIModels.Chat.GPT4oMini,
    toolRegistry = ToolRegistry {
        tool(getSystemInfoTool)
    },
    systemPrompt = OpenAiKoogAgentFactory.SYSTEM_PROMPT,
    strategy = singleRunStrategy(),
    maxIterations = 6,
)

class MissingOpenAiApiKeyException : IllegalStateException(
    "OPENAI_API_KEY is not configured",
)
