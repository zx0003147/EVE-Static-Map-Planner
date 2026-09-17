package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiCredentialStore
import dev.evestaticmapplanner.embeddedai.OPENAI_VOICE_ENVIRONMENT_VARIABLE
import dev.evestaticmapplanner.embeddedai.VoiceConfig
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
    val credentialSource: AiCredentialSource? = null,
    val speechPack: SpeechPackState = SpeechPackState(false),
    val windowsVoices: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val isInstallingSpeechPack: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

internal class VoiceSettingsController(
    private val secureStore: AiCredentialStore,
    private val sessionStore: AiCredentialStore,
    private val credentialResolver: AiCredentialResolver,
    private val speechPackManager: SpeechPackManager,
    private val localSynthesizer: SpeechSynthesizer,
    private val persistConfig: suspend (VoiceConfig) -> Result<Unit>,
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
            credentialSource = credentialResolver.source(config.credentialRef, OPENAI_VOICE_ENVIRONMENT_VARIABLE),
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

    fun save(config: VoiceConfig, replacementSecret: SecretValue?) {
        if (closed.get() || mutableState.value.isSaving || mutableState.value.isInstallingSpeechPack) {
            replacementSecret?.close()
            return
        }
        mutableState.value = mutableState.value.copy(isSaving = true, message = null, errorMessage = null)
        scope.launch {
            val secureBefore = snapshot(secureStore, config)
            val sessionBefore = snapshot(sessionStore, config)
            try {
                var sessionOnly = false
                if (replacementSecret != null) {
                    val secureWrite = runCatching {
                        secureStore.save(config.credentialRef, replacementSecret)
                        sessionStore.delete(config.credentialRef)
                    }
                    if (secureWrite.isFailure) {
                        restore(secureStore, config, secureBefore)
                        restore(sessionStore, config, sessionBefore)
                        sessionStore.save(config.credentialRef, replacementSecret)
                        sessionOnly = true
                    }
                }
                persistConfig(config).getOrThrow()
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    credentialSource = credentialResolver.source(
                        config.credentialRef,
                        OPENAI_VOICE_ENVIRONMENT_VARIABLE,
                    ),
                    message = if (sessionOnly) {
                        "Voice settings saved. Secure storage is unavailable; the Key is available for this session only."
                    } else {
                        "Voice I/O settings saved."
                    },
                )
            } catch (_: Throwable) {
                restore(secureStore, config, secureBefore)
                restore(sessionStore, config, sessionBefore)
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = "Voice I/O settings could not be updated.",
                )
            } finally {
                secureBefore?.close()
                sessionBefore?.close()
                replacementSecret?.close()
            }
        }
    }

    fun deleteCredential(config: VoiceConfig) {
        if (closed.get() || mutableState.value.isSaving) return
        mutableState.value = mutableState.value.copy(isSaving = true, message = null, errorMessage = null)
        scope.launch {
            try {
                secureStore.delete(config.credentialRef)
                sessionStore.delete(config.credentialRef)
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    credentialSource = credentialResolver.source(
                        config.credentialRef,
                        OPENAI_VOICE_ENVIRONMENT_VARIABLE,
                    ),
                    message = "Saved OpenAI Voice API Key deleted.",
                )
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = "The OpenAI Voice API Key could not be deleted.",
                )
            }
        }
    }

    fun installSpeechPack() {
        if (closed.get() || mutableState.value.isInstallingSpeechPack) return
        mutableState.value = mutableState.value.copy(
            isInstallingSpeechPack = true,
            message = "Downloading Speech Pack…",
            errorMessage = null,
        )
        scope.launch {
            try {
                speechPackManager.install()
                mutableState.value = mutableState.value.copy(
                    isInstallingSpeechPack = false,
                    speechPack = speechPackManager.state(),
                    message = "Speech Pack installed.",
                )
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isInstallingSpeechPack = false,
                    speechPack = speechPackManager.state(),
                    errorMessage = "Speech Pack download or verification failed.",
                )
            }
        }
    }

    fun removeSpeechPack() {
        if (closed.get() || mutableState.value.isInstallingSpeechPack) return
        scope.launch {
            runCatching { speechPackManager.remove() }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        speechPack = speechPackManager.state(),
                        message = "Speech Pack removed.",
                        errorMessage = null,
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = "Speech Pack could not be removed.",
                    )
                }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }

    private fun snapshot(store: AiCredentialStore, config: VoiceConfig): SecretValue? =
        if (store.contains(config.credentialRef)) store.load(config.credentialRef) else null

    private fun restore(store: AiCredentialStore, config: VoiceConfig, previous: SecretValue?) {
        if (previous == null) store.delete(config.credentialRef) else store.save(config.credentialRef, previous)
    }
}
