package dev.evestaticmapplanner.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
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

        onNodeWithText("**literal user text**").assertIsDisplayed()
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

        onNodeWithText("New Chat").performClick()
        waitForIdle()

        onAllNodesWithText("Old answer").assertCountEquals(0)
        onNodeWithText("DeepSeek · deepseek-flash · Secure storage").assertIsDisplayed()
        onNodeWithText("Start a conversation with the Planner.").assertIsDisplayed()
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
        onNodeWithText("Keep this question").assertIsDisplayed()
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
    onDeny: (String) -> Boolean = { true },
) {
    EmbeddedAiAssistantContent(
        state = state,
        confirmation = confirmation,
        providerStatus = READY_PROVIDER,
        onSend = {},
        onCancel = {},
        onNewChat = onNewChat,
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
