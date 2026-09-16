package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiConnectionCheck
import dev.evestaticmapplanner.embeddedai.AiConnectionCheckStatus
import dev.evestaticmapplanner.embeddedai.AiConnectionTestResult
import dev.evestaticmapplanner.embeddedai.AiConnectionTester
import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiCredentialRef
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiProviderConfig
import dev.evestaticmapplanner.embeddedai.AiProviderType
import dev.evestaticmapplanner.embeddedai.InMemoryAiCredentialStore
import dev.evestaticmapplanner.embeddedai.UnavailableAiCredentialStore
import dev.evestaticmapplanner.shared.auth.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AiProviderSettingsControllerTest {
    @Test
    fun `secure credential is saved before config and runtime is invalidated`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val events = mutableListOf<String>()
        val controller = controller(
            secure = secure,
            session = session,
            persist = {
                assertTrue(secure.contains(checkNotNull(it?.credentialRef)))
                events += "config"
                Result.success(Unit)
            },
            changed = { events += "runtime" },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.save(AiProviderConfig.DefaultOpenRouter, SecretValue.from("saved-secret"))
            advanceUntilIdle()

            assertEquals(listOf("config", "runtime"), events)
            assertEquals(AiCredentialSource.SECURE_STORAGE, controller.state.value.credentialSource)
            assertSecretEquals("saved-secret", secure.load(checkNotNull(AiProviderConfig.DefaultOpenRouter.credentialRef)))
        } finally {
            controller.close()
            secure.close()
            session.close()
        }
    }

    @Test
    fun `unavailable secure storage explicitly falls back to this session only`() = runTest {
        val session = InMemoryAiCredentialStore()
        var persisted: AiProviderConfig? = null
        val controller = controller(
            secure = UnavailableAiCredentialStore,
            session = session,
            persist = { persisted = it; Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.save(AiProviderConfig.DefaultOpenRouter, SecretValue.from("session-secret"))
            advanceUntilIdle()

            assertEquals(AiProviderConfig.DefaultOpenRouter, persisted)
            assertEquals(AiCredentialSource.SESSION_ONLY, controller.state.value.credentialSource)
            assertTrue(controller.state.value.message.orEmpty().contains("Key will not be saved"))
        } finally {
            controller.close()
            session.close()
        }
    }

    @Test
    fun `config persistence failure rolls back a newly saved secret`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val controller = controller(
            secure = secure,
            session = session,
            persist = { Result.failure(IllegalStateException("disk details")) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.save(AiProviderConfig.DefaultOpenRouter, SecretValue.from("must-rollback"))
            advanceUntilIdle()

            assertFalse(secure.contains(checkNotNull(AiProviderConfig.DefaultOpenRouter.credentialRef)))
            assertNotNull(controller.state.value.errorMessage)
            assertFalse(controller.state.value.errorMessage.orEmpty().contains("disk details"))
        } finally {
            controller.close()
            secure.close()
            session.close()
        }
    }

    @Test
    fun `connection test uses an unsaved replacement without persisting it`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        var observedSecret: String? = null
        val tester = AiConnectionTester { config, secret ->
            assertEquals(AiProviderConfig.DefaultOpenRouter, config)
            secret.useString { observedSecret = it }
            AiConnectionTestResult(
                AiConnectionCheck(AiConnectionCheckStatus.PASSED, "connection"),
                AiConnectionCheck(AiConnectionCheckStatus.PASSED, "model"),
                AiConnectionCheck(AiConnectionCheckStatus.PASSED, "tools"),
            )
        }
        val controller = AiProviderSettingsController(
            secureStore = secure,
            sessionStore = session,
            credentialResolver = AiCredentialResolver(secure, session) { null },
            connectionTester = tester,
            persistConfig = { Result.success(Unit) },
            onConfigurationChanged = {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.test(AiProviderConfig.DefaultOpenRouter, SecretValue.from("probe-secret"))
            advanceUntilIdle()

            assertEquals("probe-secret", observedSecret)
            assertTrue(controller.state.value.testResult?.successful == true)
            assertFalse(secure.contains(checkNotNull(AiProviderConfig.DefaultOpenRouter.credentialRef)))
        } finally {
            controller.close()
            secure.close()
            session.close()
        }
    }

    @Test
    fun `switching providers keeps the other providers secure credential`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val openRouterRef = checkNotNull(AiProviderConfig.DefaultOpenRouter.credentialRef)
        SecretValue.from("openrouter-secret").use { secure.save(openRouterRef, it) }
        val compatible = AiProviderConfig.normalized(
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.example.com/v1",
            modelId = "example-model",
        )
        val controller = controller(
            secure = secure,
            session = session,
            persist = { Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.save(compatible, SecretValue.from("compatible-secret"))
            advanceUntilIdle()

            assertSecretEquals("openrouter-secret", secure.load(openRouterRef))
            assertSecretEquals("compatible-secret", secure.load(checkNotNull(compatible.credentialRef)))
        } finally {
            controller.close()
            secure.close()
            session.close()
        }
    }

    @Test
    fun `all provider credentials are stored under independent references`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val controller = controller(
            secure = secure,
            session = session,
            persist = { Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            AiProviderType.entries.forEach { providerType ->
                controller.save(testConfig(providerType), SecretValue.from("key-${providerType.name.lowercase()}"))
                advanceUntilIdle()
            }

            AiProviderType.entries.forEach { providerType ->
                val reference = AiCredentialRef.forProvider(providerType)
                assertSecretEquals("key-${providerType.name.lowercase()}", secure.load(reference))
            }

            controller.deleteCredential(AiProviderType.ANTHROPIC)
            advanceUntilIdle()
            assertFalse(secure.contains(AiCredentialRef.forProvider(AiProviderType.ANTHROPIC)))
            AiProviderType.entries.filterNot { it == AiProviderType.ANTHROPIC }.forEach { providerType ->
                assertTrue(secure.contains(AiCredentialRef.forProvider(providerType)))
            }
        } finally {
            controller.close()
            secure.close()
            session.close()
        }
    }
}

private fun testConfig(providerType: AiProviderType) = AiProviderConfig.normalized(
    providerType = providerType,
    baseUrl = "https://api.example.com/v1".takeIf { providerType.requiresBaseUrl },
    modelId = "fixture-${providerType.name.lowercase()}",
)

private fun controller(
    secure: dev.evestaticmapplanner.embeddedai.AiCredentialStore,
    session: InMemoryAiCredentialStore,
    persist: suspend (AiProviderConfig?) -> Result<Unit>,
    changed: () -> Unit = {},
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) = AiProviderSettingsController(
    secureStore = secure,
    sessionStore = session,
    credentialResolver = AiCredentialResolver(secure, session) { null },
    connectionTester = AiConnectionTester { _, _ -> error("Connection test was not expected") },
    persistConfig = persist,
    onConfigurationChanged = changed,
    dispatcher = dispatcher,
)

private fun assertSecretEquals(expected: String, actual: SecretValue?) {
    val secret = checkNotNull(actual)
    secret.use { value -> value.useString { assertEquals(expected, it) } }
}
