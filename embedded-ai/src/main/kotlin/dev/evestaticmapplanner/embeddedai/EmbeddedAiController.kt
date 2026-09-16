package dev.evestaticmapplanner.embeddedai

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EmbeddedAiUiState(
    val response: String = "",
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val runtimeInfo: EmbeddedAiRuntimeInfo? = null,
)

class EmbeddedAiController(
    private val agentFactory: EmbeddedAiAgentFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = dispatcher,
    confirmationService: AiActionConfirmationService? = null,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val agentMutation = Mutex()
    private val generation = AtomicLong()
    private val configurationRevision = AtomicLong()
    private val closed = AtomicBoolean()
    private val mutableState = MutableStateFlow(EmbeddedAiUiState())
    private val actionConfirmationService = confirmationService
        ?: (agentFactory as? AiActionConfirmationOwner)?.actionConfirmationService
        ?: AiActionConfirmationService()
    private var agent: EmbeddedAiAgent? = null
    private var agentRevision: Long = -1
    private var request: Job? = null

    val state: StateFlow<EmbeddedAiUiState> = mutableState.asStateFlow()
    val confirmation: StateFlow<AiActionConfirmation?> = actionConfirmationService.pending

    fun send(prompt: String) {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || closed.get() || request?.isActive == true) return
        if (normalized.codePointCount(0, normalized.length) > MAX_PROMPT_CODE_POINTS) {
            mutableState.value = EmbeddedAiUiState(
                errorMessage = "The request is too long. Keep it under $MAX_PROMPT_CODE_POINTS characters.",
            )
            return
        }

        val requestGeneration = generation.incrementAndGet()
        val confirmationRequestId = "embedded-ai-request-$requestGeneration"
        mutableState.value = EmbeddedAiUiState(isLoading = true)
        request = scope.launch {
            actionConfirmationService.startRequest(confirmationRequestId)
            try {
                val currentRevision = configurationRevision.get()
                val currentAgent = agentMutation.withLock {
                    if (agent != null && agentRevision != currentRevision) {
                        agent?.close()
                        agent = null
                    }
                    agent ?: agentFactory.create().also {
                        agent = it
                        agentRevision = currentRevision
                    }
                }
                val response = currentAgent.run(normalized)
                withContext(uiDispatcher) {
                    updateIfCurrent(requestGeneration) {
                        EmbeddedAiUiState(response = response, runtimeInfo = currentAgent.runtimeInfo)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                withContext(uiDispatcher) {
                    updateIfCurrent(requestGeneration) {
                        EmbeddedAiUiState(errorMessage = failure.safeUiMessage(), runtimeInfo = agent?.runtimeInfo)
                    }
                }
            } finally {
                actionConfirmationService.finishRequest(confirmationRequestId)
                closeStaleAgent()
            }
        }
    }

    /** Current work finishes on its existing provider; the next request lazily creates a fresh runtime. */
    fun configurationChanged() {
        if (closed.get()) return
        actionConfirmationService.invalidate("The action was cancelled because the AI provider configuration changed.")
        configurationRevision.incrementAndGet()
        if (request?.isActive != true) scope.launch { closeStaleAgent() }
    }

    fun cancel() {
        if (closed.get()) return
        generation.incrementAndGet()
        actionConfirmationService.invalidate("The action was cancelled with the AI request.")
        request?.cancel()
        request = null
        mutableState.value = EmbeddedAiUiState(response = "Request cancelled.")
    }

    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        actionConfirmationService.invalidate("The action was cancelled because the application is closing.")
        val activeRequest = request
        request = null
        supervisor.cancel()
        activeRequest?.cancelAndJoin()
        agentMutation.withLock {
            agent?.close()
            agent = null
            agentRevision = -1
        }
    }

    fun approveAction(actionId: String): Boolean = actionConfirmationService.approve(actionId)

    fun denyAction(actionId: String): Boolean = actionConfirmationService.deny(actionId)

    private suspend fun closeStaleAgent() {
        if (agentRevision == configurationRevision.get()) return
        agentMutation.withLock {
            if (agentRevision != configurationRevision.get()) {
                agent?.close()
                agent = null
                agentRevision = -1
            }
        }
    }

    private inline fun updateIfCurrent(requestGeneration: Long, update: () -> EmbeddedAiUiState) {
        if (!closed.get() && generation.get() == requestGeneration) {
            mutableState.value = update()
            request = null
        }
    }
}

const val MAX_PROMPT_CODE_POINTS = 8_000

private fun Throwable.safeUiMessage(): String = when (this) {
    is AiProviderException -> safeMessage
    is EmbeddedAiToolException -> "Planner tool failed: $message"
    else -> toSafeProviderException().safeMessage
}
