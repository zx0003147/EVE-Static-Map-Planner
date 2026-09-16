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
        val koogAgent = createKoogAgent(PlannerToolSet(mapControlService, diagnostics), executor)
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
            Planner facts are available only from the registered Planner tools. Treat tool results as authoritative.
            Never guess a solar-system ID, name, location, route, jump count, edge type, capital distance, or optimized order.

            Use search_system first whenever the user supplies a solar-system name or partial name instead of a canonical numeric systemId.
            If search_system returns multiple plausible systems and the user's context does not select exactly one, ask the user to clarify.
            Use get_system_info for information about one canonical numeric systemId.
            Use calculate_normal_route when the user already specifies the visit order, including ordered waypoints.
            Use calculate_capital_route for capital navigation and pass the user's effectiveRangeLy. Never infer a missing jump range.
            Use optimize_multi_point_route when the user supplies an unordered target set and asks Planner to choose the visit order.
            Never calculate routes, distances, BFS paths, or target ordering yourself.

            Respect the tool parameters and Planner defaults. If a required option is unknown, ask the user instead of guessing.
            Keep answers concise and state only facts supported by tool results. Do not add general EVE background or map facts from model knowledge.
            Do not derive labels or classifications from numeric fields; for example, report securityStatus as returned without labeling it high-sec, low-sec, or null-sec.
        """.trimIndent()
    }
}

internal class PlannerToolSet(
    mapControlService: MapControlService,
    diagnostics: (String) -> Unit = {},
) {
    val getSystemInfo = GetSystemInfoTool(mapControlService, diagnostics)
    val searchSystem = SearchSystemTool(mapControlService, diagnostics)
    val calculateNormalRoute = CalculateNormalRouteTool(mapControlService, diagnostics)
    val calculateCapitalRoute = CalculateCapitalRouteTool(mapControlService, diagnostics)
    val optimizeMultiPointRoute = OptimizeMultiPointRouteTool(mapControlService, diagnostics)

    val names: List<String> = listOf(
        getSystemInfo.name,
        searchSystem.name,
        calculateNormalRoute.name,
        calculateCapitalRoute.name,
        optimizeMultiPointRoute.name,
    )
}

internal fun createKoogAgent(
    tools: PlannerToolSet,
    promptExecutor: PromptExecutor,
) = AIAgent(
    promptExecutor = promptExecutor,
    llmModel = OPENROUTER_MODEL,
    toolRegistry = ToolRegistry {
        tool(tools.getSystemInfo)
        tool(tools.searchSystem)
        tool(tools.calculateNormalRoute)
        tool(tools.calculateCapitalRoute)
        tool(tools.optimizeMultiPointRoute)
    },
    systemPrompt = OpenRouterKoogAgentFactory.SYSTEM_PROMPT,
    strategy = singleRunStrategy(),
    maxIterations = 24,
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
