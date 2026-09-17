package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.ai.AiProviderSettingsUiState
import dev.evestaticmapplanner.control.AiControlStatus
import dev.evestaticmapplanner.embeddedai.AiConnectionCheck
import dev.evestaticmapplanner.embeddedai.AiConnectionCheckStatus
import dev.evestaticmapplanner.embeddedai.AiConnectionTestResult
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.ui.EveTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeatureSettingsWindowsTest {
    @Test
    fun `each feature settings window has one open instance and repeat show requests focus`() {
        val initial = FeatureSettingsWindowState()
        val opened = initial.show()
        val shownAgain = opened.show()

        assertFalse(initial.isOpen)
        assertTrue(opened.isOpen)
        assertTrue(shownAgain.isOpen)
        assertEquals(opened.focusRequest + 1, shownAgain.focusRequest)
        val closed = shownAgain.close()
        val reopened = closed.show()
        assertFalse(closed.isOpen)
        assertTrue(reopened.isOpen)
        assertEquals(shownAgain.focusRequest + 1, reopened.focusRequest)
    }

    @Test
    fun `general Preferences contains only global categories`() {
        assertEquals(
            listOf("Map Display", "AI Features", "Feature Packs", "Overlays", "Web Pack", "Shared Map"),
            PreferencesCategory.entries.map { it.label },
        )
    }

    @Test
    fun `AI Features accordion keeps at most one section open`() {
        var state = AiFeaturesExpansionState()
        state = state.toggle(AiFeaturesSection.EMBEDDED_ASSISTANT)
        assertEquals(AiFeaturesSection.EMBEDDED_ASSISTANT, state.expanded)
        state = state.toggle(AiFeaturesSection.MCP_INTEGRATION)
        assertEquals(AiFeaturesSection.MCP_INTEGRATION, state.expanded)
        state = state.toggle(AiFeaturesSection.MCP_INTEGRATION)
        assertEquals(null, state.expanded)
    }

    @Test
    fun `AI Features presents Embedded Assistant then MCP Integration as exclusive accordions`() = runComposeUiTest {
        setContent {
            EveTheme {
                Column {
                    AiFeaturesPreferencesContent(
                        savedConfig = null,
                        savedProviderConfigs = emptyMap(),
                        state = AiProviderSettingsUiState(),
                        onProviderViewed = {},
                        onTest = { _, secret -> secret?.close() },
                        onSave = { _, secret -> secret?.close() },
                        onDeleteCredential = {},
                        aiControlPreferences = AiControlPreferences.Defaults,
                        aiControlStatus = AiControlStatus.Disabled,
                        aiControlError = null,
                        onAiControlChange = {},
                        onAiSavedMarkerAccessChange = {},
                        onResetAiControl = {},
                    )
                }
            }
        }

        onNodeWithText("▸ Embedded Assistant").assertIsDisplayed().performClick()
        onNodeWithText("AI Provider").assertIsDisplayed()
        onNodeWithText("Web Search").assertIsDisplayed()
        onNodeWithText("▸ MCP Integration").assertIsDisplayed().performClick()
        onNodeWithText("AI Provider").assertDoesNotExist()
        onNodeWithText("MCP Server & Permissions").assertIsDisplayed()
    }

    @Test
    fun `Marker settings content is feature-only and applies changes immediately`() = runComposeUiTest {
        val changes = mutableListOf<MarkerPreferences>()
        setContent {
            EveTheme {
                Column {
                    MarkerPreferencesContent(
                        preferences = MarkerPreferences.Defaults,
                        onChange = changes::add,
                        onReset = {},
                    )
                }
            }
        }

        onNodeWithText("Marker Settings").assertIsDisplayed()
        onNodeWithText("Saved Marker Appearance").assertIsDisplayed()
        onNodeWithText("Map Display").assertDoesNotExist()
        onNodeWithText("Mini-map Settings").assertDoesNotExist()
        onNodeWithText("AI Control").assertDoesNotExist()

        onAllNodes(isToggleable())[0].performClick()
        assertFalse(changes.last().showMarkers)
    }

    @Test
    fun `AI settings keeps an unsaved masked key available from test through save`() = runComposeUiTest {
        val observed = mutableListOf<String>()
        setContent {
            EveTheme {
                Column {
                    AiProviderPreferencesContent(
                        savedConfig = null,
                        state = AiProviderSettingsUiState(
                            credentialSource = AiCredentialSource.SECURE_STORAGE,
                            testResult = AiConnectionTestResult(
                                connection = AiConnectionCheck(AiConnectionCheckStatus.PASSED, "API connection successful"),
                                model = AiConnectionCheck(AiConnectionCheckStatus.PASSED, "Model: fixture-model"),
                                toolCalling = AiConnectionCheck(AiConnectionCheckStatus.PASSED, "Tool Calling supported"),
                            ),
                        ),
                        onProviderViewed = {},
                        onTest = { _, secret -> observed += secret.readAndClose() },
                        onSave = { _, secret -> observed += secret.readAndClose() },
                        onDeleteCredential = {},
                    )
                }
            }
        }

        onNodeWithText("AI Provider").assertIsDisplayed()
        onNodeWithText("Uses the official OpenRouter API endpoint.").assertIsDisplayed()
        onNodeWithText("API Key: Saved securely").assertIsDisplayed()
        onNodeWithText("✓ API connection successful").assertIsDisplayed()
        onNodeWithText("✓ Model: fixture-model").assertIsDisplayed()
        onNodeWithText("✓ Tool Calling supported").assertIsDisplayed()
        onAllNodes(hasSetTextAction())[1].performTextInput("SECRET_SHOULD_NEVER_APPEAR_12345")
        onNodeWithText("Test Connection").performClick()
        onNodeWithText("Save").performClick()

        assertEquals(
            listOf("SECRET_SHOULD_NEVER_APPEAR_12345", "SECRET_SHOULD_NEVER_APPEAR_12345"),
            observed,
        )
    }
}

private fun SecretValue?.readAndClose(): String {
    val secret = checkNotNull(this)
    return secret.use { value -> value.useString { it } }
}
