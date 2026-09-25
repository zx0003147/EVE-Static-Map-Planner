package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.control.AiControlStatus
import dev.evestaticmapplanner.ai.AiProviderSettingsUiState
import dev.evestaticmapplanner.ai.WebSearchSettingsUiState
import dev.evestaticmapplanner.ai.VoiceSettingsUiState
import dev.evestaticmapplanner.ai.VoiceCredentialProvider
import dev.evestaticmapplanner.ai.GlobalPushToTalkState
import dev.evestaticmapplanner.embeddedai.AiConnectionCheck
import dev.evestaticmapplanner.embeddedai.AiConnectionCheckStatus
import dev.evestaticmapplanner.embeddedai.AiCredentialRef
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiProviderConfig
import dev.evestaticmapplanner.embeddedai.AiProviderType
import dev.evestaticmapplanner.embeddedai.ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL
import dev.evestaticmapplanner.embeddedai.SearchTestCheck
import dev.evestaticmapplanner.embeddedai.SearchTestCheckStatus
import dev.evestaticmapplanner.embeddedai.WebSearchConfig
import dev.evestaticmapplanner.embeddedai.OPENAI_BUILT_IN_VOICES
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechProfile
import dev.evestaticmapplanner.embeddedai.LocalSpeechProfile
import dev.evestaticmapplanner.embeddedai.OpenAiSpeechProfile
import dev.evestaticmapplanner.embeddedai.SpeechProviderProfiles
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.featurepack.FeaturePackInstallationState
import dev.evestaticmapplanner.featurepack.FeaturePackManagerItem
import dev.evestaticmapplanner.featurepack.FeaturePackManagerViewModel
import dev.evestaticmapplanner.featurepack.FeaturePackRuntimeState
import dev.evestaticmapplanner.featurepack.PackControlActionKey
import dev.evestaticmapplanner.featurepack.PackControlActionUiState
import dev.evestaticmapplanner.feature.api.PackControlActionStatus
import dev.evestaticmapplanner.feature.api.PackControlSeverity
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.localization.PreferencesStrings
import dev.evestaticmapplanner.localization.PreferencesText
import dev.evestaticmapplanner.localization.UiMessage
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.SharedAdminUiState
import dev.evestaticmapplanner.shared.SharedMapMembersDialog
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.ui.EveCheckbox as Checkbox
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDivider as HorizontalDivider
import dev.evestaticmapplanner.ui.EveDropdownMenu as DropdownMenu
import dev.evestaticmapplanner.ui.EveDropdownMenuItem as DropdownMenuItem
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveVerticalScrollColumn
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun PreferencesWindow(
    currentZoom: Double?,
    preferences: AppPreferences,
    onMapDisplayChange: (MapDisplayPreferences) -> Unit,
    onLocaleChange: (AppLocale) -> Unit,
    aiProviderSettingsState: AiProviderSettingsUiState = AiProviderSettingsUiState(),
    webSearchSettingsState: WebSearchSettingsUiState = WebSearchSettingsUiState(),
    voiceSettingsState: VoiceSettingsUiState = VoiceSettingsUiState(),
    initialCategory: PreferencesCategory = PreferencesCategory.MAP_DISPLAY,
    onAiProviderViewed: (AiProviderType) -> Unit = {},
    onAiProviderTest: (AiProviderConfig, SecretValue?) -> Unit = { _, secret -> secret?.close() },
    onAiProviderSave: (AiProviderConfig, SecretValue?) -> Unit = { _, secret -> secret?.close() },
    onAiCredentialDelete: (AiProviderType) -> Unit = {},
    onWebSearchViewed: (WebSearchConfig) -> Unit = {},
    onWebSearchTest: (WebSearchConfig, SecretValue?) -> Unit = { _, secret -> secret?.close() },
    onWebSearchSave: (WebSearchConfig, SecretValue?) -> Unit = { _, secret -> secret?.close() },
    onWebSearchCredentialDelete: (WebSearchConfig) -> Unit = {},
    onVoiceViewed: (VoiceConfig) -> Unit = {},
    onVoiceSave: (VoiceConfig, SecretValue?, SecretValue?) -> Unit = { _, openAi, alibaba ->
        openAi?.close()
        alibaba?.close()
    },
    onVoiceCredentialDelete: (VoiceCredentialProvider) -> Unit = {},
    onVoiceTestRecognition: (VoiceConfig) -> Unit = {},
    onVoiceTestVoice: (VoiceConfig) -> Unit = {},
    pushToTalkState: GlobalPushToTalkState = GlobalPushToTalkState(),
    assistantOpen: Boolean = false,
    pushToTalkPlatformSupported: Boolean = false,
    onPushToTalkCaptureStateChanged: (Boolean) -> Unit = {},
    onPushToTalkShortcutChange: suspend (KeyboardShortcut?) -> Result<Unit> = { Result.success(Unit) },
    onSpeechPackInstall: () -> Unit = {},
    onSpeechPackRemove: () -> Unit = {},
    aiControlStatus: AiControlStatus,
    aiControlError: UiMessage?,
    featurePackManagerViewModel: FeaturePackManagerViewModel,
    overlayState: OverlayState,
    sovereigntyAvailable: Boolean,
    sharedMapState: SharedMapState,
    sharedMapOperationError: UiMessage?,
    sharedAdminState: SharedAdminUiState,
    onSharedMapConnect: (String, SecretValue, String) -> Unit,
    onSharedMapWorkspaceChange: (String) -> Unit,
    onSharedMapRefresh: () -> Unit,
    onSharedMapDisconnect: () -> Unit,
    onSharedMapClearError: () -> Unit,
    onSharedMapLoadMembers: () -> Unit,
    onSharedMapCreateMember: (String, SharedWorkspaceRole) -> Boolean,
    onSharedMapChangeMemberRole: (String, Long, SharedWorkspaceRole) -> Boolean,
    onSharedMapRemoveMember: (String, Long) -> Boolean,
    onSharedMapCreateInvite: (String, Long) -> Boolean,
    onSharedMapClearAdminError: () -> Unit,
    onSharedMapClearInvite: () -> Unit,
    onOverlayVisibilityChange: (OverlayVisibilityPreferences) -> Unit,
    onAiControlChange: (Boolean) -> Unit,
    onAiSavedMarkerAccessChange: (Boolean) -> Unit,
    onResetMapDisplay: () -> Unit,
    onResetAiControl: () -> Unit,
    onResetOverlayVisibility: () -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    val strings = LocalAppStrings.current
    Window(
        onCloseRequest = onDismiss,
        title = strings.preferences.title,
        state = rememberWindowState(width = 650.dp, height = 720.dp),
    ) {
        EveWindowChrome(window)
        EveWindowSurface(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(18.dp),
            ) {
                Text(strings.preferences.title, style = MaterialTheme.typography.titleLarge)
                LanguagePreferenceContent(preferences.uiLocale, onLocaleChange)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PreferencesCategory.entries.forEach { item ->
                            TextButton(
                                onClick = { selectedCategory = item },
                                selected = selectedCategory == item,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(strings.preferences.categoryLabel(item)) }
                        }
                    }
                    EveVerticalScrollColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .padding(start = PREFERENCES_CONTENT_START_GUTTER),
                    ) {
                        when (selectedCategory) {
                            PreferencesCategory.MAP_DISPLAY -> MapDisplayPreferencesContent(
                                currentZoom,
                                preferences.mapDisplay,
                                onMapDisplayChange,
                                onResetMapDisplay,
                            )
                            PreferencesCategory.AI_FEATURES -> AiFeaturesPreferencesContent(
                                savedConfig = preferences.aiProvider,
                                savedProviderConfigs = preferences.aiProviderProfiles,
                                state = aiProviderSettingsState,
                                onProviderViewed = onAiProviderViewed,
                                onTest = onAiProviderTest,
                                onSave = onAiProviderSave,
                                onDeleteCredential = onAiCredentialDelete,
                                webSearchConfig = preferences.webSearch,
                                webSearchState = webSearchSettingsState,
                                onWebSearchViewed = onWebSearchViewed,
                                onWebSearchTest = onWebSearchTest,
                                onWebSearchSave = onWebSearchSave,
                                onWebSearchCredentialDelete = onWebSearchCredentialDelete,
                                voiceConfig = preferences.voice,
                                voiceState = voiceSettingsState,
                                onVoiceViewed = onVoiceViewed,
                                onVoiceSave = onVoiceSave,
                                onVoiceCredentialDelete = onVoiceCredentialDelete,
                                onVoiceTestRecognition = onVoiceTestRecognition,
                                onVoiceTestVoice = onVoiceTestVoice,
                                pushToTalkShortcut = preferences.pushToTalkShortcut,
                                pushToTalkState = pushToTalkState,
                                assistantOpen = assistantOpen,
                                pushToTalkPlatformSupported = pushToTalkPlatformSupported,
                                onPushToTalkCaptureStateChanged = onPushToTalkCaptureStateChanged,
                                onPushToTalkShortcutChange = onPushToTalkShortcutChange,
                                onSpeechPackInstall = onSpeechPackInstall,
                                onSpeechPackRemove = onSpeechPackRemove,
                                aiControlPreferences = preferences.aiControl,
                                aiControlStatus = aiControlStatus,
                                aiControlError = aiControlError,
                                onAiControlChange = onAiControlChange,
                                onAiSavedMarkerAccessChange = onAiSavedMarkerAccessChange,
                                onResetAiControl = onResetAiControl,
                            )
                            PreferencesCategory.FEATURE_PACKS -> FeaturePacksPreferencesContent(
                                featurePackManagerViewModel,
                            )
                            PreferencesCategory.OVERLAYS -> OverlayPreferencesContent(
                                overlayState,
                                sovereigntyAvailable,
                                preferences.overlayVisibility,
                                preferences.mapDisplay,
                                onOverlayVisibilityChange,
                                onMapDisplayChange,
                                onResetOverlayVisibility,
                            )
                            PreferencesCategory.SHARED_MAP -> SharedMapPreferencesContent(
                                preferences.sharedMap,
                                sharedMapState,
                                sharedMapOperationError,
                                sharedAdminState,
                                onSharedMapConnect,
                                onSharedMapWorkspaceChange,
                                onSharedMapRefresh,
                                onSharedMapDisconnect,
                                onSharedMapClearError,
                                onSharedMapLoadMembers,
                                onSharedMapCreateMember,
                                onSharedMapChangeMemberRole,
                                onSharedMapRemoveMember,
                                onSharedMapCreateInvite,
                                onSharedMapClearAdminError,
                                onSharedMapClearInvite,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onResetAll) {
                        Text(strings.preferences.text(PreferencesText.RESET_ALL))
                    }
                    TextButton(onClick = onDismiss) { Text(strings.common.close) }
                }
            }
        }
    }
}

@Composable
internal fun LanguagePreferenceContent(
    locale: AppLocale,
    onLocaleChange: (AppLocale) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings.preferences.language, style = MaterialTheme.typography.titleSmall)
        Box {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("language-selector"),
            ) {
                Text("${strings.preferences.languageName(locale)} ▾")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                AppLocale.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(strings.preferences.languageName(option)) },
                        onClick = {
                            expanded = false
                            if (option != locale) onLocaleChange(option)
                        },
                        modifier = Modifier.testTag("language-option-${option.tag}"),
                    )
                }
            }
        }
    }
}

internal enum class PreferencesCategory {
    MAP_DISPLAY,
    AI_FEATURES,
    FEATURE_PACKS,
    OVERLAYS,
    SHARED_MAP,
}

internal fun PreferencesStrings.categoryLabel(category: PreferencesCategory): String = text(
    when (category) {
        PreferencesCategory.MAP_DISPLAY -> PreferencesText.CATEGORY_MAP_DISPLAY
        PreferencesCategory.AI_FEATURES -> PreferencesText.CATEGORY_AI_FEATURES
        PreferencesCategory.FEATURE_PACKS -> PreferencesText.CATEGORY_FEATURE_PACKS
        PreferencesCategory.OVERLAYS -> PreferencesText.CATEGORY_OVERLAYS
        PreferencesCategory.SHARED_MAP -> PreferencesText.CATEGORY_SHARED_MAP
    },
)

internal enum class AiFeaturesSection { EMBEDDED_ASSISTANT, MCP_INTEGRATION }

internal data class AiFeaturesExpansionState(val expanded: AiFeaturesSection? = null) {
    fun toggle(section: AiFeaturesSection) = copy(expanded = if (expanded == section) null else section)
}

internal enum class EmbeddedAssistantSection { AI_MODEL, WEB_SEARCH, VOICE_IO }

internal data class EmbeddedAssistantExpansionState(val expanded: EmbeddedAssistantSection? = null) {
    fun toggle(section: EmbeddedAssistantSection) = copy(expanded = if (expanded == section) null else section)
}

@Composable
internal fun AiFeaturesPreferencesContent(
    savedConfig: AiProviderConfig?,
    savedProviderConfigs: Map<AiProviderType, AiProviderConfig>,
    state: AiProviderSettingsUiState,
    onProviderViewed: (AiProviderType) -> Unit,
    onTest: (AiProviderConfig, SecretValue?) -> Unit,
    onSave: (AiProviderConfig, SecretValue?) -> Unit,
    onDeleteCredential: (AiProviderType) -> Unit,
    aiControlPreferences: AiControlPreferences,
    aiControlStatus: AiControlStatus,
    aiControlError: UiMessage?,
    onAiControlChange: (Boolean) -> Unit,
    onAiSavedMarkerAccessChange: (Boolean) -> Unit,
    onResetAiControl: () -> Unit,
    webSearchConfig: WebSearchConfig = WebSearchConfig.Defaults,
    webSearchState: WebSearchSettingsUiState = WebSearchSettingsUiState(),
    onWebSearchViewed: (WebSearchConfig) -> Unit = {},
    onWebSearchTest: (WebSearchConfig, SecretValue?) -> Unit = { _, secret -> secret?.close() },
    onWebSearchSave: (WebSearchConfig, SecretValue?) -> Unit = { _, secret -> secret?.close() },
    onWebSearchCredentialDelete: (WebSearchConfig) -> Unit = {},
    voiceConfig: VoiceConfig = VoiceConfig.Defaults,
    voiceState: VoiceSettingsUiState = VoiceSettingsUiState(),
    onVoiceViewed: (VoiceConfig) -> Unit = {},
    onVoiceSave: (VoiceConfig, SecretValue?, SecretValue?) -> Unit = { _, openAi, alibaba ->
        openAi?.close()
        alibaba?.close()
    },
    onVoiceCredentialDelete: (VoiceCredentialProvider) -> Unit = {},
    onVoiceTestRecognition: (VoiceConfig) -> Unit = {},
    onVoiceTestVoice: (VoiceConfig) -> Unit = {},
    pushToTalkShortcut: KeyboardShortcut? = null,
    pushToTalkState: GlobalPushToTalkState = GlobalPushToTalkState(),
    assistantOpen: Boolean = false,
    pushToTalkPlatformSupported: Boolean = false,
    onPushToTalkCaptureStateChanged: (Boolean) -> Unit = {},
    onPushToTalkShortcutChange: suspend (KeyboardShortcut?) -> Result<Unit> = { Result.success(Unit) },
    onSpeechPackInstall: () -> Unit = {},
    onSpeechPackRemove: () -> Unit = {},
) {
    val strings = LocalAppStrings.current.preferences
    var expansion by remember { mutableStateOf(AiFeaturesExpansionState()) }
    var assistantExpansion by remember { mutableStateOf(EmbeddedAssistantExpansionState()) }
    AiFeaturesAccordionHeader(
        title = strings.text(PreferencesText.EMBEDDED_ASSISTANT),
        expanded = expansion.expanded == AiFeaturesSection.EMBEDDED_ASSISTANT,
        onClick = {
            expansion = expansion.toggle(AiFeaturesSection.EMBEDDED_ASSISTANT)
            assistantExpansion = EmbeddedAssistantExpansionState()
        },
    )
    if (expansion.expanded == AiFeaturesSection.EMBEDDED_ASSISTANT) {
        AiFeaturesAccordionHeader(
            title = strings.text(PreferencesText.AI_MODEL),
            expanded = assistantExpansion.expanded == EmbeddedAssistantSection.AI_MODEL,
            onClick = { assistantExpansion = assistantExpansion.toggle(EmbeddedAssistantSection.AI_MODEL) },
        )
        if (assistantExpansion.expanded == EmbeddedAssistantSection.AI_MODEL) {
            AiProviderPreferencesContent(
                savedConfig = savedConfig,
                savedProviderConfigs = savedProviderConfigs,
                state = state,
                onProviderViewed = onProviderViewed,
                onTest = onTest,
                onSave = onSave,
                onDeleteCredential = onDeleteCredential,
            )
        }
        HorizontalDivider()
        AiFeaturesAccordionHeader(
            title = strings.text(PreferencesText.WEB_SEARCH),
            expanded = assistantExpansion.expanded == EmbeddedAssistantSection.WEB_SEARCH,
            onClick = { assistantExpansion = assistantExpansion.toggle(EmbeddedAssistantSection.WEB_SEARCH) },
        )
        if (assistantExpansion.expanded == EmbeddedAssistantSection.WEB_SEARCH) {
            WebSearchPreferencesContent(
                savedConfig = webSearchConfig,
                state = webSearchState,
                onViewed = onWebSearchViewed,
                onTest = onWebSearchTest,
                onSave = onWebSearchSave,
                onDeleteCredential = onWebSearchCredentialDelete,
            )
        }
        HorizontalDivider()
        AiFeaturesAccordionHeader(
            title = strings.text(PreferencesText.VOICE_IO),
            expanded = assistantExpansion.expanded == EmbeddedAssistantSection.VOICE_IO,
            onClick = { assistantExpansion = assistantExpansion.toggle(EmbeddedAssistantSection.VOICE_IO) },
        )
        if (assistantExpansion.expanded == EmbeddedAssistantSection.VOICE_IO) {
            VoicePreferencesContent(
                savedConfig = voiceConfig,
                state = voiceState,
                onViewed = onVoiceViewed,
                onSave = onVoiceSave,
                onDeleteCredential = onVoiceCredentialDelete,
                onTestRecognition = onVoiceTestRecognition,
                onTestVoice = onVoiceTestVoice,
                pushToTalkShortcut = pushToTalkShortcut,
                pushToTalkState = pushToTalkState,
                assistantOpen = assistantOpen,
                pushToTalkPlatformSupported = pushToTalkPlatformSupported,
                onPushToTalkCaptureStateChanged = onPushToTalkCaptureStateChanged,
                onPushToTalkShortcutChange = onPushToTalkShortcutChange,
                onInstallSpeechPack = onSpeechPackInstall,
                onRemoveSpeechPack = onSpeechPackRemove,
            )
        }
    }
    HorizontalDivider()
    AiFeaturesAccordionHeader(
        title = strings.text(PreferencesText.MCP_INTEGRATION),
        expanded = expansion.expanded == AiFeaturesSection.MCP_INTEGRATION,
        onClick = { expansion = expansion.toggle(AiFeaturesSection.MCP_INTEGRATION) },
    )
    if (expansion.expanded == AiFeaturesSection.MCP_INTEGRATION) {
        AiControlPreferencesContent(
            preferences = aiControlPreferences,
            status = aiControlStatus,
            preferenceError = aiControlError,
            onChange = onAiControlChange,
            onSavedMarkerAccessChange = onAiSavedMarkerAccessChange,
            onReset = onResetAiControl,
        )
    }
}

@Composable
internal fun VoicePreferencesContent(
    savedConfig: VoiceConfig,
    state: VoiceSettingsUiState,
    onViewed: (VoiceConfig) -> Unit,
    onSave: (VoiceConfig, SecretValue?, SecretValue?) -> Unit,
    onDeleteCredential: (VoiceCredentialProvider) -> Unit,
    onTestRecognition: (VoiceConfig) -> Unit,
    onTestVoice: (VoiceConfig) -> Unit,
    pushToTalkShortcut: KeyboardShortcut? = null,
    pushToTalkState: GlobalPushToTalkState = GlobalPushToTalkState(),
    assistantOpen: Boolean = false,
    pushToTalkPlatformSupported: Boolean = false,
    onPushToTalkCaptureStateChanged: (Boolean) -> Unit = {},
    onPushToTalkShortcutChange: suspend (KeyboardShortcut?) -> Result<Unit> = { Result.success(Unit) },
    onInstallSpeechPack: () -> Unit,
    onRemoveSpeechPack: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.preferences
    var inputProvider by remember(savedConfig) { mutableStateOf(savedConfig.inputProvider) }
    var outputProvider by remember(savedConfig) { mutableStateOf(savedConfig.outputProvider) }
    var autoSend by remember(savedConfig) { mutableStateOf(savedConfig.autoSendAfterTranscription) }
    var autoRead by remember(savedConfig) { mutableStateOf(savedConfig.readAssistantRepliesAloud) }
    var openAiSttModel by remember(savedConfig) { mutableStateOf(savedConfig.profiles.openAi.sttModel) }
    var openAiTtsModel by remember(savedConfig) { mutableStateOf(savedConfig.profiles.openAi.ttsModel) }
    var openAiVoice by remember(savedConfig) { mutableStateOf(savedConfig.profiles.openAi.voice) }
    var alibabaSttModel by remember(savedConfig) { mutableStateOf(savedConfig.profiles.alibaba.sttModel) }
    var alibabaTtsModel by remember(savedConfig) { mutableStateOf(savedConfig.profiles.alibaba.ttsModel) }
    var alibabaVoice by remember(savedConfig) { mutableStateOf(savedConfig.profiles.alibaba.voice) }
    var alibabaSttRegion by remember(savedConfig) { mutableStateOf(savedConfig.profiles.alibaba.sttRegion) }
    var alibabaTtsRegion by remember(savedConfig) { mutableStateOf(savedConfig.profiles.alibaba.ttsRegion) }
    var workspaceId by remember(savedConfig) { mutableStateOf(savedConfig.profiles.alibaba.workspaceId.orEmpty()) }
    var windowsVoice by remember(savedConfig) { mutableStateOf(savedConfig.profiles.local.windowsVoice) }
    var rate by remember(savedConfig) { mutableStateOf(savedConfig.profiles.local.ttsRate) }
    var volume by remember(savedConfig) { mutableStateOf(savedConfig.profiles.local.ttsVolume) }
    var openAiKeyDraft by remember(savedConfig) { mutableStateOf("") }
    var alibabaKeyDraft by remember(savedConfig) { mutableStateOf("") }
    var inputExpanded by remember { mutableStateOf(false) }
    var outputExpanded by remember { mutableStateOf(false) }
    var openAiVoiceExpanded by remember { mutableStateOf(false) }
    var windowsVoiceExpanded by remember { mutableStateOf(false) }
    var alibabaSttRegionExpanded by remember { mutableStateOf(false) }
    var alibabaTtsRegionExpanded by remember { mutableStateOf(false) }
    val busy = state.busy
    val sttWorkspaceRequired = inputProvider == VoiceInputProvider.ALIBABA &&
        alibabaSttModel.equals(ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL, ignoreCase = true)
    val ttsWorkspaceRequired = outputProvider == VoiceOutputProvider.ALIBABA &&
        (alibabaTtsModel.startsWith("qwen-audio-", true) || alibabaTtsModel.startsWith("cosyvoice-", true))
    val workspaceRequired = sttWorkspaceRequired || ttsWorkspaceRequired
    val config = remember(
        inputProvider, outputProvider, autoSend, autoRead, openAiSttModel, openAiTtsModel, openAiVoice,
        alibabaSttModel, alibabaTtsModel, alibabaVoice, alibabaSttRegion, alibabaTtsRegion, workspaceId,
        windowsVoice, rate, volume, workspaceRequired, savedConfig,
    ) {
        runCatching {
            VoiceConfig(
                inputProvider = inputProvider,
                outputProvider = outputProvider,
                autoSendAfterTranscription = autoSend,
                readAssistantRepliesAloud = autoRead,
                profiles = SpeechProviderProfiles(
                    local = LocalSpeechProfile(windowsVoice, rate, volume),
                    openAi = OpenAiSpeechProfile(
                        sttModel = openAiSttModel.trim(),
                        ttsModel = openAiTtsModel.trim(),
                        voice = openAiVoice,
                        timeoutSeconds = savedConfig.profiles.openAi.timeoutSeconds,
                        credentialRef = savedConfig.profiles.openAi.credentialRef,
                    ),
                    alibaba = AlibabaSpeechProfile(
                        sttModel = alibabaSttModel.trim(),
                        ttsModel = alibabaTtsModel.trim(),
                        voice = alibabaVoice.trim(),
                        sttRegion = alibabaSttRegion,
                        ttsRegion = alibabaTtsRegion,
                        workspaceId = workspaceId.trim().takeIf(String::isNotEmpty),
                        sttTimeoutSeconds = savedConfig.profiles.alibaba.sttTimeoutSeconds,
                        ttsTimeoutSeconds = savedConfig.profiles.alibaba.ttsTimeoutSeconds,
                        credentialRef = savedConfig.profiles.alibaba.credentialRef,
                    ),
                ),
            )
        }.getOrNull()?.takeUnless {
            workspaceRequired && it.profiles.alibaba.workspaceId == null
        }
    }
    LaunchedEffect(savedConfig) { onViewed(savedConfig) }

    fun draft(value: String, clear: () -> Unit): SecretValue? {
        val normalized = value.trim()
        clear()
        return normalized.takeIf(String::isNotEmpty)?.let(SecretValue::from)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().testTag(VOICE_INPUT_SECTION_TEST_TAG),
    ) {
        Text(strings.text(PreferencesText.VOICE_INPUT), style = MaterialTheme.typography.titleSmall)
        PushToTalkShortcutPreference(
            shortcut = pushToTalkShortcut,
            globalState = pushToTalkState,
            assistantOpen = assistantOpen,
            platformSupported = pushToTalkPlatformSupported,
            onCaptureStateChanged = onPushToTalkCaptureStateChanged,
            onShortcutChange = onPushToTalkShortcutChange,
        )
        EnumDropdown(strings.text(PreferencesText.INPUT_PROVIDER), strings.voiceInputProvider(inputProvider), inputExpanded, { inputExpanded = it }, !busy,
            Modifier.testTag(VOICE_INPUT_PROVIDER_TEST_TAG)) {
            VoiceInputProvider.entries.forEach { provider ->
                DropdownMenuItem({ Text(strings.voiceInputProvider(provider)) }, { inputProvider = provider; inputExpanded = false })
            }
        }
        Text(
            when (inputProvider) {
                VoiceInputProvider.LOCAL -> strings.text(PreferencesText.INPUT_LOCAL_HELP)
                VoiceInputProvider.OPENAI -> strings.text(PreferencesText.INPUT_OPENAI_HELP)
                VoiceInputProvider.ALIBABA -> strings.text(PreferencesText.INPUT_ALIBABA_HELP)
                VoiceInputProvider.OFF -> strings.text(PreferencesText.INPUT_OFF_HELP)
            },
            color = EveColors.SecondaryText,
        )
        when (inputProvider) {
            VoiceInputProvider.LOCAL -> Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().testTag(VOICE_SPEECH_PACK_TEST_TAG),
            ) {
                Text(strings.text(PreferencesText.OPTIONAL_SPEECH_PACK), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (state.speechPack.installed) strings.text(PreferencesText.INSTALLED_MODEL, state.speechPack.modelName)
                    else strings.text(PreferencesText.LOCAL_SPEECH_MODEL_NOT_INSTALLED),
                    color = EveColors.SecondaryText,
                )
                TextButton(
                    onClick = if (state.speechPack.installed) onRemoveSpeechPack else onInstallSpeechPack,
                    enabled = !busy,
                ) {
                    Text(strings.text(if (state.speechPack.installed) PreferencesText.REMOVE_SPEECH_PACK else PreferencesText.DOWNLOAD_SPEECH_PACK))
                }
            }
            VoiceInputProvider.OPENAI -> {
                SpeechTextField(strings.text(PreferencesText.STT_MODEL), openAiSttModel, { openAiSttModel = it }, busy)
                VoiceCredentialFields(
                    "OpenAI Voice", "OPENAI_VOICE_API_KEY", openAiKeyDraft,
                    { openAiKeyDraft = it }, state.openAiCredentialSource, busy,
                )
            }
            VoiceInputProvider.ALIBABA -> {
                VoiceCredentialFields(
                    "Alibaba Speech", "DASHSCOPE_API_KEY", alibabaKeyDraft,
                    { alibabaKeyDraft = it }, state.alibabaCredentialSource, busy,
                )
                SpeechTextField(strings.text(PreferencesText.STT_MODEL), alibabaSttModel, { alibabaSttModel = it }, busy)
                AlibabaRegionDropdown(
                    alibabaSttRegion, alibabaSttRegionExpanded, { alibabaSttRegionExpanded = it },
                    { alibabaSttRegion = it; alibabaSttRegionExpanded = false }, busy,
                )
                if (sttWorkspaceRequired) {
                    SpeechTextField(strings.text(PreferencesText.WORKSPACE_ID), workspaceId, { workspaceId = it }, busy)
                }
                TextButton(
                    onClick = { config?.let(onTestRecognition) },
                    enabled = !busy && config != null && state.alibabaCredentialSource != null,
                ) {
                    Text(strings.text(if (state.isTestingRecognition) PreferencesText.TESTING_RECOGNITION else PreferencesText.TEST_RECOGNITION))
                }
            }
            VoiceInputProvider.OFF -> Unit
        }
        PreferenceCheckbox(strings.text(PreferencesText.AUTO_SEND_AFTER_TRANSCRIPTION), autoSend, !busy) { autoSend = it }
    }

    HorizontalDivider()

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().testTag(VOICE_OUTPUT_SECTION_TEST_TAG),
    ) {
        Text(strings.text(PreferencesText.VOICE_OUTPUT), style = MaterialTheme.typography.titleSmall)
        EnumDropdown(strings.text(PreferencesText.OUTPUT_PROVIDER), strings.voiceOutputProvider(outputProvider), outputExpanded, { outputExpanded = it }, !busy,
            Modifier.testTag(VOICE_OUTPUT_PROVIDER_TEST_TAG)) {
            VoiceOutputProvider.entries.forEach { provider ->
                DropdownMenuItem({ Text(strings.voiceOutputProvider(provider)) }, { outputProvider = provider; outputExpanded = false })
            }
        }
        Text(
            when (outputProvider) {
                VoiceOutputProvider.LOCAL -> strings.text(PreferencesText.OUTPUT_LOCAL_HELP)
                VoiceOutputProvider.OPENAI -> strings.text(PreferencesText.OUTPUT_OPENAI_HELP)
                VoiceOutputProvider.ALIBABA -> strings.text(PreferencesText.OUTPUT_ALIBABA_HELP)
                VoiceOutputProvider.OFF -> strings.text(PreferencesText.OUTPUT_OFF_HELP)
            },
            color = EveColors.SecondaryText,
        )
        when (outputProvider) {
            VoiceOutputProvider.LOCAL -> {
                Text(strings.text(PreferencesText.WINDOWS_SPEECH), style = MaterialTheme.typography.titleSmall)
                EnumDropdown(strings.text(PreferencesText.WINDOWS_VOICE), windowsVoice ?: strings.text(PreferencesText.SYSTEM_DEFAULT), windowsVoiceExpanded,
                    { windowsVoiceExpanded = it }, !busy && state.windowsVoices.isNotEmpty()) {
                    DropdownMenuItem({ Text(strings.text(PreferencesText.SYSTEM_DEFAULT)) }, { windowsVoice = null; windowsVoiceExpanded = false })
                    state.windowsVoices.forEach { voice ->
                        DropdownMenuItem({ Text(voice) }, { windowsVoice = voice; windowsVoiceExpanded = false })
                    }
                }
                Text(strings.text(PreferencesText.RATE, rate))
                Slider(rate.toFloat(), { rate = it.toInt() }, valueRange = -10f..10f, steps = 19)
                Text(strings.text(PreferencesText.VOLUME, volume))
                Slider(volume.toFloat(), { volume = it.toInt() }, valueRange = 0f..100f, steps = 99)
            }
            VoiceOutputProvider.OPENAI -> {
                SpeechTextField(strings.text(PreferencesText.TTS_MODEL), openAiTtsModel, { openAiTtsModel = it }, busy)
                EnumDropdown("OpenAI ${strings.text(PreferencesText.VOICE)}", openAiVoice, openAiVoiceExpanded, { openAiVoiceExpanded = it }, !busy) {
                    OPENAI_BUILT_IN_VOICES.forEach { voice ->
                        DropdownMenuItem({ Text(voice) }, { openAiVoice = voice; openAiVoiceExpanded = false })
                    }
                }
                if (inputProvider != VoiceInputProvider.OPENAI) {
                    VoiceCredentialFields(
                        "OpenAI Voice", "OPENAI_VOICE_API_KEY", openAiKeyDraft,
                        { openAiKeyDraft = it }, state.openAiCredentialSource, busy,
                    )
                }
            }
            VoiceOutputProvider.ALIBABA -> {
                if (inputProvider == VoiceInputProvider.ALIBABA) {
                    VoiceCredentialStatus("Alibaba Speech", "DASHSCOPE_API_KEY", state.alibabaCredentialSource)
                } else {
                    VoiceCredentialFields(
                        "Alibaba Speech", "DASHSCOPE_API_KEY", alibabaKeyDraft,
                        { alibabaKeyDraft = it }, state.alibabaCredentialSource, busy,
                    )
                }
                SpeechTextField(strings.text(PreferencesText.TTS_MODEL), alibabaTtsModel, { alibabaTtsModel = it }, busy)
                SpeechTextField(strings.text(PreferencesText.VOICE), alibabaVoice, { alibabaVoice = it }, busy)
                AlibabaRegionDropdown(
                    alibabaTtsRegion, alibabaTtsRegionExpanded, { alibabaTtsRegionExpanded = it },
                    { alibabaTtsRegion = it; alibabaTtsRegionExpanded = false }, busy,
                )
                if (ttsWorkspaceRequired && !sttWorkspaceRequired) {
                    SpeechTextField(strings.text(PreferencesText.WORKSPACE_ID), workspaceId, { workspaceId = it }, busy)
                }
                TextButton(
                    onClick = { config?.let(onTestVoice) },
                    enabled = !busy && config != null && state.alibabaCredentialSource != null,
                ) {
                    Text(strings.text(if (state.isTestingVoice) PreferencesText.TESTING_VOICE else PreferencesText.TEST_VOICE))
                }
            }
            VoiceOutputProvider.OFF -> Unit
        }
        PreferenceCheckbox(strings.text(PreferencesText.READ_ASSISTANT_REPLIES_ALOUD), autoRead, !busy) { autoRead = it }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                config?.let {
                    onSave(
                        it,
                        draft(openAiKeyDraft) { openAiKeyDraft = "" },
                        draft(alibabaKeyDraft) { alibabaKeyDraft = "" },
                    )
                }
            },
            enabled = !busy && config != null,
        ) { Text(strings.text(PreferencesText.SAVE_VOICE_SETTINGS)) }
        if (state.isSaving) CircularProgressIndicator()
    }
    if (state.openAiCredentialSource in setOf(AiCredentialSource.SECURE_STORAGE, AiCredentialSource.SESSION_ONLY) &&
        (inputProvider == VoiceInputProvider.OPENAI || outputProvider == VoiceOutputProvider.OPENAI)
    ) {
        TextButton(onClick = { onDeleteCredential(VoiceCredentialProvider.OPENAI) }, enabled = !busy) {
            Text(strings.text(PreferencesText.DELETE_OPENAI_VOICE_KEY))
        }
    }
    if (state.alibabaCredentialSource in setOf(AiCredentialSource.SECURE_STORAGE, AiCredentialSource.SESSION_ONLY) &&
        (inputProvider == VoiceInputProvider.ALIBABA || outputProvider == VoiceOutputProvider.ALIBABA)
    ) {
        TextButton(onClick = { onDeleteCredential(VoiceCredentialProvider.ALIBABA) }, enabled = !busy) {
            Text(strings.text(PreferencesText.DELETE_ALIBABA_SPEECH_KEY))
        }
    }
    state.message?.let { Text(it.resolve(appStrings), color = EveColors.SecondaryText) }
    state.errorMessage?.let { Text(it.resolve(appStrings), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun SpeechTextField(label: String, value: String, onValueChange: (String) -> Unit, busy: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VoiceCredentialFields(
    providerName: String,
    environmentName: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    source: AiCredentialSource?,
    busy: Boolean,
) {
    val strings = LocalAppStrings.current.preferences
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        label = {
            Text(strings.text(if (source == null) PreferencesText.PROVIDER_API_KEY else PreferencesText.REPLACE_PROVIDER_API_KEY, providerName))
        },
        placeholder = {
            Text(strings.text(if (source == null) PreferencesText.ENTER_API_KEY else PreferencesText.LEAVE_BLANK_TO_KEEP_KEY))
        },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    VoiceCredentialStatus(providerName, environmentName, source)
}

@Composable
private fun VoiceCredentialStatus(providerName: String, environmentName: String, source: AiCredentialSource?) {
    val strings = LocalAppStrings.current.preferences
    Text(
        strings.credentialStatus(providerName, environmentName, source),
        color = EveColors.SecondaryText,
    )
}

@Composable
private fun AlibabaRegionDropdown(
    value: AlibabaSpeechRegion,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (AlibabaSpeechRegion) -> Unit,
    busy: Boolean,
) {
    val strings = LocalAppStrings.current.preferences
    EnumDropdown(strings.text(PreferencesText.REGION), strings.speechRegion(value), expanded, onExpandedChange, !busy) {
        AlibabaSpeechRegion.entries.forEach { region ->
            DropdownMenuItem({ Text(strings.speechRegion(region)) }, { onSelect(region) })
        }
    }
}

@Composable
private fun EnumDropdown(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Text(label)
        Box {
            TextButton(onClick = { onExpandedChange(true) }, enabled = enabled) { Text("$value ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) { content() }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun AiFeaturesAccordionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, selected = expanded, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "▾ $title" else "▸ $title", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun AiProviderPreferencesContent(
    savedConfig: AiProviderConfig?,
    savedProviderConfigs: Map<AiProviderType, AiProviderConfig> = savedConfig
        ?.let { mapOf(it.providerType to it) }
        .orEmpty(),
    state: AiProviderSettingsUiState,
    onProviderViewed: (AiProviderType) -> Unit,
    onTest: (AiProviderConfig, SecretValue?) -> Unit,
    onSave: (AiProviderConfig, SecretValue?) -> Unit,
    onDeleteCredential: (AiProviderType) -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.preferences
    val initial = savedConfig ?: AiProviderConfig.DefaultOpenRouter
    var providerType by remember(savedConfig, savedProviderConfigs) { mutableStateOf(initial.providerType) }
    var baseUrl by remember(savedConfig, savedProviderConfigs) { mutableStateOf(initial.baseUrl.orEmpty()) }
    var modelId by remember(savedConfig, savedProviderConfigs) { mutableStateOf(initial.modelId) }
    var timeout by remember(savedConfig, savedProviderConfigs) {
        mutableStateOf(initial.requestTimeoutSeconds.toString())
    }
    var temperature by remember(savedConfig, savedProviderConfigs) {
        mutableStateOf(initial.temperature?.toString().orEmpty())
    }
    var apiKeyDraft by remember(savedConfig, savedProviderConfigs) { mutableStateOf("") }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<PreferencesText?>(null) }
    val busy = state.isSaving || state.isTesting

    LaunchedEffect(providerType) { onProviderViewed(providerType) }

    fun switchProvider(next: AiProviderType) {
        providerType = next
        apiKeyDraft = ""
        validationError = null
        val saved = savedProviderConfigs[next]
        if (saved != null) {
            baseUrl = saved.baseUrl.orEmpty()
            modelId = saved.modelId
            timeout = saved.requestTimeoutSeconds.toString()
            temperature = saved.temperature?.toString().orEmpty()
        } else {
            baseUrl = ""
            modelId = if (next == AiProviderType.OPENROUTER) AiProviderConfig.DEFAULT_OPENROUTER_MODEL else ""
            timeout = AiProviderConfig.DEFAULT_TIMEOUT_SECONDS.toString()
            temperature = AiProviderConfig.DEFAULT_TEMPERATURE.toString()
        }
    }

    fun buildConfig(): AiProviderConfig? = runCatching {
        AiProviderConfig.normalized(
            providerType = providerType,
            baseUrl = baseUrl,
            credentialRef = AiCredentialRef.forProvider(providerType),
            modelId = modelId,
            temperature = temperature.trim().takeIf(String::isNotEmpty)?.toDouble()
                ?: throw IllegalArgumentException(),
            requestTimeoutSeconds = timeout.trim().toInt(),
        )
    }.fold(
        onSuccess = {
            validationError = null
            it
        },
        onFailure = {
            validationError = if (temperature.isBlank()) {
                PreferencesText.TEMPERATURE_REQUIRED
            } else {
                PreferencesText.PROVIDER_SETTINGS_INVALID
            }
            null
        },
    )

    fun draftSecret(clear: Boolean): SecretValue? {
        val normalized = apiKeyDraft.trim()
        if (clear) apiKeyDraft = ""
        return normalized.takeIf(String::isNotEmpty)?.let(SecretValue::from)
    }

    Text(strings.text(PreferencesText.AI_PROVIDER), style = MaterialTheme.typography.titleSmall)
    Text(
        strings.text(PreferencesText.AI_PROVIDER_LAZY_HELP),
        color = EveColors.SecondaryText,
    )
    Text(strings.text(PreferencesText.PROVIDER), style = MaterialTheme.typography.titleSmall)
    Box {
        TextButton(onClick = { providerMenuExpanded = true }, enabled = !busy) {
            Text(providerType.displayName)
        }
        DropdownMenu(
            expanded = providerMenuExpanded,
            onDismissRequest = { providerMenuExpanded = false },
        ) {
            AiProviderType.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = {
                        providerMenuExpanded = false
                        switchProvider(provider)
                    },
                )
            }
        }
    }
    if (providerType.requiresBaseUrl) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(strings.text(PreferencesText.BASE_URL)) },
            placeholder = { Text("https://example.com/v1") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(strings.text(PreferencesText.OFFICIAL_PROVIDER_ENDPOINT, providerType.displayName), color = EveColors.SecondaryText)
    }
    OutlinedTextField(
        value = modelId,
        onValueChange = { modelId = it },
        label = { Text(strings.text(PreferencesText.MODEL)) },
        placeholder = { Text(providerType.modelPlaceholder(strings)) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = apiKeyDraft,
        onValueChange = { apiKeyDraft = it },
        label = { Text(strings.text(if (state.credentialSource == null) PreferencesText.API_KEY else PreferencesText.REPLACE_API_KEY)) },
        placeholder = { Text(strings.text(if (state.credentialSource == null) PreferencesText.ENTER_API_KEY else PreferencesText.LEAVE_BLANK_TO_KEEP_KEY)) },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        when (state.credentialSource) {
            AiCredentialSource.SECURE_STORAGE -> strings.text(PreferencesText.API_KEY_SAVED_SECURELY)
            AiCredentialSource.SESSION_ONLY -> strings.text(PreferencesText.API_KEY_SESSION_ONLY)
            AiCredentialSource.ENVIRONMENT -> strings.text(PreferencesText.CREDENTIAL_ENVIRONMENT_VARIABLE)
            null -> strings.text(PreferencesText.API_KEY_NOT_CONFIGURED)
        },
        color = EveColors.SecondaryText,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = timeout,
            onValueChange = { timeout = it },
            label = { Text(strings.text(PreferencesText.TIMEOUT_SECONDS)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = temperature,
            onValueChange = { temperature = it },
            label = { Text(strings.text(PreferencesText.TEMPERATURE)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    validationError?.let { Text(strings.text(it), color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { buildConfig()?.let { onTest(it, draftSecret(clear = false)) } },
            enabled = !busy,
        ) { Text(strings.text(PreferencesText.TEST_CONNECTION)) }
        TextButton(
            onClick = { buildConfig()?.let { onSave(it, draftSecret(clear = true)) } },
            enabled = !busy,
        ) { Text(strings.text(PreferencesText.SAVE)) }
        TextButton(
            onClick = { onDeleteCredential(providerType) },
            enabled = !busy && state.credentialSource in setOf(
                AiCredentialSource.SECURE_STORAGE,
                AiCredentialSource.SESSION_ONLY,
            ),
        ) { Text(strings.text(PreferencesText.DELETE_API_KEY)) }
        if (busy) CircularProgressIndicator()
    }
    state.testResult?.let { result ->
        HorizontalDivider()
        Text(strings.text(PreferencesText.CONNECTION_TEST), style = MaterialTheme.typography.titleSmall)
        AiConnectionCheckRow(AiConnectionCheckRole.CONNECTION, result.connection)
        AiConnectionCheckRow(AiConnectionCheckRole.MODEL, result.model)
        AiConnectionCheckRow(AiConnectionCheckRole.TOOL_CALLING, result.toolCalling)
    }
    state.message?.let { Text(it.resolve(appStrings), color = EveColors.SecondaryText) }
    state.errorMessage?.let { Text(it.resolve(appStrings), color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun WebSearchPreferencesContent(
    savedConfig: WebSearchConfig,
    state: WebSearchSettingsUiState,
    onViewed: (WebSearchConfig) -> Unit,
    onTest: (WebSearchConfig, SecretValue?) -> Unit,
    onSave: (WebSearchConfig, SecretValue?) -> Unit,
    onDeleteCredential: (WebSearchConfig) -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.preferences
    var enabled by remember(savedConfig) { mutableStateOf(savedConfig.enabled) }
    var apiKeyDraft by remember(savedConfig) { mutableStateOf("") }
    val config = remember(enabled, savedConfig.credentialRef) {
        WebSearchConfig(enabled = enabled, credentialRef = savedConfig.credentialRef)
    }
    val busy = state.isSaving || state.isTesting
    LaunchedEffect(savedConfig) { onViewed(savedConfig) }

    fun draftSecret(clear: Boolean): SecretValue? {
        val normalized = apiKeyDraft.trim()
        if (clear) apiKeyDraft = ""
        return normalized.takeIf(String::isNotEmpty)?.let(SecretValue::from)
    }

    PreferenceCheckbox(strings.text(PreferencesText.ENABLE_WEB_SEARCH), enabled, enabled = !busy) { enabled = it }
    Text(strings.text(PreferencesText.WEB_SEARCH_PROVIDER), style = MaterialTheme.typography.titleSmall)
    Text(
        strings.text(PreferencesText.WEB_SEARCH_HELP),
        color = EveColors.SecondaryText,
    )
    OutlinedTextField(
        value = apiKeyDraft,
        onValueChange = { apiKeyDraft = it },
        label = { Text(strings.text(if (state.credentialSource == null) PreferencesText.BRAVE_API_KEY else PreferencesText.REPLACE_BRAVE_API_KEY)) },
        placeholder = {
            Text(strings.text(if (state.credentialSource == null) PreferencesText.ENTER_BRAVE_API_KEY else PreferencesText.LEAVE_BLANK_TO_KEEP_KEY))
        },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        when (state.credentialSource) {
            AiCredentialSource.SECURE_STORAGE -> strings.text(PreferencesText.BRAVE_API_KEY_SAVED_SECURELY)
            AiCredentialSource.SESSION_ONLY -> strings.text(PreferencesText.BRAVE_API_KEY_SESSION_ONLY)
            AiCredentialSource.ENVIRONMENT -> strings.text(PreferencesText.BRAVE_CREDENTIAL_ENVIRONMENT)
            null -> strings.text(PreferencesText.BRAVE_API_KEY_NOT_CONFIGURED)
        },
        color = EveColors.SecondaryText,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onTest(config, draftSecret(clear = false)) },
            enabled = !busy,
        ) { Text(strings.text(PreferencesText.TEST_SEARCH)) }
        TextButton(
            onClick = { onSave(config, draftSecret(clear = true)) },
            enabled = !busy,
        ) { Text(strings.text(PreferencesText.SAVE)) }
        TextButton(
            onClick = { onDeleteCredential(config) },
            enabled = !busy && state.credentialSource in setOf(
                AiCredentialSource.SECURE_STORAGE,
                AiCredentialSource.SESSION_ONLY,
            ),
        ) { Text(strings.text(PreferencesText.DELETE_API_KEY)) }
        if (busy) CircularProgressIndicator()
    }
    state.testResult?.let { result ->
        HorizontalDivider()
        Text(strings.text(PreferencesText.SEARCH_TEST), style = MaterialTheme.typography.titleSmall)
        SearchCheckRow(strings.text(PreferencesText.CONNECTION), result.connection)
        SearchCheckRow(strings.text(PreferencesText.AUTHENTICATION), result.authentication)
        SearchCheckRow(strings.text(PreferencesText.SEARCH_RESPONSE), result.searchResponse)
        SearchCheckRow(strings.text(PreferencesText.SOURCE_PARSING), result.sourceParsing)
    }
    state.message?.let { Text(it.resolve(appStrings), color = EveColors.SecondaryText) }
    state.errorMessage?.let { Text(it.resolve(appStrings), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun SearchCheckRow(label: String, check: SearchTestCheck) {
    val strings = LocalAppStrings.current.preferences
    val prefix = when (check.status) {
        SearchTestCheckStatus.PASSED -> "✓"
        SearchTestCheckStatus.FAILED -> "✗"
        SearchTestCheckStatus.NOT_RUN -> "–"
    }
    val status = when (check.status) {
        SearchTestCheckStatus.PASSED -> strings.text(PreferencesText.CHECK_PASSED)
        SearchTestCheckStatus.FAILED -> strings.text(PreferencesText.CHECK_FAILED)
        SearchTestCheckStatus.NOT_RUN -> strings.text(PreferencesText.CHECK_NOT_RUN)
    }
    val detail = check.message.takeIf { check.status == SearchTestCheckStatus.FAILED && it.isNotBlank() }
    Text(
        buildString {
            append("$prefix $label: $status")
            if (detail != null) append("\n$detail")
        },
        color = if (check.status == SearchTestCheckStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            EveColors.SecondaryText
        },
    )
}

private fun AiProviderType.modelPlaceholder(strings: PreferencesStrings): String = when (this) {
    AiProviderType.OPENROUTER -> "provider/model-name"
    AiProviderType.OPENAI -> strings.text(PreferencesText.MODEL_ID_PLACEHOLDER, "OpenAI")
    AiProviderType.ANTHROPIC -> strings.text(PreferencesText.MODEL_ID_PLACEHOLDER, "Claude")
    AiProviderType.DEEPSEEK -> strings.text(PreferencesText.MODEL_ID_PLACEHOLDER, "DeepSeek")
    AiProviderType.GOOGLE -> strings.text(PreferencesText.MODEL_ID_PLACEHOLDER, "Gemini")
    AiProviderType.OPENAI_COMPATIBLE -> strings.text(
        PreferencesText.MODEL_ID_PLACEHOLDER,
        strings.text(PreferencesText.PROVIDER),
    )
}

private enum class AiConnectionCheckRole { CONNECTION, MODEL, TOOL_CALLING }

@Composable
private fun AiConnectionCheckRow(role: AiConnectionCheckRole, check: AiConnectionCheck) {
    val strings = LocalAppStrings.current.preferences
    val prefix = when (check.status) {
        AiConnectionCheckStatus.PASSED -> "✓"
        AiConnectionCheckStatus.FAILED -> "✗"
        AiConnectionCheckStatus.NOT_RUN -> "–"
    }
    val localizedMessage = when (check.status) {
        AiConnectionCheckStatus.PASSED -> when (role) {
            AiConnectionCheckRole.CONNECTION -> strings.text(PreferencesText.API_CONNECTION_SUCCESSFUL)
            AiConnectionCheckRole.MODEL -> strings.text(
                PreferencesText.MODEL_VALUE,
                check.message.substringAfter("Model: ", check.message),
            )
            AiConnectionCheckRole.TOOL_CALLING -> strings.text(PreferencesText.TOOL_CALLING_SUPPORTED)
        }
        AiConnectionCheckStatus.NOT_RUN -> when (role) {
            AiConnectionCheckRole.CONNECTION -> strings.text(PreferencesText.CHECK_NOT_RUN)
            AiConnectionCheckRole.MODEL -> strings.text(PreferencesText.MODEL_NOT_TESTED)
            AiConnectionCheckRole.TOOL_CALLING -> strings.text(PreferencesText.TOOL_CALLING_NOT_TESTED)
        }
        AiConnectionCheckStatus.FAILED -> check.message
    }
    Text(
        "$prefix $localizedMessage",
        color = if (check.status == AiConnectionCheckStatus.FAILED) MaterialTheme.colorScheme.error else EveColors.SecondaryText,
    )
}

internal val PREFERENCES_CONTENT_START_GUTTER = 24.dp
internal const val VOICE_INPUT_SECTION_TEST_TAG = "voice-input-section"
internal const val VOICE_OUTPUT_SECTION_TEST_TAG = "voice-output-section"
internal const val VOICE_INPUT_PROVIDER_TEST_TAG = "voice-input-provider"
internal const val VOICE_OUTPUT_PROVIDER_TEST_TAG = "voice-output-provider"
internal const val VOICE_SPEECH_PACK_TEST_TAG = "voice-speech-pack"

@Composable
private fun SharedMapPreferencesContent(
    preferences: SharedMapPreferences,
    state: SharedMapState,
    operationError: UiMessage?,
    adminState: SharedAdminUiState,
    onConnect: (String, SecretValue, String) -> Unit,
    onWorkspaceChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onClearError: () -> Unit,
    onLoadMembers: () -> Unit,
    onCreateMember: (String, SharedWorkspaceRole) -> Boolean,
    onChangeMemberRole: (String, Long, SharedWorkspaceRole) -> Boolean,
    onRemoveMember: (String, Long) -> Boolean,
    onCreateInvite: (String, Long) -> Boolean,
    onClearAdminError: () -> Unit,
    onClearInvite: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.preferences
    val sharedStrings = appStrings.sharedMap
    var serverUrl by remember(preferences.serverUrl) { mutableStateOf(preferences.serverUrl.orEmpty()) }
    var deviceName by remember(preferences.deviceName) { mutableStateOf(preferences.deviceName) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var workspaceMenuExpanded by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    val canConnect = state.connectionState in setOf(
        SharedConnectionState.DISCONNECTED,
        SharedConnectionState.AUTH_REQUIRED,
        SharedConnectionState.FORBIDDEN,
        SharedConnectionState.PROTOCOL_UNSUPPORTED,
    ) || (state.connectionState == SharedConnectionState.OFFLINE && state.selectedWorkspaceId == null)
    val canAdmin = state.connectionState == SharedConnectionState.ONLINE &&
        state.identity?.workspace?.role == SharedWorkspaceRole.ADMIN
    LaunchedEffect(canAdmin, state.selectedWorkspaceId) {
        if (!canAdmin) {
            showMembers = false
            onClearInvite()
        }
    }

    Text(strings.text(PreferencesText.SHARED_MAP), style = MaterialTheme.typography.titleMedium)
    Text(
        strings.text(PreferencesText.SHARED_MAP_HELP),
        color = EveColors.SecondaryText,
    )
    OutlinedTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it },
        label = { Text(strings.text(PreferencesText.SERVER_URL)) },
        placeholder = { Text("https://map.example.com") },
        singleLine = true,
        enabled = canConnect,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = deviceName,
        onValueChange = { if (it.codePointCount(0, it.length) <= 80) deviceName = it },
        label = { Text(strings.text(PreferencesText.DEVICE_NAME)) },
        singleLine = true,
        enabled = canConnect,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(strings.text(PreferencesText.STATUS, sharedMapStatusLabel(state, strings)))
    sharedStrings.statusMessage(state.statusMessage)?.let { Text(it, color = EveColors.SecondaryText) }
    operationError?.let {
        Text(it.resolve(appStrings), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onClearError) { Text(strings.text(PreferencesText.DISMISS)) }
    }

    if (state.workspaces.isNotEmpty()) {
        Text(strings.text(PreferencesText.WORKSPACE), style = MaterialTheme.typography.titleSmall)
        Box {
            TextButton(
                onClick = { workspaceMenuExpanded = true },
                enabled = state.workspaces.size > 1,
            ) {
                Text(state.selectedWorkspace?.name ?: strings.text(PreferencesText.SELECT_WORKSPACE))
            }
            DropdownMenu(
                expanded = workspaceMenuExpanded,
                onDismissRequest = { workspaceMenuExpanded = false },
            ) {
                state.workspaces.forEach { workspace ->
                    DropdownMenuItem(
                        text = { Text(workspace.name) },
                        onClick = {
                            workspaceMenuExpanded = false
                            onWorkspaceChange(workspace.workspaceId)
                        },
                    )
                }
            }
        }
    }
    state.identity?.workspace?.role?.let { role ->
        Text(strings.text(PreferencesText.ROLE, sharedStrings.role(role)))
    }
    Text(strings.text(PreferencesText.LAST_SYNC, state.lastSuccessfulSyncAt?.let(::formatLocalInstant) ?: strings.text(PreferencesText.NEVER)))
    Text(strings.text(PreferencesText.SHARED_MARKERS_COUNT, state.markerCount))
    state.snapshot?.let { Text(strings.text(PreferencesText.REVISION, it.revision), color = EveColors.SecondaryText) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { showInviteDialog = true },
            enabled = canConnect && serverUrl.isNotBlank(),
        ) { Text(strings.text(PreferencesText.CONNECT_WITH_INVITE)) }
        TextButton(
            onClick = onRefresh,
            enabled = state.selectedWorkspaceId != null && state.connectionState in setOf(
                SharedConnectionState.ONLINE,
                SharedConnectionState.DEGRADED,
                SharedConnectionState.OFFLINE,
            ),
        ) { Text(strings.text(PreferencesText.REFRESH_NOW)) }
    }
    TextButton(
        onClick = onDisconnect,
        enabled = state.serverUrl != null && state.connectionState != SharedConnectionState.CONNECTING,
    ) { Text(strings.text(PreferencesText.DISCONNECT)) }
    if (state.identity?.workspace?.role == SharedWorkspaceRole.ADMIN) {
        TextButton(
            onClick = { showMembers = true },
            enabled = canAdmin,
        ) { Text(strings.text(PreferencesText.MANAGE_MEMBERS)) }
        if (!canAdmin) {
            Text(strings.text(PreferencesText.MEMBER_MANAGEMENT_ONLINE_ONLY), color = EveColors.SecondaryText)
        }
    }

    if (showInviteDialog) {
        InviteConnectDialog(
            serverUrl = serverUrl,
            onDismiss = { showInviteDialog = false },
            onSubmit = { inviteText ->
                val secret = runCatching { SecretValue.from(inviteText) }.getOrNull()
                if (secret != null) {
                    try {
                        onConnect(serverUrl, secret, deviceName)
                    } finally {
                        secret.close()
                    }
                }
                showInviteDialog = false
            },
        )
    }
    if (showMembers) {
        SharedMapMembersDialog(
            workspaceName = state.selectedWorkspace?.name ?: strings.text(PreferencesText.SHARED_MAP),
            state = adminState,
            canAdmin = canAdmin,
            onLoad = onLoadMembers,
            onCreateMember = onCreateMember,
            onChangeRole = onChangeMemberRole,
            onRemoveMember = onRemoveMember,
            onCreateInvite = onCreateInvite,
            onClearError = onClearAdminError,
            onClearInvite = onClearInvite,
            onDismiss = {
                showMembers = false
                onClearInvite()
            },
        )
    }
}

@Composable
private fun InviteConnectDialog(
    serverUrl: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val strings = LocalAppStrings.current.preferences
    var invite by remember { mutableStateOf("") }
    fun clearAndDismiss() {
        invite = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::clearAndDismiss,
        title = { Text(strings.text(PreferencesText.CONNECT_TO_SHARED_MAP)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(serverUrl, color = EveColors.SecondaryText)
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it },
                    label = { Text(strings.text(PreferencesText.ONE_TIME_INVITE)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(strings.text(PreferencesText.INVITE_NOT_SAVED), color = EveColors.SecondaryText)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val submitted = invite
                    invite = ""
                    onSubmit(submitted)
                },
                enabled = invite.isNotBlank(),
            ) { Text(strings.text(PreferencesText.CONNECT)) }
        },
        dismissButton = { TextButton(onClick = ::clearAndDismiss) { Text(LocalAppStrings.current.common.cancel) } },
    )
}

internal fun sharedMapStatusLabel(state: SharedConnectionState): String =
    sharedMapStatusLabel(state, AppStringsCatalog.forLocale(AppLocale.EN_US).preferences)

internal fun sharedMapStatusLabel(state: SharedConnectionState, strings: PreferencesStrings): String = when (state) {
    SharedConnectionState.DISCONNECTED -> strings.text(PreferencesText.SHARED_NOT_CONFIGURED)
    SharedConnectionState.CONNECTING -> strings.text(PreferencesText.SHARED_CONNECTING)
    SharedConnectionState.ONLINE -> strings.text(PreferencesText.SHARED_CONNECTED)
    SharedConnectionState.DEGRADED -> strings.text(PreferencesText.SHARED_DEGRADED)
    SharedConnectionState.OFFLINE -> strings.text(PreferencesText.SHARED_OFFLINE)
    SharedConnectionState.AUTH_REQUIRED -> strings.text(PreferencesText.SHARED_AUTH_REQUIRED)
    SharedConnectionState.FORBIDDEN -> strings.text(PreferencesText.SHARED_ACCESS_REMOVED)
    SharedConnectionState.PROTOCOL_UNSUPPORTED -> strings.text(PreferencesText.SHARED_INCOMPATIBLE_SERVER)
}

internal fun sharedMapStatusLabel(state: SharedMapState): String =
    sharedMapStatusLabel(state, AppStringsCatalog.forLocale(AppLocale.EN_US).preferences)

internal fun sharedMapStatusLabel(state: SharedMapState, strings: PreferencesStrings): String =
    if (state.connectionState == SharedConnectionState.DISCONNECTED && state.serverUrl != null) {
        strings.text(PreferencesText.SHARED_DISCONNECTED)
    } else {
        sharedMapStatusLabel(state.connectionState, strings)
    }

private val SHARED_MAP_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

private fun formatLocalInstant(instant: java.time.Instant): String =
    SHARED_MAP_TIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))

@Composable
private fun OverlayPreferencesContent(
    overlayState: OverlayState,
    sovereigntyAvailable: Boolean,
    preferences: OverlayVisibilityPreferences,
    mapDisplay: MapDisplayPreferences,
    onChange: (OverlayVisibilityPreferences) -> Unit,
    onMapDisplayChange: (MapDisplayPreferences) -> Unit,
    onReset: () -> Unit,
) {
    val strings = LocalAppStrings.current.preferences
    val uiState = remember(overlayState, sovereigntyAvailable, preferences) {
        OverlayManagementUiStateBuilder.build(overlayState, preferences, sovereigntyAvailable)
    }

    Text(strings.text(PreferencesText.MAP_OVERLAYS), style = MaterialTheme.typography.titleMedium)
    Text(
        strings.text(PreferencesText.OVERLAY_VISIBILITY_HELP),
        color = EveColors.SecondaryText,
    )
    if (uiState.overlays.isEmpty()) {
        Text(strings.text(PreferencesText.NO_FEATURE_PACK_OVERLAYS), color = EveColors.SecondaryText)
    }
    uiState.overlays.forEach { item ->
        HorizontalDivider()
        PreferenceCheckbox(item.name, item.enabled) { enabled ->
            onChange(preferences.withEnabled(item.key, enabled))
        }
        Text(strings.text(PreferencesText.SOURCE, item.providerName), color = EveColors.SecondaryText)
        item.description?.let { Text(it, color = EveColors.SecondaryText) }
        item.providerDescription?.let { Text(strings.text(PreferencesText.PROVIDER_DESCRIPTION, it), color = EveColors.DisabledText) }
    }
    TextButton(onClick = onReset, enabled = preferences.disabledLayers.isNotEmpty()) {
        Text(strings.text(PreferencesText.ENABLE_ALL_OVERLAYS))
    }
    HorizontalDivider()
    Text(strings.text(PreferencesText.REAL_3D_STARGATE_VISIBILITY), style = MaterialTheme.typography.titleSmall)
    Text(
        strings.text(PreferencesText.REAL_3D_STARGATE_VISIBILITY_HELP),
        color = EveColors.SecondaryText,
    )
    PreferenceCheckbox(
        strings.text(PreferencesText.FOCUSED_AND_ADJACENT_REGIONS),
        mapDisplay.real3DStargateVisibilityFilteringEnabled,
    ) { enabled ->
        onMapDisplayChange(mapDisplay.copy(real3DStargateVisibilityFilteringEnabled = enabled))
    }
    if (uiState.showSovereigntyLogoPreferences) {
        HorizontalDivider()
        Text(strings.text(PreferencesText.SOVEREIGNTY), style = MaterialTheme.typography.titleSmall)
        Text(strings.text(PreferencesText.SOVEREIGNTY_LOGO_EMPHASIS_ZOOM), style = MaterialTheme.typography.bodyMedium)
        Text(
            strings.text(PreferencesText.SOVEREIGNTY_LOGO_HELP),
            color = EveColors.SecondaryText,
        )
        SovereigntyLogoEmphasisZoomPreference(mapDisplay.sovereigntyLogoEmphasisZoom) { emphasisZoom ->
            onMapDisplayChange(mapDisplay.copy(sovereigntyLogoEmphasisZoom = emphasisZoom))
        }
    }
}

@Composable
private fun SovereigntyLogoEmphasisZoomPreference(
    value: Double,
    onValueChange: (Double) -> Unit,
) {
    val strings = LocalAppStrings.current.preferences
    var draft by remember { mutableStateOf(formatValue(value, 2)) }
    var showError by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value, isFocused, showError) {
        if (!isFocused && !showError) {
            draft = formatValue(value, 2)
        }
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text
            val parsed = text.toDoubleOrNull()
            val valid = parsed != null && parsed.isFinite() &&
                parsed in MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM..MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM
            showError = false
            if (valid) onValueChange(checkNotNull(parsed))
        },
        suffix = { Text("x") },
        supportingText = if (showError) ({
            Text(
                strings.text(
                    PreferencesText.ENTER_RANGE,
                    formatValue(MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM, 2),
                    formatValue(MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM, 2),
                ),
            )
        }) else null,
        singleLine = true,
        isError = showError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.width(160.dp).onFocusChanged { focusState ->
            val wasFocused = isFocused
            isFocused = focusState.isFocused
            if (!wasFocused && focusState.isFocused) {
                showError = false
            } else if (wasFocused && !focusState.isFocused) {
                val parsed = draft.toDoubleOrNull()
                val valid = parsed != null && parsed.isFinite() &&
                    parsed in MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM..MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM
                showError = !valid
                if (valid) draft = formatValue(checkNotNull(parsed), 2)
            }
        },
    )
    Text(
        strings.text(PreferencesText.FULL_MAP_RANGE_HELP),
        color = EveColors.DisabledText,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun FeaturePacksPreferencesContent(viewModel: FeaturePackManagerViewModel) {
    val strings = LocalAppStrings.current.preferences
    val state by viewModel.state.collectAsState()
    val controls by viewModel.controlsState.collectAsState()
    val identityState by viewModel.identityState.collectAsState()
    var removePending by remember { mutableStateOf<FeaturePackManagerItem?>(null) }
    LaunchedEffect(viewModel) { viewModel.refresh() }

    Text(strings.text(PreferencesText.FEATURE_PACKS), style = MaterialTheme.typography.titleMedium)
    Text(
        strings.text(PreferencesText.FEATURE_PACKS_HELP),
        color = EveColors.SecondaryText,
    )
    if (identityState.providerAvailable) {
        HorizontalDivider()
        Text(strings.text(PreferencesText.CURRENT_EVE_IDENTITY), style = MaterialTheme.typography.titleSmall)
        Text(
            strings.text(PreferencesText.CURRENT_EVE_IDENTITY_HELP),
            color = EveColors.SecondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        identityState.errors.forEach { Text(it, color = EveColors.Error) }
        val unavailableSelectedId = identityState.selectedCharacterId?.takeIf { selectedId ->
            identityState.identities.none { it.character.id == selectedId }
        }
        if (identityState.refreshing && unavailableSelectedId == null) {
            Text(strings.text(PreferencesText.REFRESHING_EVE_IDENTITIES), color = EveColors.SecondaryText)
        }
        if (unavailableSelectedId != null) {
            Text(strings.text(PreferencesText.ID, unavailableSelectedId), style = MaterialTheme.typography.titleSmall)
            Text(
                strings.text(
                    if (identityState.refreshing) {
                        PreferencesText.REFRESHING_EVE_IDENTITIES
                    } else {
                        PreferencesText.UNAVAILABLE
                    },
                ),
                color = EveColors.SecondaryText,
            )
        }
        if (identityState.identities.isEmpty() && !identityState.refreshing) {
            Text(strings.text(PreferencesText.NO_EVE_IDENTITIES), color = EveColors.SecondaryText)
        }
        identityState.identities.forEach { identity ->
            val selected = identity.character.id == identityState.selectedCharacterId
            Text(identity.character.name, style = MaterialTheme.typography.titleSmall)
            Text(
                strings.text(
                    PreferencesText.EVE_IDENTITY_CORPORATION,
                    identity.corporation.name,
                    identity.corporation.ticker,
                ),
                color = EveColors.SecondaryText,
            )
            Text(
                identity.alliance?.let { alliance ->
                    strings.text(PreferencesText.EVE_IDENTITY_ALLIANCE, alliance.name, alliance.ticker)
                } ?: strings.text(PreferencesText.EVE_IDENTITY_NO_ALLIANCE),
                color = EveColors.SecondaryText,
            )
            TextButton(
                enabled = !selected,
                onClick = { viewModel.selectIdentity(identity.character.id) },
            ) {
                Text(
                    strings.text(
                        if (selected) PreferencesText.SELECTED_EVE_IDENTITY else PreferencesText.SELECT_EVE_IDENTITY,
                    ),
                )
            }
        }
        TextButton(
            enabled = !identityState.refreshing,
            onClick = { viewModel.refreshIdentities() },
        ) { Text(strings.text(PreferencesText.REFRESH_EVE_IDENTITIES)) }
    }
    state.discoveryErrors.forEach { Text(it, color = EveColors.Error) }
    if (state.initialized && state.packs.isEmpty() && state.discoveryErrors.isEmpty()) {
        Text(strings.text(PreferencesText.NO_FEATURE_PACKS), color = EveColors.SecondaryText)
    }
    state.packs.forEach { item ->
        val pack = item.pack
        HorizontalDivider()
        Text(pack.displayName, style = MaterialTheme.typography.titleSmall)
        Text(strings.text(PreferencesText.ID, pack.packId.value), color = EveColors.SecondaryText)
        Text(strings.text(PreferencesText.VERSION, pack.version?.value ?: strings.text(PreferencesText.UNAVAILABLE)))
        Text(strings.text(PreferencesText.PUBLISHER, pack.publisher ?: strings.text(PreferencesText.UNAVAILABLE)))
        Text(strings.text(PreferencesText.PATH, pack.path), color = EveColors.SecondaryText)
        Text(
            strings.text(
                PreferencesText.STATUS,
                strings.text(
                    when {
                        pack.installationState == FeaturePackInstallationState.MISSING_JAR -> PreferencesText.MISSING_PACK_JAR
                        pack.installationState == FeaturePackInstallationState.INVALID_PACK -> PreferencesText.INVALID_PACK
                        pack.installationState == FeaturePackInstallationState.INCOMPATIBLE -> PreferencesText.INCOMPATIBLE
                        item.runtimeState == FeaturePackRuntimeState.ENABLED -> PreferencesText.ENABLED
                        else -> PreferencesText.DISABLED
                    },
                ),
            ),
        )
        pack.lastError?.let { Text(it, color = EveColors.Error) }
        controls.firstOrNull { it.packId == pack.packId }?.let { control ->
            Text(strings.text(PreferencesText.CONTROLS), style = MaterialTheme.typography.titleSmall)
            Text(
                localizedPackControlText(control.primaryText, strings),
                color = when (control.severity) {
                    PackControlSeverity.NORMAL -> EveColors.PrimaryText
                    PackControlSeverity.WARNING -> EveColors.Warning
                    PackControlSeverity.ERROR -> EveColors.Error
                },
            )
            control.secondaryText?.let { secondary ->
                Text(localizedPackControlText(secondary, strings), color = EveColors.SecondaryText)
            }
            PackControlActionList(control.actions, control.busyActionId, viewModel::invokeControl)
            control.actions.mapNotNull { it.description }.distinct().forEach { description ->
                Text(description, color = EveColors.DisabledText, style = MaterialTheme.typography.bodySmall)
            }
            control.lastMessage?.let { message ->
                Text(
                    message,
                    color = if (control.lastStatus == PackControlActionStatus.FAILED) {
                        EveColors.Error
                    } else {
                        EveColors.SecondaryText
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.runtimeState == FeaturePackRuntimeState.ENABLED) {
                TextButton(onClick = { viewModel.setEnabled(pack.packId, false) }) { Text(strings.text(PreferencesText.DISABLE)) }
            } else {
                TextButton(
                    enabled = pack.installationState == FeaturePackInstallationState.INSTALLED,
                    onClick = { viewModel.setEnabled(pack.packId, true) },
                ) { Text(strings.text(PreferencesText.ENABLE)) }
            }
            TextButton(onClick = { removePending = item }) { Text(strings.text(PreferencesText.REMOVE)) }
        }
    }

    removePending?.let { item ->
        AlertDialog(
            onDismissRequest = { removePending = null },
            title = { Text(strings.text(PreferencesText.REMOVE_PACK_TITLE, item.pack.displayName)) },
            text = { Text(strings.text(PreferencesText.REMOVE_PACK_HELP)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(item.pack.packId)
                    removePending = null
                }) { Text(strings.text(PreferencesText.REMOVE)) }
            },
            dismissButton = { TextButton(onClick = { removePending = null }) { Text(LocalAppStrings.current.common.cancel) } },
        )
    }
}

@Composable
internal fun PackControlActionList(
    actions: List<PackControlActionUiState>,
    busyActionId: String?,
    onInvoke: (PackControlActionKey) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        actions.forEach { action ->
            TextButton(
                enabled = action.enabled,
                onClick = { onInvoke(action.key) },
            ) {
                Text(if (busyActionId == action.key.actionId) "${action.label}…" else action.label)
            }
        }
    }
}

@Composable
private fun MapDisplayPreferencesContent(
    currentZoom: Double?,
    mapDisplay: MapDisplayPreferences,
    onChange: (MapDisplayPreferences) -> Unit,
    onReset: () -> Unit,
) {
    val strings = LocalAppStrings.current.preferences
    Text(strings.text(PreferencesText.MAP_DISPLAY), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(strings.text(PreferencesText.CURRENT_ZOOM), color = EveColors.SecondaryText)
        Text(currentZoom?.let { formatValue(it, 2) + "x" } ?: "—")
    }
    NumericPreferenceSlider(
        strings.text(PreferencesText.CONSTELLATION_2D_ZOOM_THRESHOLD),
        mapDisplay.constellationZoomThreshold,
        "x",
        2,
        THRESHOLD_MIN..(mapDisplay.systemZoomThreshold - THRESHOLD_MIN_GAP).coerceAtLeast(THRESHOLD_MIN),
        isValid = { it >= THRESHOLD_MIN && it < mapDisplay.systemZoomThreshold },
    ) { onChange(mapDisplay.copy(constellationZoomThreshold = it)) }
    NumericPreferenceSlider(
        strings.text(PreferencesText.SYSTEM_2D_ZOOM_THRESHOLD),
        mapDisplay.systemZoomThreshold,
        "x",
        2,
        (mapDisplay.constellationZoomThreshold + THRESHOLD_MIN_GAP).coerceAtMost(THRESHOLD_MAX)..THRESHOLD_MAX,
        isValid = { it > mapDisplay.constellationZoomThreshold && it <= THRESHOLD_MAX },
    ) { onChange(mapDisplay.copy(systemZoomThreshold = it)) }
    NumericPreferenceSlider(
        strings.text(PreferencesText.CONSTELLATION_3D_ZOOM_THRESHOLD),
        mapDisplay.real3DConstellationScaleThreshold,
        "x",
        2,
        THRESHOLD_MIN..(mapDisplay.real3DSystemScaleThreshold - THRESHOLD_MIN_GAP).coerceAtLeast(THRESHOLD_MIN),
        isValid = { it >= THRESHOLD_MIN && it < mapDisplay.real3DSystemScaleThreshold },
    ) { onChange(mapDisplay.copy(real3DConstellationScaleThreshold = it)) }
    NumericPreferenceSlider(
        strings.text(PreferencesText.SYSTEM_3D_ZOOM_THRESHOLD),
        mapDisplay.real3DSystemScaleThreshold,
        "x",
        2,
        (mapDisplay.real3DConstellationScaleThreshold + THRESHOLD_MIN_GAP).coerceAtMost(THRESHOLD_MAX)..THRESHOLD_MAX,
        isValid = { it > mapDisplay.real3DConstellationScaleThreshold && it <= THRESHOLD_MAX },
    ) { onChange(mapDisplay.copy(real3DSystemScaleThreshold = it)) }
    HorizontalDivider()
    FontPreferenceSliders(mapDisplay, onChange)
    TextButton(onClick = onReset) { Text(strings.text(PreferencesText.RESET_MAP_DISPLAY)) }
}

@Composable
private fun FontPreferenceSliders(mapDisplay: MapDisplayPreferences, onChange: (MapDisplayPreferences) -> Unit) {
    val strings = LocalAppStrings.current.preferences
    NumericPreferenceSlider(strings.text(PreferencesText.REGION_PRIMARY_FONT_SIZE), mapDisplay.regionPrimaryFontSizeSp.toDouble(), "sp", 0,
        FONT_SIZE_MIN..FONT_SIZE_MAX, FONT_SIZE_STEPS, ::validFontSize) {
        onChange(mapDisplay.copy(regionPrimaryFontSizeSp = it.toFloat()))
    }
    NumericPreferenceSlider(strings.text(PreferencesText.REGION_BACKGROUND_FONT_SIZE), mapDisplay.regionBackgroundFontSizeSp.toDouble(), "sp", 0,
        FONT_SIZE_MIN..FONT_SIZE_MAX, FONT_SIZE_STEPS, ::validFontSize) {
        onChange(mapDisplay.copy(regionBackgroundFontSizeSp = it.toFloat()))
    }
    NumericPreferenceSlider(strings.text(PreferencesText.REGION_BACKGROUND_ALPHA), mapDisplay.regionBackgroundAlpha.toDouble(), decimals = 2,
        sliderRange = BACKGROUND_ALPHA_MIN..BACKGROUND_ALPHA_MAX, steps = BACKGROUND_ALPHA_STEPS,
        isValid = { it in BACKGROUND_ALPHA_MIN..BACKGROUND_ALPHA_MAX }) {
        onChange(mapDisplay.copy(regionBackgroundAlpha = it.toFloat()))
    }
    NumericPreferenceSlider(strings.text(PreferencesText.CONSTELLATION_FONT_SIZE), mapDisplay.constellationFontSizeSp.toDouble(), "sp", 0,
        FONT_SIZE_MIN..FONT_SIZE_MAX, FONT_SIZE_STEPS, ::validFontSize) {
        onChange(mapDisplay.copy(constellationFontSizeSp = it.toFloat()))
    }
    NumericPreferenceSlider(strings.text(PreferencesText.SYSTEM_FONT_SIZE), mapDisplay.systemFontSizeSp.toDouble(), "sp", 0,
        FONT_SIZE_MIN..FONT_SIZE_MAX, FONT_SIZE_STEPS, ::validFontSize) {
        onChange(mapDisplay.copy(systemFontSizeSp = it.toFloat()))
    }
}

@Composable
internal fun MarkerPreferencesContent(
    preferences: MarkerPreferences,
    onChange: (MarkerPreferences) -> Unit,
    onReset: () -> Unit,
) {
    val strings = LocalAppStrings.current.preferences
    Text(strings.text(PreferencesText.MARKER_SETTINGS), style = MaterialTheme.typography.titleMedium)
    PreferenceCheckbox(strings.text(PreferencesText.SHOW_LOCAL_MARKERS), preferences.showMarkers) {
        onChange(preferences.copy(showMarkers = it))
    }
    PreferenceCheckbox(strings.text(PreferencesText.SHOW_SHARED_MARKERS), preferences.showSharedMarkers) {
        onChange(preferences.copy(showSharedMarkers = it))
    }
    PreferenceCheckbox(strings.text(PreferencesText.SHOW_LOCAL_MARKER_NAMES), preferences.showMarkerNames, preferences.showMarkers) {
        onChange(preferences.copy(showMarkerNames = it))
    }
    HorizontalDivider()
    Text(strings.text(PreferencesText.SAVED_MARKER_APPEARANCE), style = MaterialTheme.typography.titleSmall)
    val appearance = preferences.savedMarkerAppearance
    NumericPreferenceSlider(
        label = strings.text(PreferencesText.OUTER_RING_RADIUS),
        value = appearance.ringRadiusDp.toDouble(),
        suffix = "dp",
        decimals = 1,
        sliderRange = MIN_SAVED_MARKER_RING_RADIUS_DP.toDouble()..MAX_SAVED_MARKER_RING_RADIUS_DP.toDouble(),
        steps = 39,
        isValid = { it in MIN_SAVED_MARKER_RING_RADIUS_DP.toDouble()..MAX_SAVED_MARKER_RING_RADIUS_DP.toDouble() },
    ) { onChange(preferences.copy(savedMarkerAppearance = appearance.copy(ringRadiusDp = it.toFloat()))) }
    NumericPreferenceSlider(
        label = strings.text(PreferencesText.OUTER_RING_LINE_WIDTH),
        value = appearance.lineWidthDp.toDouble(),
        suffix = "dp",
        decimals = 1,
        sliderRange = MIN_SAVED_MARKER_LINE_WIDTH_DP.toDouble()..MAX_SAVED_MARKER_LINE_WIDTH_DP.toDouble(),
        steps = 39,
        isValid = { it in MIN_SAVED_MARKER_LINE_WIDTH_DP.toDouble()..MAX_SAVED_MARKER_LINE_WIDTH_DP.toDouble() },
    ) { onChange(preferences.copy(savedMarkerAppearance = appearance.copy(lineWidthDp = it.toFloat()))) }
    PreferenceCheckbox(strings.text(PreferencesText.GLOW), appearance.glowEnabled) {
        onChange(preferences.copy(savedMarkerAppearance = appearance.copy(glowEnabled = it)))
    }
    NumericPreferenceSlider(
        label = strings.text(PreferencesText.GLOW_STRENGTH),
        value = appearance.glowStrength.toDouble(),
        decimals = 2,
        sliderRange = MIN_SAVED_MARKER_GLOW_STRENGTH.toDouble()..MAX_SAVED_MARKER_GLOW_STRENGTH.toDouble(),
        steps = 19,
        isValid = { it in MIN_SAVED_MARKER_GLOW_STRENGTH.toDouble()..MAX_SAVED_MARKER_GLOW_STRENGTH.toDouble() },
        enabled = appearance.glowEnabled,
    ) { onChange(preferences.copy(savedMarkerAppearance = appearance.copy(glowStrength = it.toFloat()))) }
    TextButton(onClick = onReset) { Text(strings.text(PreferencesText.RESET_MARKER)) }
}

private fun localizedPackControlText(text: String, strings: dev.evestaticmapplanner.localization.PreferencesStrings): String =
    when (text) {
        "Feature Pack controls unavailable" -> strings.text(PreferencesText.FEATURE_PACK_CONTROLS_UNAVAILABLE)
        "This Pack could not provide its current status." -> strings.text(PreferencesText.FEATURE_PACK_STATUS_UNAVAILABLE)
        else -> text
    }

@Composable
private fun AiControlPreferencesContent(
    preferences: AiControlPreferences,
    status: AiControlStatus,
    preferenceError: UiMessage?,
    onChange: (Boolean) -> Unit,
    onSavedMarkerAccessChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val strings = LocalAppStrings.current.preferences
    Text(strings.text(PreferencesText.MCP_SERVER_PERMISSIONS), style = MaterialTheme.typography.titleMedium)
    PreferenceCheckbox(strings.text(PreferencesText.ENABLE_MCP_INTEGRATION), preferences.enabled, onCheckedChange = onChange)
    PreferenceCheckbox(
        strings.text(PreferencesText.ALLOW_AI_SAVED_MARKERS),
        preferences.savedMarkerAccessEnabled,
        onCheckedChange = onSavedMarkerAccessChange,
    )
    Text(
        strings.text(PreferencesText.AI_SAVED_MARKERS_HELP),
        color = EveColors.SecondaryText,
    )
    Text(
        when (status) {
            AiControlStatus.Disabled -> strings.text(PreferencesText.DISABLED)
            AiControlStatus.Starting -> strings.text(PreferencesText.MCP_STARTING)
            AiControlStatus.Listening -> strings.text(PreferencesText.MCP_LISTENING)
            AiControlStatus.AlreadyActive -> strings.text(PreferencesText.MCP_ALREADY_ACTIVE)
            is AiControlStatus.Error -> status.message
        },
        color = when (status) {
            is AiControlStatus.Error, AiControlStatus.AlreadyActive -> EveColors.Error
            else -> EveColors.SecondaryText
        },
    )
    if (preferenceError != null) Text(preferenceError.resolve(LocalAppStrings.current), color = EveColors.Error)
    Text(
        strings.text(PreferencesText.MCP_SESSION_HELP),
        color = EveColors.SecondaryText,
    )
    TextButton(onClick = onReset) { Text(strings.text(PreferencesText.RESET_MCP_INTEGRATION)) }
}

@Composable
private fun PreferenceCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(label, color = if (enabled) EveColors.PrimaryText else EveColors.DisabledText)
    }
}

@Composable
private fun NumericPreferenceSlider(
    label: String,
    value: Double,
    suffix: String = "",
    decimals: Int,
    sliderRange: ClosedFloatingPointRange<Double>,
    steps: Int = 0,
    isValid: (Double) -> Boolean,
    enabled: Boolean = true,
    onValueChange: (Double) -> Unit,
) {
    var draft by remember { mutableStateOf(formatValue(value, decimals)) }
    var invalid by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(value, decimals, isFocused) {
        if (!isFocused) {
            draft = formatValue(value, decimals)
            invalid = false
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) EveColors.PrimaryText else EveColors.DisabledText,
            )
            Slider(
                value = value.coerceIn(sliderRange).toFloat(),
                onValueChange = { onValueChange(it.toDouble()) },
                valueRange = sliderRange.start.toFloat()..sliderRange.endInclusive.toFloat(),
                steps = steps,
                enabled = enabled,
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { text ->
                draft = text
                val parsed = text.toDoubleOrNull()
                invalid = parsed == null || !isValid(parsed)
                if (!invalid) onValueChange(checkNotNull(parsed))
            },
            suffix = if (suffix.isEmpty()) null else ({ Text(suffix) }),
            singleLine = true,
            isError = invalid,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(104.dp).onFocusChanged { focusState ->
                val wasFocused = isFocused
                isFocused = focusState.isFocused
                if (wasFocused && !focusState.isFocused) {
                    draft = formatValue(value, decimals)
                    invalid = false
                }
            },
        )
    }
}

private fun validFontSize(value: Double): Boolean = value in FONT_SIZE_MIN..FONT_SIZE_MAX
private fun formatValue(value: Double, decimals: Int): String =
    String.format(Locale.ROOT, "%.${decimals}f", value)

private const val THRESHOLD_MIN = 0.1
private const val THRESHOLD_MAX = MAX_ZOOM_THRESHOLD
private const val THRESHOLD_MIN_GAP = 0.1
private const val FONT_SIZE_MIN = 8.0
private const val FONT_SIZE_MAX = 72.0
private const val FONT_SIZE_STEPS = 63
private const val BACKGROUND_ALPHA_MIN = 0.0
private const val BACKGROUND_ALPHA_MAX = 1.0
private const val BACKGROUND_ALPHA_STEPS = 99
