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
)

class EmbeddedAiController(
    private val agentFactory: EmbeddedAiAgentFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = dispatcher,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val agentMutation = Mutex()
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val mutableState = MutableStateFlow(EmbeddedAiUiState())
    private var agent: EmbeddedAiAgent? = null
    private var request: Job? = null

    val state: StateFlow<EmbeddedAiUiState> = mutableState.asStateFlow()

    fun send(prompt: String) {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || closed.get() || request?.isActive == true) return

        val requestGeneration = generation.incrementAndGet()
        mutableState.value = EmbeddedAiUiState(isLoading = true)
        request = scope.launch {
            try {
                val currentAgent = agentMutation.withLock {
                    agent ?: agentFactory.create().also { agent = it }
                }
                val response = currentAgent.run(normalized)
                withContext(uiDispatcher) {
                    updateIfCurrent(requestGeneration) {
                        EmbeddedAiUiState(response = response)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                withContext(uiDispatcher) {
                    updateIfCurrent(requestGeneration) {
                        EmbeddedAiUiState(errorMessage = failure.safeUiMessage())
                    }
                }
            }
        }
    }

    fun cancel() {
        if (closed.get()) return
        generation.incrementAndGet()
        request?.cancel()
        request = null
        mutableState.value = EmbeddedAiUiState(response = "Request cancelled.")
    }

    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        val activeRequest = request
        request = null
        supervisor.cancel()
        activeRequest?.cancelAndJoin()
        agentMutation.withLock {
            agent?.close()
            agent = null
        }
    }

    private inline fun updateIfCurrent(requestGeneration: Long, update: () -> EmbeddedAiUiState) {
        if (!closed.get() && generation.get() == requestGeneration) {
            mutableState.value = update()
            request = null
        }
    }
}

private fun Throwable.safeUiMessage(): String = when (this) {
    is MissingOpenAiApiKeyException ->
        "OPENAI_API_KEY is not set. Set it before using the embedded assistant."
    is EmbeddedAiToolException -> "Planner tool failed: $message"
    else -> "AI request failed (${this::class.simpleName ?: "unknown error"}). Check network access and provider settings."
}
