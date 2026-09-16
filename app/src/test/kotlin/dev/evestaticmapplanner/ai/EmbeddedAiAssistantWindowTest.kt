package dev.evestaticmapplanner.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.embeddedai.AiActionConfirmation
import dev.evestaticmapplanner.embeddedai.AiActionDetail
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiProviderType
import dev.evestaticmapplanner.embeddedai.EmbeddedAiChatSession
import dev.evestaticmapplanner.embeddedai.EmbeddedAiMessage
import dev.evestaticmapplanner.embeddedai.EmbeddedAiMessageRole
import dev.evestaticmapplanner.embeddedai.EmbeddedAiUiState
import dev.evestaticmapplanner.embeddedai.PlannerToolRisk
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EmbeddedAiAssistantWindowTest {
    @Test
    fun `user messages align right assistant messages align left and order remains chronological`() = runComposeUiTest {
        val state = chatState(
            message("a1", EmbeddedAiMessageRole.ASSISTANT, "First answer"),
            message("u1", EmbeddedAiMessageRole.USER, "Second message"),
            message("a2", EmbeddedAiMessageRole.ASSISTANT, "Third answer"),
        )
        setAssistantContent(state)

        val root = onNodeWithTag(AI_ASSISTANT_ROOT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val first = onNodeWithTag("$AI_CHAT_MESSAGE_TEST_TAG_PREFIX-a1").fetchSemanticsNode().boundsInRoot
        val user = onNodeWithTag("$AI_CHAT_MESSAGE_TEST_TAG_PREFIX-u1").fetchSemanticsNode().boundsInRoot
        val third = onNodeWithTag("$AI_CHAT_MESSAGE_TEST_TAG_PREFIX-a2").fetchSemanticsNode().boundsInRoot

        assertTrue(first.left < user.left)
        assertTrue(third.left < user.left)
        assertTrue(first.top < user.top && user.top < third.top)
        assertTrue(first.width <= root.width * 0.80f)
        assertTrue(user.width <= root.width * 0.80f)
    }

    @Test
    fun `assistant renders safe Markdown while user text remains literal`() = runComposeUiTest {
        val markdown = "**Bold route** and `routeId`\n\n- First\n- *Second*\n\n1. One\n2. Two"
        setAssistantContent(
            chatState(
                message("u1", EmbeddedAiMessageRole.USER, "**literal user text**"),
                message("a1", EmbeddedAiMessageRole.ASSISTANT, markdown),
            ),
        )

        onAllNodesWithText("**literal user text**").assertCountEquals(1)
        onNodeWithText("Bold route and routeId").assertIsDisplayed()
        onAllNodesWithText("**Bold route** and `routeId`").assertCountEquals(0)
        onAllNodesWithText("•").assertCountEquals(2)
        onNodeWithText("First").assertIsDisplayed()
        onNodeWithText("Second").assertIsDisplayed()
        onNodeWithText("1.").assertIsDisplayed()
        onNodeWithText("Two").assertIsDisplayed()

        val annotated = inlineMarkdown("**Bold** and `code`")
        assertEquals("Bold and code", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(annotated.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `new messages automatically scroll to the latest item`() = runComposeUiTest {
        var state by mutableStateOf(
            chatState(*(1..30).map { message("m$it", EmbeddedAiMessageRole.ASSISTANT, "Message $it") }.toTypedArray()),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(680.dp, 520.dp)) {
                    TestContent(state = state)
                }
            }
        }
        state = state.copy(
            chatSession = state.chatSession.copy(
                messages = state.chatSession.messages + message("latest", EmbeddedAiMessageRole.ASSISTANT, "Latest answer"),
            ),
            scrollRequest = state.scrollRequest + 1,
        )
        waitForIdle()

        onNodeWithTag("$AI_CHAT_MESSAGE_TEST_TAG_PREFIX-latest").assertIsDisplayed()
    }

    @Test
    fun `New Chat clears only conversation while provider status remains`() = runComposeUiTest {
        var state by mutableStateOf(chatState(message("a1", EmbeddedAiMessageRole.ASSISTANT, "Old answer")))
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(680.dp, 520.dp)) {
                    TestContent(
                        state = state,
                        onNewChat = { state = EmbeddedAiUiState() },
                    )
                }
            }
        }

        onNodeWithText("+ New Chat").performClick()
        waitForIdle()

        onAllNodesWithText("Old answer").assertCountEquals(0)
        onNodeWithText("DeepSeek · deepseek-flash · Secure storage").assertIsDisplayed()
        onNodeWithText("Start a conversation with the Planner.").assertIsDisplayed()
    }

    @Test
    fun `conversation sidebar switches sessions and can hide then show without deleting history`() = runComposeUiTest {
        val first = EmbeddedAiChatSession(
            "session-1",
            Instant.EPOCH,
            listOf(message("u1", EmbeddedAiMessageRole.USER, "First chat")),
            title = "First chat",
        )
        val second = EmbeddedAiChatSession(
            "session-2",
            Instant.EPOCH.plusSeconds(1),
            listOf(message("u2", EmbeddedAiMessageRole.USER, "Second chat")),
            title = "Second chat",
        )
        var selected: String? = null
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(880.dp, 620.dp)) {
                    EmbeddedAiAssistantContent(
                        state = EmbeddedAiUiState(chatSession = first, sessions = listOf(second, first)),
                        confirmation = null,
                        providerStatus = READY_PROVIDER,
                        onSend = {},
                        onCancel = {},
                        onNewChat = {},
                        onSelectChat = { selected = it },
                        onOpenSettings = {},
                        onApprove = { true },
                        onDeny = { true },
                    )
                }
            }
        }

        onNodeWithTag(AI_SESSION_SIDEBAR_TEST_TAG).assertIsDisplayed()
        onAllNodesWithText("Hide Sidebar").assertCountEquals(0)
        onAllNodesWithText("Show Sidebar").assertCountEquals(0)
        val sidebarBounds = onNodeWithTag(AI_SESSION_SIDEBAR_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val expandedHandleBounds = onNodeWithTag(AI_HIDE_SIDEBAR_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val expandedTitleBounds = onNodeWithText("Embedded AI Assistant").fetchSemanticsNode().boundsInRoot
        val expandedStatusBounds = onNodeWithText(READY_PROVIDER.description).fetchSemanticsNode().boundsInRoot
        assertTrue(
            expandedHandleBounds.center.x in sidebarBounds.right..(sidebarBounds.right + 12f),
            "Expanded handle should remain centered on the sidebar's padded outer edge.",
        )
        assertTrue(
            expandedHandleBounds.right < expandedTitleBounds.left,
            "Expanded handle must not overlap the Assistant title.",
        )
        assertTrue(
            expandedHandleBounds.right < expandedStatusBounds.left,
            "Expanded handle must not overlap provider status.",
        )
        onNodeWithText("Second chat").performClick()
        assertEquals("session-2", selected)
        onNodeWithTag(AI_HIDE_SIDEBAR_TEST_TAG).performClick()
        waitForIdle()
        onNodeWithTag(AI_SESSION_SIDEBAR_TEST_TAG).assertDoesNotExist()
        onNodeWithText("Embedded AI Assistant").assertIsDisplayed()
        val collapsedHandle = onNodeWithTag(AI_SHOW_SIDEBAR_TEST_TAG).assertIsDisplayed()
        val collapsedHandleBounds = collapsedHandle.fetchSemanticsNode().boundsInRoot
        val collapsedTitleBounds = onNodeWithText("Embedded AI Assistant").fetchSemanticsNode().boundsInRoot
        val collapsedStatusBounds = onNodeWithText(READY_PROVIDER.description).fetchSemanticsNode().boundsInRoot
        assertTrue(
            collapsedHandleBounds.right < collapsedTitleBounds.left,
            "Collapsed handle must not overlap the Assistant title.",
        )
        assertTrue(
            collapsedHandleBounds.right < collapsedStatusBounds.left,
            "Collapsed handle must not overlap provider status.",
        )
        assertTrue(
            abs(collapsedHandleBounds.top - expandedHandleBounds.top) <= 1.1f,
            "Chevron height should stay fixed when the sidebar changes state.",
        )
        assertTrue(abs(collapsedHandleBounds.width - expandedHandleBounds.width) <= 1.1f)
        collapsedHandle.performClick()
        onNodeWithTag("$AI_SESSION_ITEM_TEST_TAG_PREFIX-session-1").assertIsDisplayed()
        onNodeWithTag("$AI_SESSION_ITEM_TEST_TAG_PREFIX-session-2").assertIsDisplayed()
    }

    @Test
    fun `empty chat is hidden and only one New Chat control is shown`() = runComposeUiTest {
        val empty = EmbeddedAiChatSession.create(Instant.EPOCH.plusSeconds(2))
        val established = EmbeddedAiChatSession(
            "session-established",
            Instant.EPOCH,
            listOf(message("user-established", EmbeddedAiMessageRole.USER, "Jita route")),
            title = "Jita route",
        )
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(880.dp, 620.dp)) {
                    TestContent(
                        state = EmbeddedAiUiState(
                            chatSession = empty,
                            sessions = listOf(empty, established),
                        ),
                    )
                }
            }
        }

        onAllNodesWithText("+ New Chat").assertCountEquals(1)
        onAllNodesWithText("New Chat").assertCountEquals(0)
        onNodeWithTag("$AI_SESSION_ITEM_TEST_TAG_PREFIX-${empty.id}").assertDoesNotExist()
        onNodeWithTag("$AI_SESSION_ITEM_TEST_TAG_PREFIX-${established.id}").assertIsDisplayed()
    }

    @Test
    fun `chat rename saves with Enter cancels with Escape and survives Assistant content reopen`() = runComposeUiTest {
        val originalMessages = listOf(message("rename-user", EmbeddedAiMessageRole.USER, "把1dq1-a标注在地图上"))
        val initial = EmbeddedAiChatSession(
            "rename-session",
            Instant.EPOCH,
            originalMessages,
            title = "把1dq1-a标注在地图上",
        )
        var state by mutableStateOf(EmbeddedAiUiState(chatSession = initial, sessions = listOf(initial)))
        var visible by mutableStateOf(true)
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(880.dp, 620.dp)) {
                    if (visible) {
                        TestContent(
                            state = state,
                            onRenameChat = { id, title ->
                                val normalized = title.trim()
                                if (normalized.isBlank()) {
                                    false
                                } else {
                                    val renamed = state.sessions.single { it.id == id }.copy(
                                        title = normalized,
                                        isTitleCustomized = true,
                                    )
                                    state = state.copy(chatSession = renamed, sessions = listOf(renamed))
                                    true
                                }
                            },
                        )
                    }
                }
            }
        }

        onNodeWithTag("$AI_RENAME_SESSION_TEST_TAG_PREFIX-${initial.id}").performClick()
        onNodeWithTag(AI_RENAME_INPUT_TEST_TAG).performTextReplacement("  1DQ Route Planning  ")
        onNodeWithTag(AI_RENAME_INPUT_TEST_TAG).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("1DQ Route Planning").assertIsDisplayed()
        assertEquals(originalMessages, state.chatSession.messages)

        onNodeWithTag("$AI_RENAME_SESSION_TEST_TAG_PREFIX-${initial.id}").performClick()
        onNodeWithTag(AI_RENAME_INPUT_TEST_TAG).performTextReplacement("Discarded title")
        onNodeWithTag(AI_RENAME_INPUT_TEST_TAG).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText("1DQ Route Planning").assertIsDisplayed()

        visible = false
        waitForIdle()
        visible = true
        waitForIdle()
        onNodeWithText("1DQ Route Planning").assertIsDisplayed()
    }

    @Test
    fun `chat Enter decision sends plain Enter preserves Shift newline and protects IME composition`() {
        assertEquals(ChatEnterAction.SEND, chatEnterAction(TextFieldValue("send me"), false, true))
        assertEquals(ChatEnterAction.NEW_LINE, chatEnterAction(TextFieldValue("line one"), true, true))
        assertEquals(
            ChatEnterAction.IME_COMPOSITION,
            chatEnterAction(TextFieldValue("中文", composition = TextRange(0, 2)), false, true),
        )
        assertEquals(ChatEnterAction.IGNORE, chatEnterAction(TextFieldValue("   "), false, true))
        assertEquals(ChatEnterAction.IGNORE, chatEnterAction(TextFieldValue("send me"), false, false))
    }

    @Test
    fun `short bubbles wrap content while long bubbles stop at the maximum width`() = runComposeUiTest {
        setAssistantContent(
            chatState(
                message("short", EmbeddedAiMessageRole.USER, "显示路线"),
                message("long", EmbeddedAiMessageRole.USER, "long message ".repeat(20)),
                message("assistant-short", EmbeddedAiMessageRole.ASSISTANT, "Done"),
            ),
        )

        val root = onNodeWithTag(AI_CHAT_LIST_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val short = onNodeWithTag("$AI_CHAT_MESSAGE_TEST_TAG_PREFIX-short").fetchSemanticsNode().boundsInRoot
        val long = onNodeWithTag("$AI_CHAT_MESSAGE_TEST_TAG_PREFIX-long").fetchSemanticsNode().boundsInRoot
        val assistant = onNodeWithTag("$AI_CHAT_MESSAGE_TEST_TAG_PREFIX-assistant-short").fetchSemanticsNode().boundsInRoot

        assertTrue(short.width < long.width)
        assertTrue(assistant.width < long.width)
        assertTrue(long.width <= root.width * 0.78f)
    }

    @Test
    fun `Cancel keeps existing conversation visible`() = runComposeUiTest {
        var cancelled = false
        val state = chatState(
            message("u1", EmbeddedAiMessageRole.USER, "Keep this question"),
            message("a1", EmbeddedAiMessageRole.ASSISTANT, ""),
        ).copy(isLoading = true)
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(680.dp, 520.dp)) {
                    EmbeddedAiAssistantContent(
                        state = state,
                        confirmation = null,
                        providerStatus = READY_PROVIDER,
                        onSend = {},
                        onCancel = { cancelled = true },
                        onNewChat = {},
                        onOpenSettings = {},
                        onApprove = { true },
                        onDeny = { true },
                    )
                }
            }
        }

        onNodeWithText("Cancel").performClick()

        assertTrue(cancelled)
        onAllNodesWithText("Keep this question").assertCountEquals(1)
    }

    @Test
    fun `pending EVE action renders friendly confirmation and Cancel denies it`() = runComposeUiTest {
        var denied: String? = null
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(680.dp, 520.dp)) {
                    TestContent(
                        state = chatState(message("a1", EmbeddedAiMessageRole.ASSISTANT, "")),
                        confirmation = navigationConfirmation(),
                        onDeny = { denied = it; true },
                    )
                }
            }
        }

        onNodeWithTag(AI_CONFIRMATION_DIALOG_TEST_TAG).assertIsDisplayed()
        onNodeWithText("Action: Send navigation to EVE").assertIsDisplayed()
        onNodeWithText("Character: Oijghr").assertIsDisplayed()
        onNodeWithText("Mission: Jita to Amarr").assertIsDisplayed()
        onNodeWithText("Route: Jita → Amarr").assertIsDisplayed()
        onNodeWithText("Target count: 1").assertIsDisplayed()
        onNodeWithText("Effect: This will send navigation data to EVE Online.").assertIsDisplayed()
        onNodeWithTag(AI_CONFIRMATION_CANCEL_TEST_TAG).performClick()

        assertEquals("action-1", denied)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setAssistantContent(state: EmbeddedAiUiState) {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(680.dp, 640.dp)) { TestContent(state) }
            }
        }
    }
}

@Composable
private fun TestContent(
    state: EmbeddedAiUiState,
    confirmation: AiActionConfirmation? = null,
    onNewChat: () -> Unit = {},
    onRenameChat: (String, String) -> Boolean = { _, _ -> false },
    onDeny: (String) -> Boolean = { true },
) {
    EmbeddedAiAssistantContent(
        state = state,
        confirmation = confirmation,
        providerStatus = READY_PROVIDER,
        onSend = {},
        onCancel = {},
        onNewChat = onNewChat,
        onRenameChat = onRenameChat,
        onOpenSettings = {},
        onApprove = { true },
        onDeny = onDeny,
    )
}

private fun chatState(vararg messages: EmbeddedAiMessage) = EmbeddedAiUiState(
    chatSession = EmbeddedAiChatSession("session-1", Instant.EPOCH, messages.toList()),
    scrollRequest = 1,
)

private fun message(id: String, role: EmbeddedAiMessageRole, content: String) =
    EmbeddedAiMessage(id, role, content)

private fun navigationConfirmation() = AiActionConfirmation(
    actionId = "action-1",
    toolName = "send_mission_navigation_to_eve",
    risk = PlannerToolRisk.EXTERNAL_ACTION,
    action = "Send navigation to EVE",
    target = "Oijghr",
    details = listOf(
        AiActionDetail("Character", "Oijghr"),
        AiActionDetail("Mission", "Jita to Amarr"),
        AiActionDetail("Route", "Jita → Amarr"),
        AiActionDetail("Target count", "1"),
    ),
    effect = "This will send navigation data to EVE Online.",
)

private val READY_PROVIDER = AiAssistantProviderStatus(
    providerType = AiProviderType.DEEPSEEK,
    modelId = "deepseek-flash",
    credentialSource = AiCredentialSource.SECURE_STORAGE,
)
