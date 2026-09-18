package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiCredentialStore
import dev.evestaticmapplanner.embeddedai.OPENAI_VOICE_ENVIRONMENT_VARIABLE
import dev.evestaticmapplanner.embeddedai.OPENAI_VOICE_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.ALIBABA_SPEECH_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.ALIBABA_SPEECH_ENVIRONMENT_VARIABLE
import dev.evestaticmapplanner.embeddedai.SpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.embeddedai.VoiceException
import dev.evestaticmapplanner.localization.PreferencesMessage
import dev.evestaticmapplanner.localization.PreferencesUiMessage
import dev.evestaticmapplanner.localization.UiMessage
import dev.evestaticmapplanner.localization.VoiceFailureUiMessage
import dev.evestaticmapplanner.embeddedai.recognitionConfig
import dev.evestaticmapplanner.embeddedai.synthesisConfig
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class VoiceSettingsUiState(
    val openAiCredentialSource: AiCredentialSource? = null,
    val alibabaCredentialSource: AiCredentialSource? = null,
    val speechPack: SpeechPackState = SpeechPackState(false),
    val windowsVoices: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val isInstallingSpeechPack: Boolean = false,
    val isTestingRecognition: Boolean = false,
    val isTestingVoice: Boolean = false,
    val message: UiMessage? = null,
    val errorMessage: UiMessage? = null,
) {
    val busy: Boolean get() = isSaving || isInstallingSpeechPack || isTestingRecognition || isTestingVoice
}

internal enum class VoiceCredentialProvider { OPENAI, ALIBABA }

internal class VoiceSettingsController(
    private val secureStore: AiCredentialStore,
    private val sessionStore: AiCredentialStore,
    private val credentialResolver: AiCredentialResolver,
    private val speechPackManager: SpeechPackManager,
    private val localSynthesizer: SpeechSynthesizer,
    private val providerFactory: SpeechProviderFactory,
    private val audioPlayer: VoiceAudioPlayer,
    private val persistConfig: suspend (VoiceConfig) -> Result<Unit>,
    private val onConfigSaved: () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(
        VoiceSettingsUiState(speechPack = speechPackManager.state()),
    )
    val state: StateFlow<VoiceSettingsUiState> = mutableState.asStateFlow()

    fun refresh(config: VoiceConfig) {
        if (closed.get()) return
        mutableState.value = mutableState.value.copy(
            openAiCredentialSource = credentialResolver.source(
                config.profiles.openAi.credentialRef,
                OPENAI_VOICE_ENVIRONMENT_VARIABLE,
            ),
            alibabaCredentialSource = credentialResolver.source(
                config.profiles.alibaba.credentialRef,
                ALIBABA_SPEECH_ENVIRONMENT_VARIABLE,
            ),
            speechPack = speechPackManager.state(),
            message = null,
            errorMessage = null,
        )
        if (mutableState.value.windowsVoices.isEmpty()) {
            scope.launch {
                val voices = runCatching { localSynthesizer.voices() }.getOrDefault(emptyList())
                mutableState.value = mutableState.value.copy(windowsVoices = voices)
            }
        }
    }

    fun save(config: VoiceConfig, openAiReplacement: SecretValue?, alibabaReplacement: SecretValue?) {
        if (closed.get() || mutableState.value.busy) {
            openAiReplacement?.close()
            alibabaReplacement?.close()
            return
        }
        mutableState.value = mutableState.value.copy(isSaving = true, message = null, errorMessage = null)
        scope.launch {
            val references = listOf(config.profiles.openAi.credentialRef, config.profiles.alibaba.credentialRef)
            val secureBefore = references.associateWith { snapshot(secureStore, it) }
            val sessionBefore = references.associateWith { snapshot(sessionStore, it) }
            try {
                var sessionOnly = false
                sessionOnly = saveReplacement(
                    config.profiles.openAi.credentialRef,
                    openAiReplacement,
                    secureBefore,
                    sessionBefore,
                ) || sessionOnly
                sessionOnly = saveReplacement(
                    config.profiles.alibaba.credentialRef,
                    alibabaReplacement,
                    secureBefore,
                    sessionBefore,
                ) || sessionOnly
                persistConfig(config).getOrThrow()
                onConfigSaved()
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    openAiCredentialSource = credentialResolver.source(
                        config.profiles.openAi.credentialRef,
                        OPENAI_VOICE_ENVIRONMENT_VARIABLE,
                    ),
                    alibabaCredentialSource = credentialResolver.source(
                        config.profiles.alibaba.credentialRef,
                        ALIBABA_SPEECH_ENVIRONMENT_VARIABLE,
                    ),
                    message = PreferencesUiMessage(
                        if (sessionOnly) {
                            PreferencesMessage.VOICE_SETTINGS_SAVED_SESSION_ONLY
                        } else {
                            PreferencesMessage.VOICE_SETTINGS_SAVED
                        },
                    ),
                )
            } catch (_: Throwable) {
                references.forEach { reference ->
                    restore(secureStore, reference, secureBefore[reference])
                    restore(sessionStore, reference, sessionBefore[reference])
                }
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = PreferencesUiMessage(PreferencesMessage.VOICE_SETTINGS_UPDATE_FAILED),
                )
            } finally {
                secureBefore.values.forEach { it?.close() }
                sessionBefore.values.forEach { it?.close() }
                openAiReplacement?.close()
                alibabaReplacement?.close()
            }
        }
    }

    fun deleteCredential(provider: VoiceCredentialProvider) {
        if (closed.get() || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(isSaving = true, message = null, errorMessage = null)
        scope.launch {
            val reference = when (provider) {
                VoiceCredentialProvider.OPENAI -> OPENAI_VOICE_CREDENTIAL_REF
                VoiceCredentialProvider.ALIBABA -> ALIBABA_SPEECH_CREDENTIAL_REF
            }
            val environment = when (provider) {
                VoiceCredentialProvider.OPENAI -> OPENAI_VOICE_ENVIRONMENT_VARIABLE
                VoiceCredentialProvider.ALIBABA -> ALIBABA_SPEECH_ENVIRONMENT_VARIABLE
            }
            val label = if (provider == VoiceCredentialProvider.OPENAI) "OpenAI Voice" else "Alibaba Speech"
            try {
                secureStore.delete(reference)
                sessionStore.delete(reference)
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    openAiCredentialSource = if (provider == VoiceCredentialProvider.OPENAI) {
                        credentialResolver.source(reference, environment)
                    } else mutableState.value.openAiCredentialSource,
                    alibabaCredentialSource = if (provider == VoiceCredentialProvider.ALIBABA) {
                        credentialResolver.source(reference, environment)
                    } else mutableState.value.alibabaCredentialSource,
                    message = PreferencesUiMessage(PreferencesMessage.VOICE_API_KEY_DELETED, label),
                )
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = PreferencesUiMessage(PreferencesMessage.VOICE_API_KEY_DELETE_FAILED, label),
                )
            }
        }
    }

    fun testRecognition(config: VoiceConfig) {
        if (closed.get() || mutableState.value.busy || config.inputProvider == VoiceInputProvider.OFF) return
        mutableState.value = mutableState.value.copy(
            isTestingRecognition = true,
            message = PreferencesUiMessage(PreferencesMessage.TESTING_RECOGNITION),
            errorMessage = null,
        )
        scope.launch {
            try {
                val fixture = providerFactory.textToSpeech(VoiceOutputProvider.LOCAL)
                    ?.synthesize(RECOGNITION_TEST_TEXT, SpeechSynthesisConfig())
                    ?: error("Windows speech fixture is unavailable")
                val provider = providerFactory.speechToText(config.inputProvider)
                    ?: error("Speech recognition provider is unavailable")
                val transcript = provider.transcribe(
                    dev.evestaticmapplanner.embeddedai.RecordedAudio(fixture.wav),
                    config.recognitionConfig(),
                ).text
                mutableState.value = mutableState.value.copy(
                    isTestingRecognition = false,
                    message = PreferencesUiMessage(
                        PreferencesMessage.RECOGNITION_SUCCEEDED,
                        transcript.take(MAX_TEST_TRANSCRIPT_DISPLAY),
                    ),
                )
            } catch (failure: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isTestingRecognition = false,
                    errorMessage = voiceFailure(failure, PreferencesMessage.RECOGNITION_TEST_FAILED),
                )
            }
        }
    }

    fun testVoice(config: VoiceConfig) {
        if (closed.get() || mutableState.value.busy || config.outputProvider == VoiceOutputProvider.OFF) return
        mutableState.value = mutableState.value.copy(
            isTestingVoice = true,
            message = PreferencesUiMessage(PreferencesMessage.TESTING_VOICE),
            errorMessage = null,
        )
        scope.launch {
            try {
                val provider = providerFactory.textToSpeech(config.outputProvider)
                    ?: error("Speech synthesis provider is unavailable")
                val audio = provider.synthesize(VOICE_TEST_TEXT, config.synthesisConfig())
                audioPlayer.play(audio.wav)
                mutableState.value = mutableState.value.copy(
                    isTestingVoice = false,
                    message = PreferencesUiMessage(PreferencesMessage.VOICE_TEST_SUCCEEDED),
                )
            } catch (failure: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isTestingVoice = false,
                    errorMessage = voiceFailure(failure, PreferencesMessage.VOICE_TEST_FAILED),
                )
            }
        }
    }

    fun installSpeechPack() {
        if (closed.get() || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(
            isInstallingSpeechPack = true,
            message = PreferencesUiMessage(PreferencesMessage.DOWNLOADING_SPEECH_PACK),
            errorMessage = null,
        )
        scope.launch {
            try {
                speechPackManager.install()
                mutableState.value = mutableState.value.copy(
                    isInstallingSpeechPack = false,
                    speechPack = speechPackManager.state(),
                    message = PreferencesUiMessage(PreferencesMessage.SPEECH_PACK_INSTALLED),
                )
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isInstallingSpeechPack = false,
                    speechPack = speechPackManager.state(),
                    errorMessage = PreferencesUiMessage(PreferencesMessage.SPEECH_PACK_INSTALL_FAILED),
                )
            }
        }
    }

    fun removeSpeechPack() {
        if (closed.get() || mutableState.value.busy) return
        scope.launch {
            runCatching { speechPackManager.remove() }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        speechPack = speechPackManager.state(),
                        message = PreferencesUiMessage(PreferencesMessage.SPEECH_PACK_REMOVED),
                        errorMessage = null,
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = PreferencesUiMessage(PreferencesMessage.SPEECH_PACK_REMOVE_FAILED),
                    )
                }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }

    private fun saveReplacement(
        reference: dev.evestaticmapplanner.embeddedai.AiCredentialRef,
        replacement: SecretValue?,
        secureBefore: Map<dev.evestaticmapplanner.embeddedai.AiCredentialRef, SecretValue?>,
        sessionBefore: Map<dev.evestaticmapplanner.embeddedai.AiCredentialRef, SecretValue?>,
    ): Boolean {
        if (replacement == null) return false
        val secureWrite = runCatching {
            secureStore.save(reference, replacement)
            sessionStore.delete(reference)
        }
        if (secureWrite.isSuccess) return false
        restore(secureStore, reference, secureBefore[reference])
        restore(sessionStore, reference, sessionBefore[reference])
        sessionStore.save(reference, replacement)
        return true
    }

    private fun snapshot(
        store: AiCredentialStore,
        reference: dev.evestaticmapplanner.embeddedai.AiCredentialRef,
    ): SecretValue? = if (store.contains(reference)) store.load(reference) else null

    private fun restore(
        store: AiCredentialStore,
        reference: dev.evestaticmapplanner.embeddedai.AiCredentialRef,
        previous: SecretValue?,
    ) {
        if (previous == null) store.delete(reference) else store.save(reference, previous)
    }

    private companion object {
        const val RECOGNITION_TEST_TEXT = "你好，这是语音识别测试。"
        const val VOICE_TEST_TEXT = "你好，这是语音合成测试。"
        const val MAX_TEST_TRANSCRIPT_DISPLAY = 120
    }
}

private fun voiceFailure(failure: Throwable, fallback: PreferencesMessage): UiMessage =
    (failure as? VoiceException)?.let { VoiceFailureUiMessage(it.code, it.safeMessage) }
        ?: PreferencesUiMessage(fallback)
