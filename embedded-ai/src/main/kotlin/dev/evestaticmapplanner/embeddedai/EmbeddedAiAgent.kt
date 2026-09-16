package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import dev.evestaticmapplanner.control.MapControlService
import java.util.concurrent.atomic.AtomicBoolean

data class EmbeddedAiRuntimeInfo(
    val providerType: AiProviderType,
    val modelId: String,
    val credentialSource: AiCredentialSource,
)

interface EmbeddedAiAgent {
    val runtimeInfo: EmbeddedAiRuntimeInfo? get() = null
    suspend fun run(prompt: String): String
    suspend fun close()
}

fun interface EmbeddedAiAgentFactory {
    fun create(): EmbeddedAiAgent
}

class ConfiguredKoogAgentFactory(
    private val mapControlService: MapControlService,
    private val configSource: AiProviderConfigSource,
    private val credentialResolver: AiCredentialResolver,
    private val clientFactory: AiClientFactory = DefaultAiClientFactory(),
    private val diagnostics: (String) -> Unit = {},
) : EmbeddedAiAgentFactory {
    override fun create(): EmbeddedAiAgent {
        val config = configSource.current() ?: throw AiProviderException(
            AiProviderErrorCode.NO_PROVIDER_CONFIGURED,
            "AI provider is not configured.",
        )
        val credential = credentialResolver.resolve(config) ?: throw AiProviderException(
            AiProviderErrorCode.NO_CREDENTIAL,
            "AI API Key is not configured.",
        )
        val source = credential.source
        val managedClient = try {
            credential.useSecret { clientFactory.create(config, it) }
        } finally {
            credential.close()
        }
        val koogAgent = try {
            createKoogAgent(
                tools = PlannerToolSet(mapControlService, diagnostics),
                promptExecutor = managedClient.promptExecutor,
                model = managedClient.model,
                temperature = config.temperature,
            )
        } catch (failure: Throwable) {
            managedClient.close()
            throw failure
        }
        diagnostics("Provider: ${config.providerType.displayName}")
        diagnostics("Model: ${config.modelId}")
        diagnostics("Credential: ${source.displayName}")
        return ManagedKoogEmbeddedAiAgent(
            koogAgent = koogAgent,
            managedClient = managedClient,
            runtimeInfo = EmbeddedAiRuntimeInfo(config.providerType, config.modelId, source),
        )
    }
}

private class ManagedKoogEmbeddedAiAgent(
    private val koogAgent: AIAgent<String, String>,
    private val managedClient: ManagedAiClient,
    override val runtimeInfo: EmbeddedAiRuntimeInfo,
) : EmbeddedAiAgent {
    private val closed = AtomicBoolean()

    override suspend fun run(prompt: String): String = koogAgent.run(prompt)

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) managedClient.close()
    }
}

/** Compatibility adapter retained for the Phase 2 environment-only smoke test. */
class OpenRouterKoogAgentFactory(
    mapControlService: MapControlService,
    environment: (String) -> String? = System::getenv,
    diagnostics: (String) -> Unit = {},
) : EmbeddedAiAgentFactory {
    private val delegate = ConfiguredKoogAgentFactory(
        mapControlService = mapControlService,
        configSource = AiProviderConfigSource { AiProviderConfig.DefaultOpenRouter },
        credentialResolver = AiCredentialResolver(
            secureStore = UnavailableAiCredentialStore,
            sessionStore = UnavailableAiCredentialStore,
            environment = environment,
        ),
        diagnostics = diagnostics,
    )

    override fun create(): EmbeddedAiAgent = delegate.create()

    companion object {
        const val OPENROUTER_API_KEY = "OPENROUTER_API_KEY"
        const val MODEL_ID = AiProviderConfig.DEFAULT_OPENROUTER_MODEL
        const val PROVIDER_DIAGNOSTIC = "Provider: OpenRouter"
        const val MODEL_DIAGNOSTIC = "Model: $MODEL_ID"
        internal val SYSTEM_PROMPT = PLANNER_SYSTEM_PROMPT
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
    model: LLModel = DEFAULT_OPENROUTER_MODEL,
    temperature: Double? = AiProviderConfig.DEFAULT_TEMPERATURE,
) = AIAgent(
    promptExecutor = promptExecutor,
    llmModel = model,
    toolRegistry = ToolRegistry {
        tool(tools.getSystemInfo)
        tool(tools.searchSystem)
        tool(tools.calculateNormalRoute)
        tool(tools.calculateCapitalRoute)
        tool(tools.optimizeMultiPointRoute)
    },
    systemPrompt = PLANNER_SYSTEM_PROMPT,
    temperature = temperature,
    strategy = singleRunStrategy(),
    maxIterations = 24,
)

internal val DEFAULT_OPENROUTER_MODEL = AiProviderConfig.DefaultOpenRouter.toKoogModel()
internal val OPENROUTER_MODEL = DEFAULT_OPENROUTER_MODEL

private val PLANNER_SYSTEM_PROMPT = """
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
