package dev.evestaticmapplanner.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.evestaticmapplanner.embeddedai.EmbeddedAiChatSession
import dev.evestaticmapplanner.embeddedai.MAX_CUSTOM_CHAT_TITLE_CODE_POINTS
import dev.evestaticmapplanner.embeddedai.PlannerToolRisk
import dev.evestaticmapplanner.embeddedai.isEstablished
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface

@Composable
internal fun EmbeddedAiAssistantWindow(
    controller: EmbeddedAiController,
    voiceController: VoiceController,
    voiceInputEnabled: Boolean,
    providerStatus: AiAssistantProviderStatus,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val confirmation by controller.confirmation.collectAsState()
    val voiceState by voiceController.state.collectAsState()
    val readableAssistantMessage = state.chatSession.messages.lastOrNull {
        it.role == EmbeddedAiMessageRole.ASSISTANT && it.status != EmbeddedAiMessageStatus.ERROR
    }
    LaunchedEffect(
        readableAssistantMessage?.id,
        readableAssistantMessage?.status,
        readableAssistantMessage?.content,
    ) {
        readableAssistantMessage?.let {
            voiceController.assistantMessageUpdated(
                messageId = it.id,
                accumulatedMarkdown = it.content,
                complete = it.status == EmbeddedAiMessageStatus.COMPLETE,
            )
        }
    }

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
                voiceState = voiceState,
                voiceInputEnabled = voiceInputEnabled,
                onSend = controller::send,
                onCancel = controller::cancel,
                onNewChat = {
                    voiceController.onNewChat()
                    controller.newChat()
                },
                onSelectChat = controller::selectChat,
                onRenameChat = controller::renameChat,
                onOpenSettings = onOpenSettings,
                onApprove = controller::approveAction,
                onDeny = controller::denyAction,
                onMicrophone = voiceController::microphonePressed,
                onCancelVoice = voiceController::cancelVoiceActivity,
                onSpeak = voiceController::speak,
                onStopSpeaking = voiceController::stopPlayback,
            )
        }
    }
}

@Composable
internal fun EmbeddedAiAssistantContent(
    state: EmbeddedAiUiState,
    confirmation: AiActionConfirmation?,
    providerStatus: AiAssistantProviderStatus,
    voiceState: VoiceUiState = VoiceUiState(),
    voiceInputEnabled: Boolean = true,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit = {},
    onRenameChat: (String, String) -> Boolean = { _, _ -> false },
    onOpenSettings: () -> Unit,
    onApprove: (String) -> Boolean,
    onDeny: (String) -> Boolean,
    onMicrophone: (((String, Boolean) -> Unit) -> Unit) = {},
    onCancelVoice: () -> Unit = {},
    onSpeak: (String, String) -> Unit = { _, _ -> },
    onStopSpeaking: () -> Unit = {},
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

    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag(AI_ASSISTANT_ROOT_TEST_TAG),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (sidebarVisible) {
                ConversationSidebar(
                    sessions = state.sessions,
                    activeSessionId = state.chatSession.id,
                    onNewChat = onNewChat,
                    onSelectChat = onSelectChat,
                    onRenameChat = onRenameChat,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.testTag(AI_ASSISTANT_HEADER_TEST_TAG),
                ) {
                    Text(
                        "Embedded AI Assistant",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(providerStatus.description, color = EveColors.SecondaryText)
                }
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
                                    voiceState = voiceState,
                                    onSpeak = onSpeak,
                                    onStopSpeaking = onStopSpeaking,
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
                ChatComposer(
                    prompt = prompt,
                    onPromptChange = { prompt = it },
                    inputEnabled = inputEnabled,
                    agentRunning = state.isLoading,
                    voiceInputEnabled = voiceInputEnabled,
                    voiceState = voiceState,
                    focusRequester = inputFocusRequester,
                    onSubmit = ::submitPrompt,
                    onStopAgent = onCancel,
                    onMicrophone = {
                        onMicrophone { transcript, autoSend ->
                            if (autoSend && inputEnabled) {
                                prompt = TextFieldValue()
                                onSend(transcript)
                            } else {
                                prompt = TextFieldValue(transcript)
                                inputFocusRequester.requestFocus()
                            }
                        }
                    },
                    onCancelVoice = onCancelVoice,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(AI_CHAT_BOTTOM_ACTION_ROW_TEST_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatSidebarChevronButton(
                        expanded = sidebarVisible,
                        onClick = { sidebarVisible = !sidebarVisible },
                    )
                    Box(Modifier.weight(1f))
                }
                voiceState.message?.let {
                    Text(
                        it,
                        color = if (voiceState.errorCode == null) EveColors.SecondaryText else MaterialTheme.colorScheme.error,
                    )
                }
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
private fun ChatComposer(
    prompt: TextFieldValue,
    onPromptChange: (TextFieldValue) -> Unit,
    inputEnabled: Boolean,
    agentRunning: Boolean,
    voiceInputEnabled: Boolean,
    voiceState: VoiceUiState,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onStopAgent: () -> Unit,
    onMicrophone: () -> Unit,
    onCancelVoice: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val controlsWidth = if (voiceInputEnabled) 88.dp else 40.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .background(colors.surfaceContainerLowest, shape)
            .border(1.dp, if (focused) colors.primary else colors.outline, shape)
            .testTag(AI_CHAT_COMPOSER_TEST_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    top = 12.dp,
                    end = controlsWidth + 16.dp,
                    bottom = 56.dp,
                )
                .testTag(AI_CHAT_EDITABLE_REGION_TEST_TAG),
        ) {
            BasicTextField(
                value = prompt,
                onValueChange = onPromptChange,
                enabled = inputEnabled,
                minLines = 2,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (inputEnabled) colors.onSurface else colors.onSurface.copy(alpha = 0.45f),
                ),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (prompt.text.isEmpty()) {
                            Text(
                                "Message…",
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == Key.Escape && voiceState.activity != VoiceActivity.IDLE) {
                            onCancelVoice()
                            return@onPreviewKeyEvent true
                        }
                        if (event.key != Key.Enter) return@onPreviewKeyEvent false
                        when (chatEnterAction(prompt, event.isShiftPressed, inputEnabled)) {
                            ChatEnterAction.SEND -> {
                                onSubmit()
                                true
                            }
                            ChatEnterAction.NEW_LINE, ChatEnterAction.IME_COMPOSITION -> false
                            ChatEnterAction.IGNORE -> true
                        }
                    }
                    .testTag(AI_CHAT_INPUT_TEST_TAG),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 8.dp)
                .testTag(AI_CHAT_COMPOSER_CONTROLS_TEST_TAG),
        ) {
            if (voiceInputEnabled) {
                val microphoneEnabled = !agentRunning && voiceState.activity in setOf(
                    VoiceActivity.IDLE,
                    VoiceActivity.RECORDING,
                    VoiceActivity.TRANSCRIBING,
                )
                val microphoneDescription = when (voiceState.activity) {
                    VoiceActivity.RECORDING -> "Stop recording and transcribe"
                    VoiceActivity.TRANSCRIBING -> "Cancel transcription"
                    else -> "Start voice input"
                }
                ComposerActionButton(
                    contentDescription = microphoneDescription,
                    stateDescription = voiceState.activity.name.lowercase(),
                    enabled = microphoneEnabled,
                    active = voiceState.recording,
                    modifier = Modifier.testTag(AI_MICROPHONE_BUTTON_TEST_TAG),
                    onClick = {
                        if (voiceState.transcribing) onCancelVoice() else onMicrophone()
                    },
                ) {
                    if (voiceState.transcribing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.onSurface,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        MicrophoneGlyph(active = voiceState.recording)
                    }
                }
            }
            val actionEnabled = agentRunning || prompt.text.isNotBlank() && inputEnabled
            ComposerActionButton(
                contentDescription = if (agentRunning) "Stop generating" else "Send message",
                stateDescription = if (agentRunning) "stop" else "send",
                enabled = actionEnabled,
                filled = actionEnabled,
                modifier = Modifier.testTag(AI_SEND_STOP_BUTTON_TEST_TAG),
                onClick = if (agentRunning) onStopAgent else onSubmit,
            ) {
                if (agentRunning) StopGlyph() else SendGlyph(enabled = actionEnabled)
            }
        }
    }
}

@Composable
private fun ComposerActionButton(
    contentDescription: String,
    stateDescription: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    filled: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container = when {
        active -> colors.errorContainer
        filled -> colors.primary
        else -> colors.surfaceContainerHigh
    }
    val border = when {
        active -> colors.error
        filled -> colors.primary
        else -> colors.outline
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(40.dp)
            .background(container.copy(alpha = if (enabled) 1f else 0.46f), CircleShape)
            .border(1.dp, border.copy(alpha = if (enabled) 1f else 0.46f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            },
    ) {
        content()
    }
}

@Composable
private fun MicrophoneGlyph(active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(21.dp)) {
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.35f, size.height * 0.08f),
            size = Size(size.width * 0.30f, size.height * 0.52f),
            cornerRadius = CornerRadius(size.width * 0.16f),
            style = Stroke(stroke),
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.20f, size.height * 0.30f),
            size = Size(size.width * 0.60f, size.height * 0.42f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(size.width * 0.50f, size.height * 0.72f), Offset(size.width * 0.50f, size.height * 0.90f), stroke)
        drawLine(color, Offset(size.width * 0.34f, size.height * 0.90f), Offset(size.width * 0.66f, size.height * 0.90f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun SendGlyph(enabled: Boolean) {
    val color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(size.width * 0.50f, size.height * 0.82f), Offset(size.width * 0.50f, size.height * 0.20f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.50f, size.height * 0.20f), Offset(size.width * 0.24f, size.height * 0.46f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.50f, size.height * 0.20f), Offset(size.width * 0.76f, size.height * 0.46f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun StopGlyph() {
    val color = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(20.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.30f, size.height * 0.30f),
            size = Size(size.width * 0.40f, size.height * 0.40f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}

@Composable
private fun ConversationSidebar(
    sessions: List<EmbeddedAiChatSession>,
    activeSessionId: String,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onRenameChat: (String, String) -> Boolean,
) {
    var editingSessionId by remember { mutableStateOf<String?>(null) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(CHAT_SIDEBAR_WIDTH)
            .fillMaxSize()
            .background(EveColors.InputSurface, RoundedCornerShape(6.dp))
            .padding(10.dp)
            .testTag(AI_SESSION_SIDEBAR_TEST_TAG),
    ) {
        Text("Chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 26.dp))
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) { Text("+ New Chat") }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(sessions.filter(EmbeddedAiChatSession::isEstablished), key = { it.id }) { session ->
                if (editingSessionId == session.id) {
                    SessionRenameEditor(
                        initialTitle = session.title,
                        onSave = { title ->
                            if (onRenameChat(session.id, title)) editingSessionId = null
                        },
                        onCancel = { editingSessionId = null },
                    )
                } else {
                    ChatSessionItem(
                        session = session,
                        selected = session.id == activeSessionId,
                        onSelect = { onSelectChat(session.id) },
                        onRename = { editingSessionId = session.id },
                    )
                }
            }
        }
        Text(
            "Chats last until the Planner closes.",
            style = MaterialTheme.typography.labelSmall,
            color = EveColors.SecondaryText,
        )
    }
}

@Composable
private fun ChatSessionItem(
    session: EmbeddedAiChatSession,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    TextButton(
        onClick = onSelect,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverInteraction)
            .testTag("$AI_SESSION_ITEM_TEST_TAG_PREFIX-${session.id}"),
    ) {
        Text(session.title, maxLines = 2, modifier = Modifier.weight(1f))
        if (selected || hovered) {
            ChatRenameIconButton(
                onClick = onRename,
                modifier = Modifier.testTag("$AI_RENAME_SESSION_TEST_TAG_PREFIX-${session.id}"),
            )
        }
    }
}

@Composable
private fun ChatRenameIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Rename chat" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val color = EveColors.PrimaryText
            val stroke = 1.8.dp.toPx()
            drawLine(color, Offset(size.width * 0.22f, size.height * 0.78f), Offset(size.width * 0.72f, size.height * 0.28f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * 0.64f, size.height * 0.20f), Offset(size.width * 0.80f, size.height * 0.36f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * 0.18f, size.height * 0.82f), Offset(size.width * 0.36f, size.height * 0.78f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun SessionRenameEditor(
    initialTitle: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var receivedFocus by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    fun save() {
        if (finished || title.isBlank()) return
        finished = true
        onSave(title)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = title,
        onValueChange = { value ->
            if (value.codePointCount(0, value.length) <= MAX_CUSTOM_CHAT_TITLE_CODE_POINTS) title = value
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    receivedFocus = true
                } else if (receivedFocus && !finished) {
                    if (title.isBlank()) {
                        finished = true
                        onCancel()
                    } else {
                        save()
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter -> {
                        save()
                        true
                    }
                    Key.Escape -> {
                        if (!finished) {
                            finished = true
                            onCancel()
                        }
                        true
                    }
                    else -> false
                }
            }
            .testTag(AI_RENAME_INPUT_TEST_TAG),
    )
}

@Composable
private fun ChatSidebarChevronButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(CHAT_SIDEBAR_CHEVRON_SIZE)
            .background(EveColors.SecondarySurface, RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (expanded) "Collapse chat sidebar" else "Expand chat sidebar"
                stateDescription = if (expanded) "Left chevron" else "Right chevron"
            }
            .testTag(if (expanded) AI_HIDE_SIDEBAR_TEST_TAG else AI_SHOW_SIDEBAR_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val color = EveColors.PrimaryText
            val stroke = 1.8.dp.toPx()
            val edge = if (expanded) 0.68f else 0.32f
            val point = if (expanded) 0.34f else 0.66f
            drawLine(color, Offset(size.width * edge, size.height * 0.18f), Offset(size.width * point, size.height * 0.50f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * point, size.height * 0.50f), Offset(size.width * edge, size.height * 0.82f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: EmbeddedAiMessage,
    waitingForConfirmation: Boolean,
    voiceState: VoiceUiState,
    onSpeak: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
) {
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
                    if (message.status == EmbeddedAiMessageStatus.COMPLETE && message.content.isNotBlank()) {
                        val isThisPlaying = voiceState.playingMessageId == message.id &&
                            voiceState.activity in setOf(VoiceActivity.SYNTHESIZING, VoiceActivity.PLAYING)
                        TextButton(
                            onClick = { if (isThisPlaying) onStopSpeaking() else onSpeak(message.id, message.content) },
                            modifier = Modifier.testTag("$AI_SPEAK_MESSAGE_TEST_TAG_PREFIX-${message.id}"),
                        ) { Text(if (isThisPlaying) "Stop" else "Read aloud") }
                    }
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
internal const val AI_ASSISTANT_HEADER_TEST_TAG = "embedded-ai-assistant-header"
internal const val AI_CHAT_LIST_TEST_TAG = "embedded-ai-chat-list"
internal const val AI_CHAT_COMPOSER_TEST_TAG = "embedded-ai-chat-composer"
internal const val AI_CHAT_EDITABLE_REGION_TEST_TAG = "embedded-ai-chat-editable-region"
internal const val AI_CHAT_COMPOSER_CONTROLS_TEST_TAG = "embedded-ai-chat-composer-controls"
internal const val AI_CHAT_INPUT_TEST_TAG = "embedded-ai-chat-input"
internal const val AI_CHAT_BOTTOM_ACTION_ROW_TEST_TAG = "embedded-ai-chat-bottom-action-row"
internal const val AI_CHAT_MESSAGE_TEST_TAG_PREFIX = "embedded-ai-chat-message"
internal const val AI_SESSION_SIDEBAR_TEST_TAG = "embedded-ai-session-sidebar"
internal const val AI_SESSION_ITEM_TEST_TAG_PREFIX = "embedded-ai-session-item"
internal const val AI_HIDE_SIDEBAR_TEST_TAG = "embedded-ai-hide-sidebar"
internal const val AI_SHOW_SIDEBAR_TEST_TAG = "embedded-ai-show-sidebar"
internal const val AI_RENAME_SESSION_TEST_TAG_PREFIX = "embedded-ai-rename-session"
internal const val AI_RENAME_INPUT_TEST_TAG = "embedded-ai-rename-input"
internal const val AI_CONFIRMATION_DIALOG_TEST_TAG = "embedded-ai-confirmation-dialog"
internal const val AI_CONFIRMATION_CANCEL_TEST_TAG = "embedded-ai-confirmation-cancel"
internal const val AI_MICROPHONE_BUTTON_TEST_TAG = "embedded-ai-microphone"
internal const val AI_SEND_STOP_BUTTON_TEST_TAG = "embedded-ai-send-stop"
internal const val AI_SPEAK_MESSAGE_TEST_TAG_PREFIX = "embedded-ai-speak-message"

private val CHAT_SIDEBAR_WIDTH = 210.dp
private val CHAT_SIDEBAR_CHEVRON_SIZE = 24.dp
