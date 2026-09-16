package dev.evestaticmapplanner.embeddedai

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AiActionDetail(
    val label: String,
    val value: String,
)

data class AiActionConfirmation(
    val actionId: String,
    val toolName: String,
    val risk: PlannerToolRisk,
    val action: String,
    val target: String,
    val details: List<AiActionDetail>,
    val effect: String,
)

internal data class AiActionRequest(
    val toolName: String,
    val risk: PlannerToolRisk,
    val normalizedArguments: String,
    val action: String,
    val target: String,
    val details: List<AiActionDetail> = emptyList(),
    val effect: String,
) {
    init {
        require(risk.requiresConfirmation) { "Only confirmation-required actions may use the confirmation gateway" }
        require(toolName.isNotBlank())
        require(normalizedArguments.isNotBlank())
        require(action.isNotBlank())
        require(target.isNotBlank())
        require(effect.isNotBlank())
    }
}

/**
 * In-process, fail-closed authorization boundary for embedded-AI side effects.
 *
 * Approval is never represented in model-visible arguments. The service binds one UI decision to the active
 * controller request plus an exact tool/argument fingerprint, then owns execution so duplicate model calls share
 * one result and cannot repeat the side effect.
 */
class AiActionConfirmationService(
    private val diagnostics: (String) -> Unit = {},
) {
    private val lock = Any()
    private val mutablePending = MutableStateFlow<AiActionConfirmation?>(null)
    private val executions = linkedMapOf<ActionFingerprint, ActionExecution>()
    private var activeRequestId: String? = null

    val pending: StateFlow<AiActionConfirmation?> = mutablePending.asStateFlow()

    internal fun startRequest(requestId: String) {
        require(requestId.isNotBlank())
        invalidate("A newer AI request replaced the pending action.")
        synchronized(lock) {
            activeRequestId = requestId
        }
    }

    internal fun finishRequest(requestId: String) {
        val cancelled = synchronized(lock) {
            if (activeRequestId == requestId) activeRequestId = null
            val matching = executions.values.filter { it.requestId == requestId }
            val pendingActions = matching.filter { it.status == ExecutionStatus.PENDING }
            if (mutablePending.value?.actionId in matching.map { it.confirmation.actionId }) {
                mutablePending.value = null
            }
            pendingActions.forEach { execution ->
                execution.status = ExecutionStatus.COMPLETED
                execution.decision.complete(false)
                execution.result.complete(cancelledResult("The action was cancelled because the AI request ended."))
            }
            executions.entries.removeIf { it.value.requestId == requestId }
            pendingActions
        }
        cancelled.forEach { execution ->
            diagnostics(actionAudit(execution, "cancelled"))
        }
    }

    fun approve(actionId: String): Boolean {
        val execution = synchronized(lock) {
            executions.values.singleOrNull {
                it.confirmation.actionId == actionId &&
                    it.status == ExecutionStatus.PENDING &&
                    !it.decision.isCompleted &&
                    it.requestId == activeRequestId
            }?.also {
                mutablePending.value = null
                it.decision.complete(true)
            }
        } ?: return false
        diagnostics(actionAudit(execution, "approved"))
        return true
    }

    fun deny(actionId: String): Boolean {
        val execution = synchronized(lock) {
            executions.values.singleOrNull {
                it.confirmation.actionId == actionId &&
                    it.status == ExecutionStatus.PENDING &&
                    !it.decision.isCompleted
            }?.also {
                it.status = ExecutionStatus.COMPLETED
                if (mutablePending.value?.actionId == actionId) mutablePending.value = null
                it.decision.complete(false)
                it.result.complete(cancelledResult("The action was cancelled."))
            }
        } ?: return false
        diagnostics(actionAudit(execution, "denied"))
        return true
    }

    fun invalidate(reason: String) {
        val cancelled = synchronized(lock) {
            activeRequestId = null
            mutablePending.value = null
            executions.values.filter { it.status == ExecutionStatus.PENDING }.onEach { execution ->
                execution.status = ExecutionStatus.COMPLETED
                execution.decision.complete(false)
                execution.result.complete(cancelledResult(reason))
            }
        }
        cancelled.forEach { execution -> diagnostics(actionAudit(execution, "invalidated")) }
    }

    internal suspend fun confirmAndExecute(
        request: AiActionRequest,
        execute: suspend (idempotencyKey: String) -> String,
    ): String {
        val registration = synchronized(lock) { register(request) }
        when (registration) {
            is Registration.Rejected -> return cancelledResult(registration.reason)
            is Registration.Duplicate -> {
                diagnostics(actionAudit(registration.execution, "deduplicated"))
                return registration.execution.result.await()
            }
            is Registration.Owner -> Unit
        }

        val execution = registration.execution
        diagnostics(actionAudit(execution, "requested"))
        try {
            if (!execution.decision.await()) return execution.result.await()
            val claimed = synchronized(lock) {
                if (
                    execution.status == ExecutionStatus.PENDING &&
                    execution.requestId == activeRequestId &&
                    !execution.result.isCompleted
                ) {
                    execution.status = ExecutionStatus.EXECUTING
                    true
                } else {
                    false
                }
            }
            if (!claimed) return execution.result.await()

            val output = execute(execution.idempotencyKey)
            synchronized(lock) {
                execution.status = ExecutionStatus.COMPLETED
                execution.result.complete(output)
            }
            diagnostics(actionAudit(execution, "completed"))
            return output
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                execution.status = ExecutionStatus.COMPLETED
                execution.result.completeExceptionally(cancelled)
            }
            diagnostics(actionAudit(execution, "cancelled"))
            throw cancelled
        } catch (failure: Throwable) {
            synchronized(lock) {
                execution.status = ExecutionStatus.COMPLETED
                execution.result.completeExceptionally(failure)
            }
            diagnostics(actionAudit(execution, "failed"))
            throw failure
        }
    }

    private fun register(request: AiActionRequest): Registration {
        val requestId = activeRequestId
            ?: return Registration.Rejected("The action was not performed because confirmation is unavailable.")
        val fingerprint = ActionFingerprint(requestId, request.toolName, request.normalizedArguments)
        executions[fingerprint]?.let { return Registration.Duplicate(it) }
        if (executions.values.any { it.status != ExecutionStatus.COMPLETED }) {
            return Registration.Rejected("The action was not performed because another protected action is still active.")
        }

        val actionId = UUID.randomUUID().toString()
        val execution = ActionExecution(
            requestId = requestId,
            confirmation = AiActionConfirmation(
                actionId = actionId,
                toolName = request.toolName,
                risk = request.risk,
                action = request.action,
                target = request.target,
                details = request.details,
                effect = request.effect,
            ),
            idempotencyKey = "embedded-ai-action-$actionId",
        )
        executions[fingerprint] = execution
        mutablePending.value = execution.confirmation
        return Registration.Owner(execution)
    }

    private fun actionAudit(execution: ActionExecution, status: String): String =
        "AI action audit: tool=${execution.confirmation.toolName} actionId=${execution.confirmation.actionId} status=$status"

    private data class ActionFingerprint(
        val requestId: String,
        val toolName: String,
        val normalizedArguments: String,
    )

    private class ActionExecution(
        val requestId: String,
        val confirmation: AiActionConfirmation,
        val idempotencyKey: String,
        val decision: CompletableDeferred<Boolean> = CompletableDeferred(),
        val result: CompletableDeferred<String> = CompletableDeferred(),
        var status: ExecutionStatus = ExecutionStatus.PENDING,
    )

    private enum class ExecutionStatus {
        PENDING,
        EXECUTING,
        COMPLETED,
    }

    private sealed interface Registration {
        data class Owner(val execution: ActionExecution) : Registration
        data class Duplicate(val execution: ActionExecution) : Registration
        data class Rejected(val reason: String) : Registration
    }
}

internal fun cancelledResult(message: String): String = buildJsonObject {
    put("success", false)
    put("status", "cancelled")
    put("message", message)
}.toString()
