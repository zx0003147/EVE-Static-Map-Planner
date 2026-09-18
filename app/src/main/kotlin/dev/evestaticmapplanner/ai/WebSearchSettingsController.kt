package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiCredentialStore
import dev.evestaticmapplanner.embeddedai.BRAVE_SEARCH_ENVIRONMENT_VARIABLE
import dev.evestaticmapplanner.embeddedai.BraveSearchTester
import dev.evestaticmapplanner.embeddedai.SearchTestCheck
import dev.evestaticmapplanner.embeddedai.SearchTestCheckStatus
import dev.evestaticmapplanner.embeddedai.WebSearchConfig
import dev.evestaticmapplanner.embeddedai.WebSearchTestResult
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

data class WebSearchSettingsUiState(
    val credentialSource: AiCredentialSource? = null,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val testResult: WebSearchTestResult? = null,
    val message: UiMessage? = null,
    val errorMessage: UiMessage? = null,
)

class WebSearchSettingsController(
    private val secureStore: AiCredentialStore,
    private val sessionStore: AiCredentialStore,
    private val credentialResolver: AiCredentialResolver,
    private val tester: BraveSearchTester,
    private val persistConfig: suspend (WebSearchConfig) -> Result<Unit>,
    private val diagnostics: (String) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(WebSearchSettingsUiState())
    val state: StateFlow<WebSearchSettingsUiState> = mutableState.asStateFlow()

    fun refresh(config: WebSearchConfig) {
        if (closed.get()) return
        mutableState.value = mutableState.value.copy(
            credentialSource = credentialResolver.source(config.credentialRef, BRAVE_SEARCH_ENVIRONMENT_VARIABLE),
            testResult = null,
            message = null,
            errorMessage = null,
        )
    }

    fun test(config: WebSearchConfig, replacementSecret: SecretValue?) {
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
            val resolved = if (replacementSecret == null) {
                credentialResolver.resolve(config.credentialRef, BRAVE_SEARCH_ENVIRONMENT_VARIABLE)
            } else {
                null
            }
            val secret = replacementSecret ?: resolved?.useSecret(SecretValue::copy)
            try {
                if (secret == null) {
                    mutableState.value = mutableState.value.copy(
                        isTesting = false,
                        testResult = missingCredentialTestResult(),
                        errorMessage = PreferencesUiMessage(PreferencesMessage.WEB_SEARCH_NOT_CONFIGURED),
                    )
                    return@launch
                }
                val result = tester.test(secret)
                diagnostics(
                    "Brave Search test: ${if (result.successful) "success" else "failed:${result.errorCode}"}",
                )
                mutableState.value = mutableState.value.copy(
                    isTesting = false,
                    testResult = result,
                    errorMessage = null,
                )
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isTesting = false,
                    errorMessage = PreferencesUiMessage(PreferencesMessage.WEB_SEARCH_TEST_FAILED),
                )
            } finally {
                secret?.close()
                resolved?.close()
                replacementSecret?.takeIf { it !== secret }?.close()
            }
        }
    }

    fun save(config: WebSearchConfig, replacementSecret: SecretValue?) {
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
                val persisted = persistConfig(config)
                if (persisted.isFailure) {
                    restore(secureStore, config, secureBefore)
                    restore(sessionStore, config, sessionBefore)
                    throw persisted.exceptionOrNull() ?: IllegalStateException("Web Search settings could not be saved")
                }
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    credentialSource = credentialResolver.source(
                        config.credentialRef,
                        BRAVE_SEARCH_ENVIRONMENT_VARIABLE,
                    ),
                    testResult = null,
                    message = PreferencesUiMessage(
                        if (sessionOnly) {
                            PreferencesMessage.WEB_SEARCH_SETTINGS_SAVED_SESSION_ONLY
                        } else {
                            PreferencesMessage.WEB_SEARCH_SETTINGS_SAVED
                        },
                    ),
                )
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = PreferencesUiMessage(PreferencesMessage.WEB_SEARCH_SETTINGS_UPDATE_FAILED),
                )
            } finally {
                secureBefore?.close()
                sessionBefore?.close()
                replacementSecret?.close()
            }
        }
    }

    fun deleteCredential(config: WebSearchConfig) {
        if (closed.get() || mutableState.value.isSaving || mutableState.value.isTesting) return
        mutableState.value = mutableState.value.copy(isSaving = true, message = null, errorMessage = null)
        scope.launch {
            try {
                secureStore.delete(config.credentialRef)
                sessionStore.delete(config.credentialRef)
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    credentialSource = credentialResolver.source(
                        config.credentialRef,
                        BRAVE_SEARCH_ENVIRONMENT_VARIABLE,
                    ),
                    testResult = null,
                    message = PreferencesUiMessage(PreferencesMessage.WEB_SEARCH_API_KEY_DELETED),
                )
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = PreferencesUiMessage(PreferencesMessage.WEB_SEARCH_API_KEY_DELETE_FAILED),
                )
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }

    private fun snapshot(store: AiCredentialStore, config: WebSearchConfig): SecretValue? =
        if (store.contains(config.credentialRef)) store.load(config.credentialRef) else null

    private fun restore(store: AiCredentialStore, config: WebSearchConfig, previous: SecretValue?) {
        if (previous == null) store.delete(config.credentialRef) else store.save(config.credentialRef, previous)
    }
}

private fun missingCredentialTestResult(): WebSearchTestResult {
    val notRun = SearchTestCheck(SearchTestCheckStatus.NOT_RUN, "Not tested")
    return WebSearchTestResult(
        connection = notRun,
        authentication = notRun,
        searchResponse = notRun,
        sourceParsing = notRun,
        successful = false,
        errorCode = dev.evestaticmapplanner.embeddedai.WebSearchErrorCode.NO_SEARCH_CREDENTIAL,
    )
}
