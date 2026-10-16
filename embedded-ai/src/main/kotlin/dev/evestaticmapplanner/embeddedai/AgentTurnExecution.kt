package dev.evestaticmapplanner.embeddedai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class AgentToolCallStatus {
    RUNNING,
    WAITING_CONFIRMATION,
    SUCCESS,
    FAILURE,
    CANCELLED,
}

data class AgentToolCall(
    val callId: String,
    val toolName: String,
    val status: AgentToolCallStatus,
    val errorMessage: String? = null,
)

data class AgentTurnExecution(
    val toolCalls: List<AgentToolCall> = emptyList(),
) {
    val toolCallCount: Int get() = toolCalls.size
    val mutationCallCount: Int get() = toolCalls.count(AgentToolCall::isMutation)
    val successfulMutationCount: Int get() = toolCalls.count {
        it.isMutation && it.status == AgentToolCallStatus.SUCCESS
    }
    val failedMutationCount: Int get() = toolCalls.count {
        it.isMutation && it.status == AgentToolCallStatus.FAILURE
    }
    val hasSuccessfulMutation: Boolean get() = toolCalls.any {
        it.isMutation && it.status == AgentToolCallStatus.SUCCESS
    }

    operator fun plus(other: AgentTurnExecution): AgentTurnExecution = AgentTurnExecution(
        toolCalls = toolCalls + other.toolCalls,
    )
}

enum class AgentMutationExpectation {
    REQUIRED,
    NOT_REQUIRED,
}

interface EmbeddedAiTurnObserver {
    fun textDelta(text: String) = Unit
    fun toolExecutionChanged(execution: AgentTurnExecution) = Unit
}

internal class AgentTurnExecutionTracker(
    private val diagnosticSink: (String) -> Unit = {},
) : (String) -> Unit {
    private val lock = Any()
    private val calls = LinkedHashMap<String, AgentToolCall>()
    private var observer: EmbeddedAiTurnObserver? = null

    override fun invoke(message: String) = diagnosticSink(message)

    fun toolStarted(toolName: String, callId: String = UUID.randomUUID().toString()): String = synchronized(lock) {
        calls[callId] = AgentToolCall(
            callId = callId,
            toolName = toolName,
            status = AgentToolCallStatus.RUNNING,
        )
        observer?.toolExecutionChanged(snapshotLocked())
        callId
    }

    fun toolWaitingForConfirmation(callId: String) = update(callId, AgentToolCallStatus.WAITING_CONFIRMATION)

    fun toolRunning(callId: String) = update(callId, AgentToolCallStatus.RUNNING)

    fun toolSucceeded(callId: String) = update(callId, AgentToolCallStatus.SUCCESS)

    fun toolFailed(callId: String, errorMessage: String? = null) = update(
        callId,
        AgentToolCallStatus.FAILURE,
        errorMessage,
    )

    fun toolCancelled(callId: String, message: String? = null) = update(
        callId,
        AgentToolCallStatus.CANCELLED,
        message,
    )

    fun cancelActive(message: String? = null) {
        synchronized(lock) {
            calls.replaceAll { _, call ->
                if (call.status.isActive) {
                    call.copy(status = AgentToolCallStatus.CANCELLED, errorMessage = message)
                } else {
                    call
                }
            }
            observer?.toolExecutionChanged(snapshotLocked())
        }
    }

    fun reset(observer: EmbeddedAiTurnObserver? = null) {
        synchronized(lock) {
            calls.clear()
            this.observer = observer
        }
    }

    fun snapshot(): AgentTurnExecution = synchronized(lock, ::snapshotLocked)

    fun status(callId: String): AgentToolCallStatus? = synchronized(lock) { calls[callId]?.status }

    private fun update(callId: String, status: AgentToolCallStatus, errorMessage: String? = null) {
        synchronized(lock) {
            val previous = calls[callId] ?: return
            calls[callId] = previous.copy(
                status = status,
                errorMessage = errorMessage,
            )
            observer?.toolExecutionChanged(snapshotLocked())
        }
    }

    private fun snapshotLocked() = AgentTurnExecution(calls.values.toList())
}

internal class AgentTurnContext(
    val tracker: AgentTurnExecutionTracker,
    val observer: EmbeddedAiTurnObserver,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentTurnContext>
}

internal class ActivePlannerToolCall(
    val callId: String,
    val toolName: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActivePlannerToolCall>
}

internal suspend fun plannerToolStarted(
    toolName: String,
    diagnostics: (String) -> Unit,
): String {
    val context = currentCoroutineContext()[AgentTurnContext]
    val existing = currentCoroutineContext()[ActivePlannerToolCall]
        ?.takeIf { it.toolName == toolName }
        ?.callId
    val callId = existing ?: context?.tracker?.toolStarted(toolName) ?: UUID.randomUUID().toString()
    if (existing == null) diagnostics(toolCallDiagnostic(toolName))
    return callId
}

internal suspend fun plannerToolWaitingForConfirmation(callId: String) {
    currentCoroutineContext()[AgentTurnContext]?.tracker?.toolWaitingForConfirmation(callId)
}

internal suspend fun plannerToolRunning(callId: String) {
    currentCoroutineContext()[AgentTurnContext]?.tracker?.toolRunning(callId)
}

internal suspend fun plannerToolSucceeded(callId: String, diagnostics: (String) -> Unit) {
    currentCoroutineContext()[AgentTurnContext]?.tracker?.toolSucceeded(callId)
    diagnostics(toolSuccessDiagnostic())
}

internal suspend fun plannerToolFailed(
    callId: String,
    diagnostics: (String) -> Unit,
    errorMessage: String? = null,
) {
    currentCoroutineContext()[AgentTurnContext]?.tracker?.toolFailed(callId, errorMessage)
    diagnostics(toolFailureDiagnostic())
}

internal suspend fun plannerToolCancelled(
    callId: String,
    diagnostics: (String) -> Unit,
    message: String? = null,
) {
    currentCoroutineContext()[AgentTurnContext]?.tracker?.toolCancelled(callId, message)
    diagnostics(toolCancellationDiagnostic())
}

internal suspend fun <T> trackWholePlannerToolCall(
    toolName: String,
    diagnostics: (String) -> Unit,
    execute: suspend () -> T,
): T {
    val tracker = currentCoroutineContext()[AgentTurnContext]?.tracker ?: return execute()
    val callId = plannerToolStarted(toolName, diagnostics)
    return try {
        withContext(ActivePlannerToolCall(callId, toolName)) { execute() }
    } catch (cancelled: CancellationException) {
        if (tracker.status(callId)?.isActive == true) {
            plannerToolCancelled(callId, diagnostics, "Cancelled with the AI request.")
        }
        throw cancelled
    } catch (failure: Throwable) {
        if (tracker.status(callId)?.isActive == true) {
            val message = if (failure is EmbeddedAiToolException) {
                failure.message ?: "The Planner tool failed."
            } else {
                "The Planner tool could not complete."
            }
            plannerToolFailed(callId, diagnostics, message)
        }
        throw failure
    }
}

private val AgentToolCall.isMutation: Boolean
    get() = PlannerToolPermissions.riskOf(toolName)?.isMutation == true

private val AgentToolCallStatus.isActive: Boolean
    get() = this == AgentToolCallStatus.RUNNING || this == AgentToolCallStatus.WAITING_CONFIRMATION

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
