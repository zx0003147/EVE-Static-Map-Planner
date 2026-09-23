package dev.evestaticmapplanner.embeddedai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AgentTurnExecution(
    val toolCallCount: Int = 0,
    val mutationCallCount: Int = 0,
    val successfulMutationCount: Int = 0,
    val failedMutationCount: Int = 0,
) {
    operator fun plus(other: AgentTurnExecution): AgentTurnExecution = AgentTurnExecution(
        toolCallCount = toolCallCount + other.toolCallCount,
        mutationCallCount = mutationCallCount + other.mutationCallCount,
        successfulMutationCount = successfulMutationCount + other.successfulMutationCount,
        failedMutationCount = failedMutationCount + other.failedMutationCount,
    )
}

enum class AgentMutationExpectation {
    REQUIRED,
    NOT_REQUIRED,
}

internal interface PlannerToolExecutionObserver {
    fun toolStarted(toolName: String)
    fun toolSucceeded(toolName: String)
    fun toolFailed(toolName: String)
}

internal class AgentTurnExecutionTracker(
    private val diagnosticSink: (String) -> Unit = {},
) : (String) -> Unit, PlannerToolExecutionObserver {
    private val lock = Any()
    private var execution = AgentTurnExecution()

    override fun invoke(message: String) = diagnosticSink(message)

    override fun toolStarted(toolName: String) = synchronized(lock) {
        val mutation = PlannerToolPermissions.riskOf(toolName)?.isMutation == true
        execution = execution.copy(
            toolCallCount = execution.toolCallCount + 1,
            mutationCallCount = execution.mutationCallCount + if (mutation) 1 else 0,
        )
    }

    override fun toolSucceeded(toolName: String) = synchronized(lock) {
        if (PlannerToolPermissions.riskOf(toolName)?.isMutation == true) {
            execution = execution.copy(successfulMutationCount = execution.successfulMutationCount + 1)
        }
    }

    override fun toolFailed(toolName: String) = synchronized(lock) {
        if (PlannerToolPermissions.riskOf(toolName)?.isMutation == true) {
            execution = execution.copy(failedMutationCount = execution.failedMutationCount + 1)
        }
    }

    fun reset() = synchronized(lock) { execution = AgentTurnExecution() }

    fun snapshot(): AgentTurnExecution = synchronized(lock) { execution }
}

internal fun ((String) -> Unit).plannerToolStarted(toolName: String) {
    (this as? PlannerToolExecutionObserver)?.toolStarted(toolName)
    invoke(toolCallDiagnostic(toolName))
}

internal fun ((String) -> Unit).plannerToolSucceeded(toolName: String) {
    (this as? PlannerToolExecutionObserver)?.toolSucceeded(toolName)
    invoke(toolSuccessDiagnostic())
}

internal fun ((String) -> Unit).plannerToolFailed(toolName: String) {
    (this as? PlannerToolExecutionObserver)?.toolFailed(toolName)
    invoke(toolFailureDiagnostic())
}

internal class KoogMutationExpectationClassifier(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
) {
    suspend fun classify(promptText: String): AgentMutationExpectation {
        val mutationTools = PlannerToolPermissions.mutationToolNames.joinToString(", ")
        val response = promptExecutor.execute(
            prompt(
                id = "planner-mutation-expectation-${UUID.randomUUID()}",
                params = LLMParams(temperature = 0.0),
            ) {
                system(
                    """
                    Classify whether the current EVE Static Map Planner user turn explicitly requires an actual
                    non-read-only Planner tool effect. The registered non-read-only tools are: $mutationTools.
                    If the input contains conversation JSON, classify only currentUserMessage; use recentMessages
                    solely to resolve references such as "it" or "that route".

                    Return requiresMapMutation=true when the current user explicitly asks Planner to focus, display,
                    draw, create, add, remove, clear, rename, switch, save, send, or otherwise carry out an effect
                    represented by one of those tools. Missing parameters do not turn an action request into a
                    read-only request. Return false for facts, explanations, usage questions, system information,
                    or route calculations that the user did not ask to display or apply.

                    Return exactly one JSON object and no Markdown:
                    {"requiresMapMutation":true}
                    or
                    {"requiresMapMutation":false}
                    """.trimIndent(),
                )
                user(promptText)
            },
            model,
            emptyList(),
        ).textContent()
        return checkNotNull(parseMutationExpectation(response)) {
            "The provider returned an invalid mutation-expectation response"
        }
    }
}

internal fun parseMutationExpectation(response: String): AgentMutationExpectation? = runCatching {
    val required = Json.parseToJsonElement(response.trim())
        .jsonObject["requiresMapMutation"]
        ?.jsonPrimitive
        ?.booleanOrNull
        ?: error("Missing requiresMapMutation")
    if (required) AgentMutationExpectation.REQUIRED else AgentMutationExpectation.NOT_REQUIRED
}.getOrNull()

private val PlannerToolRisk.isMutation: Boolean
    get() = this != PlannerToolRisk.READ_ONLY
