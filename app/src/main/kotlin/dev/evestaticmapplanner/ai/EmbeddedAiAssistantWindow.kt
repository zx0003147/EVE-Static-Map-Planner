package dev.evestaticmapplanner.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.embeddedai.AiActionConfirmation
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiProviderType
import dev.evestaticmapplanner.embeddedai.EmbeddedAiController
import dev.evestaticmapplanner.embeddedai.EmbeddedAiMessage
import dev.evestaticmapplanner.embeddedai.EmbeddedAiMessageRole
import dev.evestaticmapplanner.embeddedai.EmbeddedAiMessageStatus
import dev.evestaticmapplanner.embeddedai.EmbeddedAiUiState
import dev.evestaticmapplanner.embeddedai.PlannerToolRisk
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface

@Composable
fun EmbeddedAiAssistantWindow(
    controller: EmbeddedAiController,
    providerStatus: AiAssistantProviderStatus,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val confirmation by controller.confirmation.collectAsState()

    Window(
        onCloseRequest = {
            controller.cancel()
            onDismiss()
        },
        title = "Embedded AI Assistant",
        state = rememberWindowState(width = 880.dp, height = 680.dp),
    ) {
        EveWindowChrome(window)
        EveWindowSurface(Modifier.fillMaxSize()) {
            EmbeddedAiAssistantContent(
                state = state,
                confirmation = confirmation,
                providerStatus = providerStatus,
                onSend = controller::send,
                onCancel = controller::cancel,
                onNewChat = controller::newChat,
                onSelectChat = controller::selectChat,
                onOpenSettings = onOpenSettings,
                onApprove = controller::approveAction,
                onDeny = controller::denyAction,
            )
        }
    }
}

@Composable
internal fun EmbeddedAiAssistantContent(
    state: EmbeddedAiUiState,
    confirmation: AiActionConfirmation?,
    providerStatus: AiAssistantProviderStatus,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onApprove: (String) -> Boolean,
    onDeny: (String) -> Boolean,
) {
    var prompt by remember { mutableStateOf(TextFieldValue()) }
    var sidebarVisible by remember { mutableStateOf(true) }
    val inputFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val messages = state.chatSession.messages
    val nearBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            layout.totalItemsCount == 0 || lastVisible >= layout.totalItemsCount - 2
        }
    }
    val lastMessageSnapshot = messages.lastOrNull()?.let { "${it.id}:${it.status}:${it.content.length}" }

    LaunchedEffect(state.scrollRequest) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(lastMessageSnapshot) {
        if (messages.isNotEmpty() && nearBottom) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(Unit) { inputFocusRequester.requestFocus() }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag(AI_ASSISTANT_ROOT_TEST_TAG),
    ) {
        if (sidebarVisible) {
            ConversationSidebar(
                sessions = state.sessions,
                activeSessionId = state.chatSession.id,
                onNewChat = onNewChat,
                onSelectChat = onSelectChat,
                onHide = { sidebarVisible = false },
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!sidebarVisible) {
                    TextButton(
                        onClick = { sidebarVisible = true },
                        modifier = Modifier.testTag(AI_SHOW_SIDEBAR_TEST_TAG),
                    ) { Text("Show Sidebar") }
                }
                Text("Embedded AI Assistant", style = MaterialTheme.typography.titleLarge)
            }
            Text(providerStatus.description, color = EveColors.SecondaryText)
            state.contextNotice?.let { Text(it, color = EveColors.SecondaryText) }
            if (!providerStatus.ready) {
                TextButton(onClick = onOpenSettings) { Text("Open AI Settings") }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(EveColors.InputSurface, RoundedCornerShape(6.dp))
                    .padding(10.dp),
            ) {
                if (messages.isEmpty()) {
                    Text(
                        if (providerStatus.ready) "Start a conversation with the Planner." else providerStatus.actionMessage,
                        color = EveColors.SecondaryText,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize().testTag(AI_CHAT_LIST_TEST_TAG),
                    ) {
                        items(messages, key = EmbeddedAiMessage::id) { message ->
                            ChatMessageBubble(
                                message = message,
                                waitingForConfirmation = confirmation != null &&
                                    message.status == EmbeddedAiMessageStatus.THINKING,
                            )
                        }
                    }
                }
            }

            val inputEnabled = !state.isLoading && providerStatus.ready
            fun submitPrompt() {
                val submitted = prompt.text
                if (!inputEnabled || submitted.isBlank()) return
                prompt = TextFieldValue()
                onSend(submitted)
                inputFocusRequester.requestFocus()
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Message") },
                enabled = inputEnabled,
                minLines = 2,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Enter || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (chatEnterAction(prompt, event.isShiftPressed, inputEnabled)) {
                            ChatEnterAction.SEND -> {
                                submitPrompt()
                                true
                            }
                            ChatEnterAction.NEW_LINE, ChatEnterAction.IME_COMPOSITION -> false
                            ChatEnterAction.IGNORE -> true
                        }
                    }
                    .testTag(AI_CHAT_INPUT_TEST_TAG),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                Button(
                    onClick = ::submitPrompt,
                    enabled = prompt.text.isNotBlank() && inputEnabled,
                ) { Text("Send") }
                Button(
                    onClick = onCancel,
                    enabled = state.isLoading,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Cancel") }
            }
        }
    }

    confirmation?.let { action ->
        AiActionConfirmationDialog(
            confirmation = action,
            onCancel = { onDeny(action.actionId) },
            onAllow = { onApprove(action.actionId) },
        )
    }
}

@Composable
private fun ConversationSidebar(
    sessions: List<dev.evestaticmapplanner.embeddedai.EmbeddedAiChatSession>,
    activeSessionId: String,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onHide: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(210.dp)
            .fillMaxSize()
            .background(EveColors.InputSurface, RoundedCornerShape(6.dp))
            .padding(10.dp)
            .testTag(AI_SESSION_SIDEBAR_TEST_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chats", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onHide, modifier = Modifier.testTag(AI_HIDE_SIDEBAR_TEST_TAG)) {
                Text("Hide Sidebar")
            }
        }
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) { Text("+ New Chat") }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(sessions, key = { it.id }) { session ->
                TextButton(
                    onClick = { onSelectChat(session.id) },
                    selected = session.id == activeSessionId,
                    modifier = Modifier.fillMaxWidth().testTag("$AI_SESSION_ITEM_TEST_TAG_PREFIX-${session.id}"),
                ) {
                    Text(session.title, maxLines = 2)
                }
            }
        }
        Text(
            "History is stored only on this device.",
            style = MaterialTheme.typography.labelSmall,
            color = EveColors.SecondaryText,
        )
    }
}

@Composable
private fun ChatMessageBubble(message: EmbeddedAiMessage, waitingForConfirmation: Boolean) {
    val user = message.role == EmbeddedAiMessageRole.USER
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.76f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .background(
                        if (user) EveColors.PrimaryAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("$AI_CHAT_MESSAGE_TEST_TAG_PREFIX-${message.id}"),
            ) {
                Text(
                    if (user) "You" else "AI",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (user) EveColors.PrimaryAccent else EveColors.SecondaryText,
                )
                if (user) {
                    Text(message.content)
                } else {
                    val display = when {
                        message.status != EmbeddedAiMessageStatus.THINKING -> message.content
                        waitingForConfirmation -> "Waiting for confirmation…"
                        else -> "Thinking…"
                    }
                    AssistantMarkdown(display)
                }
            }
        }
    }
}

internal enum class ChatEnterAction { SEND, NEW_LINE, IME_COMPOSITION, IGNORE }

internal fun chatEnterAction(
    value: TextFieldValue,
    shiftPressed: Boolean,
    inputEnabled: Boolean,
): ChatEnterAction = when {
    value.composition != null -> ChatEnterAction.IME_COMPOSITION
    shiftPressed -> ChatEnterAction.NEW_LINE
    !inputEnabled || value.text.isBlank() -> ChatEnterAction.IGNORE
    else -> ChatEnterAction.SEND
}

@Composable
private fun AiActionConfirmationDialog(
    confirmation: AiActionConfirmation,
    onCancel: () -> Unit,
    onAllow: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(AI_CONFIRMATION_DIALOG_TEST_TAG),
        onDismissRequest = onCancel,
        title = { Text("Confirm AI action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Action: ${confirmation.action}")
                confirmation.details.forEach { detail -> Text("${detail.label}: ${detail.value}") }
                if (confirmation.details.none { it.label == "Character" }) Text("Target: ${confirmation.target}")
                Text("Risk: ${confirmation.risk.userFacingLabel()}")
                Text("Effect: ${confirmation.effect}", color = EveColors.SecondaryText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(AI_CONFIRMATION_CANCEL_TEST_TAG),
            ) { Text("Cancel") }
        },
        confirmButton = { Button(onClick = onAllow) { Text("Allow") } },
    )
}

private fun PlannerToolRisk.userFacingLabel(): String = when (this) {
    PlannerToolRisk.READ_ONLY -> "Read only"
    PlannerToolRisk.TEMPORARY_UI -> "Temporary map change"
    PlannerToolRisk.PERSISTENT_WRITE -> "Permanent write"
    PlannerToolRisk.DESTRUCTIVE_WRITE -> "Destructive change"
    PlannerToolRisk.EXTERNAL_ACTION -> "External action"
}

data class AiAssistantProviderStatus(
    val providerType: AiProviderType?,
    val modelId: String?,
    val credentialSource: AiCredentialSource?,
) {
    val ready: Boolean get() = providerType != null && credentialSource != null
    val description: String
        get() = when {
            providerType == null -> "AI provider is not configured."
            credentialSource == null -> "Provider: ${providerType.displayName} · AI API Key is not configured."
            else -> "${providerType.displayName} · $modelId · ${credentialSource.displayName}"
        }
    val actionMessage: String
        get() = if (providerType == null) "Configure an AI provider to begin." else "Configure an AI API Key to begin."
}

internal const val AI_ASSISTANT_ROOT_TEST_TAG = "embedded-ai-assistant-root"
internal const val AI_CHAT_LIST_TEST_TAG = "embedded-ai-chat-list"
internal const val AI_CHAT_INPUT_TEST_TAG = "embedded-ai-chat-input"
internal const val AI_CHAT_MESSAGE_TEST_TAG_PREFIX = "embedded-ai-chat-message"
internal const val AI_SESSION_SIDEBAR_TEST_TAG = "embedded-ai-session-sidebar"
internal const val AI_SESSION_ITEM_TEST_TAG_PREFIX = "embedded-ai-session-item"
internal const val AI_HIDE_SIDEBAR_TEST_TAG = "embedded-ai-hide-sidebar"
internal const val AI_SHOW_SIDEBAR_TEST_TAG = "embedded-ai-show-sidebar"
internal const val AI_CONFIRMATION_DIALOG_TEST_TAG = "embedded-ai-confirmation-dialog"
internal const val AI_CONFIRMATION_CANCEL_TEST_TAG = "embedded-ai-confirmation-cancel"
