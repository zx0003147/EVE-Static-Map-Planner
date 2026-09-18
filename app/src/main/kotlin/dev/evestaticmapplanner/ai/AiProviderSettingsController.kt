package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiConnectionTestResult
import dev.evestaticmapplanner.embeddedai.AiConnectionTester
import dev.evestaticmapplanner.embeddedai.AiCredentialRef
import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiCredentialStore
import dev.evestaticmapplanner.embeddedai.AiProviderConfig
import dev.evestaticmapplanner.embeddedai.AiProviderException
import dev.evestaticmapplanner.embeddedai.AiProviderType
import dev.evestaticmapplanner.localization.PreferencesMessage
import dev.evestaticmapplanner.localization.PreferencesUiMessage
import dev.evestaticmapplanner.localization.UiMessage
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

data class AiProviderSettingsUiState(
    val credentialSource: AiCredentialSource? = null,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val testResult: AiConnectionTestResult? = null,
    val message: UiMessage? = null,
    val errorMessage: UiMessage? = null,
)

class AiProviderSettingsController(
    private val secureStore: AiCredentialStore,
    private val sessionStore: AiCredentialStore,
    private val credentialResolver: AiCredentialResolver,
    private val connectionTester: AiConnectionTester,
    private val persistConfig: suspend (AiProviderConfig?) -> Result<Unit>,
    private val onConfigurationChanged: () -> Unit,
    private val diagnostics: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(AiProviderSettingsUiState())
    val state: StateFlow<AiProviderSettingsUiState> = mutableState.asStateFlow()

    fun refresh(config: AiProviderConfig?) {
        if (closed.get()) return
        mutableState.value = mutableState.value.copy(
            credentialSource = config?.let(credentialResolver::source),
            testResult = null,
            message = null,
            errorMessage = null,
        )
    }

    fun refresh(providerType: AiProviderType) {
        if (closed.get()) return
        mutableState.value = mutableState.value.copy(
            credentialSource = credentialResolver.source(providerType),
            testResult = null,
            message = null,
            errorMessage = null,
        )
    }

    /** Takes ownership of [replacementSecret] and clears it after the test. */
    fun test(config: AiProviderConfig, replacementSecret: SecretValue?) {
        if (closed.get() || mutableState.value.isTesting || mutableState.value.isSaving) {
            replacementSecret?.close()
            return
        }
        mutableState.value = mutableState.value.copy(
            isTesting = true,
            testResult = null,
            message = null,
            errorMessage = null,
        )
        scope.launch {
            val resolved = if (replacementSecret == null) credentialResolver.resolve(config) else null
            val secret = replacementSecret ?: resolved?.let { credential ->
                credential.useSecret(SecretValue::copy)
            }
            try {
                if (secret == null) {
                    mutableState.value = mutableState.value.copy(
                        isTesting = false,
                        errorMessage = PreferencesUiMessage(PreferencesMessage.AI_API_KEY_NOT_CONFIGURED),
                    )
                    return@launch
                }
                val result = connectionTester.test(config, secret)
                diagnostics("AI connection test: provider=${config.providerType.displayName}, model=${config.modelId}, " +
                    "result=${if (result.successful) "success" else "failed:${result.errorCode}"}")
                mutableState.value = mutableState.value.copy(
                    isTesting = false,
                    testResult = result,
                    errorMessage = null,
                )
            } catch (failure: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isTesting = false,
                    errorMessage = safeSettingsError(failure),
                )
            } finally {
                secret?.close()
                resolved?.close()
                replacementSecret?.takeIf { it !== secret }?.close()
            }
        }
    }

    /** Takes ownership of [replacementSecret]. */
    fun save(config: AiProviderConfig, replacementSecret: SecretValue?) {
        if (closed.get() || mutableState.value.isSaving || mutableState.value.isTesting) {
            replacementSecret?.close()
            return
        }
        mutableState.value = mutableState.value.copy(
            isSaving = true,
            message = null,
            errorMessage = null,
        )
        scope.launch {
            try {
                val storageResult = if (replacementSecret == null) {
                    CredentialWriteResult(credentialResolver.source(config), sessionOnlyFallback = false)
                } else {
                    writeCredentialTransaction(config, replacementSecret)
                }
                try {
                    val persisted = persistConfig(config)
                    if (persisted.isFailure) {
                        storageResult.rollback?.invoke()
                        throw persisted.exceptionOrNull() ?: IllegalStateException("AI settings could not be saved")
                    }
                    onConfigurationChanged()
                    val source = storageResult.source ?: credentialResolver.source(config)
                    mutableState.value = mutableState.value.copy(
                        isSaving = false,
                        credentialSource = source,
                        testResult = null,
                        message = PreferencesUiMessage(
                            if (storageResult.sessionOnlyFallback) {
                                PreferencesMessage.AI_SETTINGS_SAVED_SESSION_ONLY
                            } else {
                                PreferencesMessage.AI_SETTINGS_SAVED
                            },
                        ),
                    )
                } finally {
                    storageResult.dispose?.invoke()
                }
            } catch (failure: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = safeSettingsError(failure),
                )
            } finally {
                replacementSecret?.close()
            }
        }
    }

    fun deleteCredential(providerType: AiProviderType) {
        if (closed.get() || mutableState.value.isSaving || mutableState.value.isTesting) return
        mutableState.value = mutableState.value.copy(isSaving = true, message = null, errorMessage = null)
        scope.launch {
            val reference = AiCredentialRef.forProvider(providerType)
            val secureBefore = snapshot(secureStore, reference)
            val sessionBefore = snapshot(sessionStore, reference)
            try {
                secureStore.delete(reference)
                sessionStore.delete(reference)
                onConfigurationChanged()
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    credentialSource = credentialResolver.source(providerType),
                    testResult = null,
                    message = PreferencesUiMessage(PreferencesMessage.AI_API_KEY_DELETED),
                )
            } catch (failure: Throwable) {
                restore(secureStore, reference, secureBefore)
                restore(sessionStore, reference, sessionBefore)
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = safeSettingsError(failure),
                )
            } finally {
                secureBefore?.close()
                sessionBefore?.close()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }

    private fun writeCredentialTransaction(config: AiProviderConfig, secret: SecretValue): CredentialWriteResult {
        val reference = config.credentialRef ?: AiCredentialRef.forProvider(config.providerType)
        val secureBefore = snapshot(secureStore, reference)
        val sessionBefore = snapshot(sessionStore, reference)
        val rollback = {
            restore(secureStore, reference, secureBefore)
            restore(sessionStore, reference, sessionBefore)
        }
        val dispose = {
            secureBefore?.close()
            sessionBefore?.close()
            Unit
        }
        return try {
            val secureResult = runCatching {
                secureStore.save(reference, secret)
                sessionStore.delete(reference)
            }
            if (secureResult.isSuccess) {
                CredentialWriteResult(
                    source = AiCredentialSource.SECURE_STORAGE,
                    sessionOnlyFallback = false,
                    rollback = rollback,
                    dispose = dispose,
                )
            } else {
                rollback()
                sessionStore.save(reference, secret)
                CredentialWriteResult(
                    source = AiCredentialSource.SESSION_ONLY,
                    sessionOnlyFallback = true,
                    rollback = rollback,
                    dispose = dispose,
                )
            }
        } catch (failure: Throwable) {
            dispose()
            throw failure
        }
    }

    private fun snapshot(store: AiCredentialStore, reference: AiCredentialRef): SecretValue? =
        if (store.contains(reference)) store.load(reference) else null

    private fun restore(store: AiCredentialStore, reference: AiCredentialRef, previous: SecretValue?) {
        if (previous == null) store.delete(reference) else store.save(reference, previous)
    }

    private data class CredentialWriteResult(
        val source: AiCredentialSource?,
        val sessionOnlyFallback: Boolean,
        val rollback: (() -> Unit)? = null,
        val dispose: (() -> Unit)? = null,
    )
}

private fun safeSettingsError(failure: Throwable): UiMessage = PreferencesUiMessage(
    id = PreferencesMessage.AI_SETTINGS_UPDATE_FAILED,
    technicalDetail = (failure as? AiProviderException)?.safeMessage,
)
