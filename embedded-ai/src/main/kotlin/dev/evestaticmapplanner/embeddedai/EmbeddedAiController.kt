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
    val chatSession: EmbeddedAiChatSession = EmbeddedAiChatSession.create(),
    val response: String = "",
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val runtimeInfo: EmbeddedAiRuntimeInfo? = null,
    val scrollRequest: Long = 0,
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
    private val historyRevision = AtomicLong()
    private val messageSequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val historyLock = Any()
    private val agentHistory = mutableListOf<AgentConversationMessage>()
    private val mutableState = MutableStateFlow(EmbeddedAiUiState())
    private val actionConfirmationService = confirmationService
        ?: (agentFactory as? AiActionConfirmationOwner)?.actionConfirmationService
        ?: AiActionConfirmationService()
    private var agent: EmbeddedAiAgent? = null
    private var agentRevision: Long = -1
    private var request: Job? = null
    private var activeTurn: ActiveTurn? = null

    val state: StateFlow<EmbeddedAiUiState> = mutableState.asStateFlow()
    val confirmation: StateFlow<AiActionConfirmation?> = actionConfirmationService.pending

    fun send(prompt: String) {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || closed.get() || request?.isActive == true) return
        if (normalized.codePointCount(0, normalized.length) > MAX_PROMPT_CODE_POINTS) {
            appendImmediateError(
                normalized,
                "The request is too long. Keep it under $MAX_PROMPT_CODE_POINTS characters.",
            )
            return
        }

        val requestGeneration = generation.incrementAndGet()
        val requestHistoryRevision = historyRevision.get()
        val confirmationRequestId = "embedded-ai-request-$requestGeneration"
        val userMessage = message(EmbeddedAiMessageRole.USER, normalized)
        val assistantMessage = message(
            role = EmbeddedAiMessageRole.ASSISTANT,
            content = "",
            status = EmbeddedAiMessageStatus.THINKING,
        )
        activeTurn = ActiveTurn(userMessage, assistantMessage.id, requestHistoryRevision)
        mutableState.value = mutableState.value.withMessages(userMessage, assistantMessage).copy(
            response = "",
            errorMessage = null,
            isLoading = true,
            scrollRequest = mutableState.value.scrollRequest + 1,
        )
        val contextualPrompt = buildAgentPrompt(historySnapshot(), normalized)
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
                val response = currentAgent.run(contextualPrompt).boundedAssistantMessage()
                withContext(uiDispatcher) {
                    updateIfCurrent(requestGeneration) { current ->
                        recordHistoryIfCurrent(requestHistoryRevision, userMessage.content, response)
                        current.completeAssistant(assistantMessage.id, response).copy(
                            response = response,
                            errorMessage = null,
                            isLoading = false,
                            runtimeInfo = currentAgent.runtimeInfo,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val safeMessage = failure.safeUiMessage()
                withContext(uiDispatcher) {
                    updateIfCurrent(requestGeneration) { current ->
                        recordHistoryIfCurrent(requestHistoryRevision, userMessage.content, safeMessage)
                        current.completeAssistant(
                            assistantMessage.id,
                            safeMessage,
                            EmbeddedAiMessageStatus.ERROR,
                        ).copy(
                            response = "",
                            errorMessage = safeMessage,
                            isLoading = false,
                            runtimeInfo = agent?.runtimeInfo,
                        )
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
        historyRevision.incrementAndGet()
        clearAgentHistory()
        configurationRevision.incrementAndGet()
        if (request?.isActive != true) scope.launch { closeStaleAgent() }
    }

    fun cancel() {
        if (closed.get()) return
        val turn = activeTurn
        generation.incrementAndGet()
        actionConfirmationService.invalidate("The action was cancelled with the AI request.")
        request?.cancel()
        request = null
        activeTurn = null
        if (turn != null) {
            val cancelledMessage = "Request cancelled."
            recordHistoryIfCurrent(turn.historyRevision, turn.userMessage.content, cancelledMessage)
            mutableState.value = mutableState.value.completeAssistant(
                turn.assistantMessageId,
                cancelledMessage,
                EmbeddedAiMessageStatus.CANCELLED,
            ).copy(
                response = cancelledMessage,
                errorMessage = null,
                isLoading = false,
            )
        }
    }

    fun newChat() {
        if (closed.get()) return
        generation.incrementAndGet()
        historyRevision.incrementAndGet()
        clearAgentHistory()
        actionConfirmationService.invalidate("The action was cancelled because a new chat started.")
        val activeRequest = request
        request = null
        activeTurn = null
        activeRequest?.cancel()
        configurationRevision.incrementAndGet()
        mutableState.value = EmbeddedAiUiState(
            chatSession = EmbeddedAiChatSession.create(),
            runtimeInfo = mutableState.value.runtimeInfo,
            scrollRequest = mutableState.value.scrollRequest + 1,
        )
        scope.launch {
            activeRequest?.join()
            closeStaleAgent()
        }
    }

    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        actionConfirmationService.invalidate("The action was cancelled because the application is closing.")
        val activeRequest = request
        request = null
        activeTurn = null
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

    private inline fun updateIfCurrent(
        requestGeneration: Long,
        update: (EmbeddedAiUiState) -> EmbeddedAiUiState,
    ) {
        if (!closed.get() && generation.get() == requestGeneration) {
            mutableState.value = update(mutableState.value)
            request = null
            activeTurn = null
        }
    }

    private fun message(
        role: EmbeddedAiMessageRole,
        content: String,
        status: EmbeddedAiMessageStatus = EmbeddedAiMessageStatus.COMPLETE,
    ) = EmbeddedAiMessage(
        id = "chat-message-${messageSequence.incrementAndGet()}",
        role = role,
        content = content,
        status = status,
    )

    private fun appendImmediateError(prompt: String, error: String) {
        val user = message(EmbeddedAiMessageRole.USER, prompt)
        val assistant = message(EmbeddedAiMessageRole.ASSISTANT, error, EmbeddedAiMessageStatus.ERROR)
        mutableState.value = mutableState.value.withMessages(user, assistant).copy(
            response = "",
            errorMessage = error,
            isLoading = false,
            scrollRequest = mutableState.value.scrollRequest + 1,
        )
    }

    private fun historySnapshot(): List<AgentConversationMessage> = synchronized(historyLock) {
        agentHistory.toList()
    }

    private fun recordHistoryIfCurrent(revision: Long, user: String, assistant: String) {
        if (historyRevision.get() != revision) return
        synchronized(historyLock) {
            if (historyRevision.get() != revision) return
            agentHistory += AgentConversationMessage(EmbeddedAiMessageRole.USER, user)
            agentHistory += AgentConversationMessage(EmbeddedAiMessageRole.ASSISTANT, assistant)
            val bounded = boundedAgentHistory(agentHistory)
            agentHistory.clear()
            agentHistory += bounded
        }
    }

    private fun clearAgentHistory() = synchronized(historyLock) { agentHistory.clear() }

    private data class ActiveTurn(
        val userMessage: EmbeddedAiMessage,
        val assistantMessageId: String,
        val historyRevision: Long,
    )
}

const val MAX_PROMPT_CODE_POINTS = 8_000

private fun EmbeddedAiUiState.withMessages(vararg additions: EmbeddedAiMessage): EmbeddedAiUiState {
    val bounded = (chatSession.messages + additions).takeLast(MAX_CHAT_MESSAGES)
    return copy(chatSession = chatSession.copy(messages = bounded))
}

private fun EmbeddedAiUiState.completeAssistant(
    messageId: String,
    content: String,
    status: EmbeddedAiMessageStatus = EmbeddedAiMessageStatus.COMPLETE,
): EmbeddedAiUiState = copy(
    chatSession = chatSession.copy(
        messages = chatSession.messages.map { message ->
            if (message.id == messageId) message.copy(content = content, status = status) else message
        },
    ),
)

private fun String.boundedAssistantMessage(): String =
    if (length <= MAX_ASSISTANT_MESSAGE_CHARACTERS) this else take(MAX_ASSISTANT_MESSAGE_CHARACTERS) + "\n\n[Response truncated]"

private fun Throwable.safeUiMessage(): String = when (this) {
    is AiProviderException -> safeMessage
    is EmbeddedAiToolException -> "Planner tool failed: $message"
    else -> toSafeProviderException().safeMessage
}
