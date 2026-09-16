package dev.evestaticmapplanner.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
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
        state = rememberWindowState(width = 680.dp, height = 640.dp),
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
    onOpenSettings: () -> Unit,
    onApprove: (String) -> Boolean,
    onDeny: (String) -> Boolean,
) {
    var prompt by remember { mutableStateOf("") }
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

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag(AI_ASSISTANT_ROOT_TEST_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Embedded AI Assistant", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onNewChat) { Text("New Chat") }
        }
        Text(providerStatus.description, color = EveColors.SecondaryText)
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

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Message") },
            enabled = !state.isLoading && providerStatus.ready,
            modifier = Modifier.fillMaxWidth().testTag(AI_CHAT_INPUT_TEST_TAG),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
            Button(
                onClick = {
                    val submitted = prompt
                    prompt = ""
                    onSend(submitted)
                },
                enabled = prompt.isNotBlank() && !state.isLoading && providerStatus.ready,
            ) { Text("Send") }
            Button(
                onClick = onCancel,
                enabled = state.isLoading,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Cancel") }
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
private fun ChatMessageBubble(message: EmbeddedAiMessage, waitingForConfirmation: Boolean) {
    val user = message.role == EmbeddedAiMessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth(0.78f)
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
internal const val AI_CONFIRMATION_DIALOG_TEST_TAG = "embedded-ai-confirmation-dialog"
internal const val AI_CONFIRMATION_CANCEL_TEST_TAG = "embedded-ai-confirmation-cancel"
