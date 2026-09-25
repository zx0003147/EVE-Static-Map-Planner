package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.ai.AiProviderSettingsUiState
import dev.evestaticmapplanner.ai.SpeechPackState
import dev.evestaticmapplanner.ai.VoiceSettingsUiState
import dev.evestaticmapplanner.control.AiControlStatus
import dev.evestaticmapplanner.embeddedai.AiConnectionCheck
import dev.evestaticmapplanner.embeddedai.AiConnectionCheckStatus
import dev.evestaticmapplanner.embeddedai.AiConnectionTestResult
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechProfile
import dev.evestaticmapplanner.embeddedai.SpeechProviderProfiles
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.ui.EveTheme
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import dev.evestaticmapplanner.localization.AppLocalization
import dev.evestaticmapplanner.localization.ProvideAppLocalization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeatureSettingsWindowsTest {
    @Test
    fun `Provider Preferences renders Chinese display text while preserving provider values`() = runComposeUiTest {
        val saved = dev.evestaticmapplanner.embeddedai.AiProviderConfig.normalized(
            providerType = dev.evestaticmapplanner.embeddedai.AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.example.com/v1",
            modelId = "deepseek/deepseek-v4-flash-0731",
        )
        var submitted: dev.evestaticmapplanner.embeddedai.AiProviderConfig? = null
        setContent {
            ProvideAppLocalization(AppLocalization(AppLocale.ZH_CN)) {
                EveTheme {
                    Column {
                        AiProviderPreferencesContent(
                            savedConfig = saved,
                            state = AiProviderSettingsUiState(credentialSource = AiCredentialSource.SECURE_STORAGE),
                            onProviderViewed = {},
                            onTest = { _, secret -> secret?.close() },
                            onSave = { config, secret -> submitted = config; secret?.close() },
                            onDeleteCredential = {},
                        )
                    }
                }
            }
        }

        onNodeWithText("AI 提供商").assertIsDisplayed()
        onNodeWithText("Base URL").assertIsDisplayed()
        onNodeWithText("模型").assertIsDisplayed()
        onNodeWithText("API Key：已安全保存").assertIsDisplayed()
        onNodeWithText("测试连接").assertIsDisplayed()
        onNodeWithText("保存").performClick()
        runOnIdle { assertEquals(saved, submitted) }
    }

    @Test
    fun `Voice Preferences renders Chinese labels and preserves Alibaba technical values`() = runComposeUiTest {
        val config = VoiceConfig(
            inputProvider = VoiceInputProvider.ALIBABA,
            outputProvider = VoiceOutputProvider.ALIBABA,
            profiles = SpeechProviderProfiles(
                alibaba = AlibabaSpeechProfile(
                    sttModel = "qwen-audio-3.0-asr-flash",
                    ttsModel = "qwen-audio-3.0-tts-flash",
                    voice = "longanfengyue",
                    workspaceId = "llm-fixture-workspace",
                ),
            ),
        )
        setContent {
            ProvideAppLocalization(AppLocalization(AppLocale.ZH_CN)) {
                EveTheme {
                    Column {
                        VoicePreferencesContent(
                            savedConfig = config,
                            state = VoiceSettingsUiState(
                                openAiCredentialSource = AiCredentialSource.SECURE_STORAGE,
                                alibabaCredentialSource = AiCredentialSource.SECURE_STORAGE,
                            ),
                            onViewed = {},
                            onSave = { _, openAi, alibaba -> openAi?.close(); alibaba?.close() },
                            onDeleteCredential = {},
                            onTestRecognition = {},
                            onTestVoice = {},
                            onInstallSpeechPack = {},
                            onRemoveSpeechPack = {},
                        )
                    }
                }
            }
        }

        onNodeWithText("语音输入").assertIsDisplayed()
        onNodeWithText("输入提供商").assertIsDisplayed()
        onNodeWithText("音频会发送到 Alibaba Cloud 进行识别。").assertIsDisplayed()
        onNodeWithText("输出提供商").assertIsDisplayed()
        onNodeWithText("文本会发送到 Alibaba Cloud 进行语音合成。").assertIsDisplayed()
        onNodeWithText("测试识别").assertIsDisplayed()
        onNodeWithText("测试语音").assertExists()
        onNodeWithText("Workspace ID").assertExists()
        onNodeWithText("qwen-audio-3.0-asr-flash").assertExists()
        onNodeWithText("qwen-audio-3.0-tts-flash").assertExists()
        onNodeWithText("longanfengyue").assertExists()
        onNodeWithText("llm-fixture-workspace").assertExists()
    }

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
            listOf("Map Display", "AI Features", "Feature Packs", "Overlays", "Shared Map"),
            PreferencesCategory.entries.map {
                AppStringsCatalog.forLocale(AppLocale.EN_US).preferences.categoryLabel(it)
            },
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

        var assistant = EmbeddedAssistantExpansionState()
        assistant = assistant.toggle(EmbeddedAssistantSection.AI_MODEL)
        assertEquals(EmbeddedAssistantSection.AI_MODEL, assistant.expanded)
        assistant = assistant.toggle(EmbeddedAssistantSection.WEB_SEARCH)
        assertEquals(EmbeddedAssistantSection.WEB_SEARCH, assistant.expanded)
        assistant = assistant.toggle(EmbeddedAssistantSection.WEB_SEARCH)
        assertEquals(null, assistant.expanded)
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
        onNodeWithText("▸ AI Model").assertIsDisplayed()
        onNodeWithText("▸ Web Search").assertIsDisplayed()
        onNodeWithText("▸ Voice I/O").assertIsDisplayed()
        onNodeWithText("AI Provider").assertDoesNotExist()

        onNodeWithText("▸ AI Model").performClick()
        onNodeWithText("AI Provider").assertIsDisplayed()
        onNodeWithText("▸ Web Search").performClick()
        onNodeWithText("AI Provider").assertDoesNotExist()
        onNodeWithText("Enable Web Search").assertIsDisplayed()
        onNodeWithText("▸ Voice I/O").performClick()
        onNodeWithText("Enable Web Search").assertDoesNotExist()
        onNodeWithText("Voice Input").assertIsDisplayed()

        onNodeWithText("▸ MCP Integration").assertIsDisplayed().performClick()
        onNodeWithText("Voice Input").assertDoesNotExist()
        onNodeWithText("MCP Server & Permissions").assertIsDisplayed()
    }

    @Test
    fun `Embedded Assistant accordions reset after content reopens`() = runComposeUiTest {
        var visible by mutableStateOf(true)
        setContent {
            EveTheme {
                if (visible) {
                    Column { TestAiFeaturesContent() }
                }
            }
        }

        onNodeWithText("▸ Embedded Assistant").performClick()
        onNodeWithText("▸ AI Model").performClick()
        onNodeWithText("AI Provider").assertIsDisplayed()

        visible = false
        waitForIdle()
        visible = true
        waitForIdle()

        onNodeWithText("▸ Embedded Assistant").assertIsDisplayed().performClick()
        onNodeWithText("▸ AI Model").assertIsDisplayed()
        onNodeWithText("▸ Web Search").assertIsDisplayed()
        onNodeWithText("▸ Voice I/O").assertIsDisplayed()
        onNodeWithText("AI Provider").assertDoesNotExist()
    }

    @Test
    fun `Speech Pack belongs only to Local Voice Input and stays above Voice Output`() = runComposeUiTest {
        var config by mutableStateOf(
            VoiceConfig(inputProvider = VoiceInputProvider.LOCAL, outputProvider = VoiceOutputProvider.LOCAL),
        )
        setContent {
            EveTheme {
                Column(Modifier.requiredSize(620.dp, 900.dp)) {
                    VoicePreferencesContent(
                        savedConfig = config,
                        state = VoiceSettingsUiState(
                            speechPack = SpeechPackState(
                                installed = true,
                                modelBytes = 148_000_000,
                            ),
                            windowsVoices = listOf("Fixture Voice"),
                        ),
                        onViewed = {},
                        onSave = { _, openAi, alibaba -> openAi?.close(); alibaba?.close() },
                        onDeleteCredential = {},
                        onTestRecognition = {},
                        onTestVoice = {},
                        onInstallSpeechPack = {},
                        onRemoveSpeechPack = {},
                    )
                }
            }
        }

        val input = onNodeWithTag(VOICE_INPUT_SECTION_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val pack = onNodeWithTag(VOICE_SPEECH_PACK_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val output = onNodeWithTag(VOICE_OUTPUT_SECTION_TEST_TAG).fetchSemanticsNode().boundsInRoot
        onNodeWithText("Optional Speech Pack").assertIsDisplayed()
        assertTrue(pack.top >= input.top && pack.bottom <= input.bottom)
        assertTrue(pack.bottom <= output.top, "Speech Pack must render before and outside Voice Output.")

        config = VoiceConfig(inputProvider = VoiceInputProvider.OPENAI, outputProvider = VoiceOutputProvider.LOCAL)
        waitForIdle()
        onNodeWithTag(VOICE_SPEECH_PACK_TEST_TAG).assertDoesNotExist()
        onNodeWithText("STT Model").assertIsDisplayed()

        config = VoiceConfig(inputProvider = VoiceInputProvider.OFF, outputProvider = VoiceOutputProvider.LOCAL)
        waitForIdle()
        onNodeWithTag(VOICE_SPEECH_PACK_TEST_TAG).assertDoesNotExist()
        onNodeWithText("Windows Speech").assertIsDisplayed()
    }

    @Test
    fun `Alibaba voice settings show cloud privacy and a single shared credential editor`() = runComposeUiTest {
        var config by mutableStateOf(
            VoiceConfig(
                inputProvider = VoiceInputProvider.ALIBABA,
                outputProvider = VoiceOutputProvider.ALIBABA,
            ),
        )
        setContent {
            EveTheme {
                Column(Modifier.requiredSize(680.dp, 1_200.dp)) {
                    VoicePreferencesContent(
                        savedConfig = config,
                        state = VoiceSettingsUiState(
                            alibabaCredentialSource = AiCredentialSource.SECURE_STORAGE,
                        ),
                        onViewed = {},
                        onSave = { _, openAi, alibaba -> openAi?.close(); alibaba?.close() },
                        onDeleteCredential = {},
                        onTestRecognition = {},
                        onTestVoice = {},
                        onInstallSpeechPack = {},
                        onRemoveSpeechPack = {},
                    )
                }
            }
        }

        onNodeWithText("Audio is sent to Alibaba Cloud for transcription.").assertExists()
        onNodeWithText("Text is sent to Alibaba Cloud for speech synthesis.").assertExists()
        onNodeWithText("Test Recognition").assertExists()
        onNodeWithText("Test Voice").assertExists()
        onAllNodesWithText("Replace Alibaba Speech API Key").assertCountEquals(1)
        onNodeWithText("OpenAI Voice API Key").assertDoesNotExist()
        onNodeWithText("Optional Speech Pack").assertDoesNotExist()
        onNodeWithText("Workspace ID").assertDoesNotExist()

        config = config.copy(
            profiles = SpeechProviderProfiles(
                alibaba = AlibabaSpeechProfile(
                    sttModel = "qwen-audio-3.0-asr-flash",
                    workspaceId = "fixture-workspace",
                ),
            ),
        )
        waitForIdle()
        onNodeWithText("Workspace ID").assertExists()

        config = config.copy(
            profiles = SpeechProviderProfiles(
                alibaba = AlibabaSpeechProfile(
                    ttsModel = "qwen-audio-3.0-tts-flash",
                    voice = "longanhuan_v3.6",
                    workspaceId = "fixture-workspace",
                ),
            ),
        )
        waitForIdle()
        onNodeWithText("Workspace ID").assertExists()
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

@Composable
private fun TestAiFeaturesContent() {
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

private fun SecretValue?.readAndClose(): String {
    val secret = checkNotNull(this)
    return secret.use { value -> value.useString { it } }
}
