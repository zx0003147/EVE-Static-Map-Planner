package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.BraveSearchTester
import dev.evestaticmapplanner.embeddedai.InMemoryAiCredentialStore
import dev.evestaticmapplanner.embeddedai.SearchFreshness
import dev.evestaticmapplanner.embeddedai.UnavailableAiCredentialStore
import dev.evestaticmapplanner.embeddedai.WebSearchClient
import dev.evestaticmapplanner.embeddedai.WebSearchConfig
import dev.evestaticmapplanner.embeddedai.WebSearchRequest
import dev.evestaticmapplanner.embeddedai.WebSearchResponse
import dev.evestaticmapplanner.embeddedai.WebSearchSource
import dev.evestaticmapplanner.shared.auth.SecretValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebSearchSettingsControllerTest {
    @Test
    fun `Brave Key is secured before enabled settings are persisted`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val events = mutableListOf<String>()
        val config = WebSearchConfig(enabled = true)
        val controller = controller(
            secure,
            session,
            persist = {
                assertTrue(secure.contains(config.credentialRef))
                events += "config"
                Result.success(Unit)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.save(config, SecretValue.from("brave-secret"))
            advanceUntilIdle()

            assertEquals(listOf("config"), events)
            assertEquals(AiCredentialSource.SECURE_STORAGE, controller.state.value.credentialSource)
            assertSecret("brave-secret", secure.load(config.credentialRef))
        } finally {
            controller.close()
            secure.close()
            session.close()
        }
    }

    @Test
    fun `persistence failure rolls back a new Brave Key`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val config = WebSearchConfig(enabled = true)
        val controller = controller(
            secure,
            session,
            persist = { Result.failure(IllegalStateException("disk detail")) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.save(config, SecretValue.from("rollback-secret"))
            advanceUntilIdle()

            assertFalse(secure.contains(config.credentialRef))
            assertFalse(controller.state.value.errorMessage.orEmpty().contains("disk detail"))
        } finally {
            controller.close()
            secure.close()
            session.close()
        }
    }

    @Test
    fun `unavailable DPAPI uses explicit session-only Brave credential`() = runTest {
        val session = InMemoryAiCredentialStore()
        val config = WebSearchConfig(enabled = true)
        val controller = WebSearchSettingsController(
            secureStore = UnavailableAiCredentialStore,
            sessionStore = session,
            credentialResolver = AiCredentialResolver(UnavailableAiCredentialStore, session) { null },
            tester = BraveSearchTester(successClient()),
            persistConfig = { Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.save(config, SecretValue.from("session-brave"))
            advanceUntilIdle()

            assertEquals(AiCredentialSource.SESSION_ONLY, controller.state.value.credentialSource)
            assertTrue(controller.state.value.message.orEmpty().contains("session only"))
            assertSecret("session-brave", session.load(config.credentialRef))
        } finally {
            controller.close()
            session.close()
        }
    }

    @Test
    fun `Test Search uses unsaved replacement and reports source parsing`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        var observed: String? = null
        val client = object : WebSearchClient {
            override suspend fun search(request: WebSearchRequest, secret: SecretValue): WebSearchResponse {
                secret.useString { observed = it }
                return successResponse(request)
            }
        }
        val controller = WebSearchSettingsController(
            secureStore = secure,
            sessionStore = session,
            credentialResolver = AiCredentialResolver(secure, session) { null },
            tester = BraveSearchTester(client),
            persistConfig = { Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.test(WebSearchConfig(enabled = true), SecretValue.from("probe-brave"))
            advanceUntilIdle()

            assertEquals("probe-brave", observed)
            assertTrue(controller.state.value.testResult?.successful == true)
            assertFalse(secure.contains(WebSearchConfig.Defaults.credentialRef))
        } finally {
            controller.close()
            secure.close()
            session.close()
        }
    }
}

private fun controller(
    secure: InMemoryAiCredentialStore,
    session: InMemoryAiCredentialStore,
    persist: suspend (WebSearchConfig) -> Result<Unit>,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) = WebSearchSettingsController(
    secureStore = secure,
    sessionStore = session,
    credentialResolver = AiCredentialResolver(secure, session) { null },
    tester = BraveSearchTester(successClient()),
    persistConfig = persist,
    dispatcher = dispatcher,
)

private fun successClient() = object : WebSearchClient {
    override suspend fun search(request: WebSearchRequest, secret: SecretValue): WebSearchResponse =
        successResponse(request)
}

private fun successResponse(request: WebSearchRequest) = WebSearchResponse(
    query = request.query,
    freshness = SearchFreshness.ANY,
    results = listOf(
        WebSearchSource(
            title = "CCP Games",
            url = "https://www.ccpgames.com/",
            hostname = "www.ccpgames.com",
            published = null,
            snippets = listOf("EVE Online"),
        ),
    ),
)

private fun assertSecret(expected: String, actual: SecretValue?) {
    checkNotNull(actual).use { value -> value.useString { assertEquals(expected, it) } }
}
