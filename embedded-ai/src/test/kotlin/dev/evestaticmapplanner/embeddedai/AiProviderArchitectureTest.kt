package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.shared.auth.SecretValue
import ai.koog.prompt.llm.LLMProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiProviderArchitectureTest {
    @Test
    fun `factory creates the native Koog client and model provider for every backend`() {
        val cases = listOf(
            providerCase(AiProviderType.OPENROUTER, "OpenRouterLLMClient", LLMProvider.OpenRouter),
            providerCase(AiProviderType.OPENAI, "OpenAILLMClient", LLMProvider.OpenAI),
            providerCase(AiProviderType.ANTHROPIC, "AnthropicLLMClient", LLMProvider.Anthropic),
            providerCase(AiProviderType.DEEPSEEK, "DeepSeekLLMClient", LLMProvider.DeepSeek),
            providerCase(AiProviderType.GOOGLE, "GoogleLLMClient", LLMProvider.Google),
            providerCase(AiProviderType.OPENAI_COMPATIBLE, "OpenAILLMClient", LLMProvider.OpenAI),
        )

        cases.forEach { case ->
            SecretValue.from("fixture-${case.config.providerType.name.lowercase()}").use { secret ->
                DefaultAiClientFactory().create(case.config, secret).use { client ->
                    assertEquals(case.clientImplementation, client.clientImplementation)
                    assertEquals(case.provider, client.model.provider)
                    assertEquals(case.config.modelId, client.model.id)
                    assertTrue(client.httpBackend.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `provider credential references and environment fallbacks are strictly isolated`() {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val environment = AiProviderType.entries.associate { it.environmentVariable to "env-${it.name.lowercase()}" }
        val resolver = AiCredentialResolver(secure, session, environment::get)
        try {
            AiProviderType.entries.forEach { providerType ->
                val config = providerConfig(providerType)
                assertEquals(providerType.name.lowercase().replace('_', '-'), checkNotNull(config.credentialRef).value)
                resolver.resolve(config).useResolved { source, value ->
                    assertEquals(AiCredentialSource.ENVIRONMENT, source)
                    assertEquals("env-${providerType.name.lowercase()}", value)
                }
            }
            assertEquals(AiProviderType.entries.size, AiProviderType.entries.map { it.environmentVariable }.toSet().size)
            assertEquals(AiProviderType.entries.size, AiProviderType.entries.map(AiCredentialRef::forProvider).toSet().size)
        } finally {
            secure.close()
            session.close()
        }
    }

    @Test
    fun `provider config is normalized and contains only a credential reference`() {
        val config = AiProviderConfig.normalized(
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = " https://api.example.com/v1/ ",
            modelId = " example-model ",
            temperature = 0.4,
            requestTimeoutSeconds = 45,
        )

        assertEquals("https://api.example.com/v1", config.baseUrl)
        assertEquals("example-model", config.modelId)
        assertEquals(AiCredentialRef("openai-compatible"), config.credentialRef)
        assertFalse(config.toString().contains("apiKey", ignoreCase = true))
    }

    @Test
    fun `base URL validation permits HTTPS and localhost HTTP only`() {
        AiProviderConfig.normalized(
            AiProviderType.OPENAI_COMPATIBLE,
            "http://127.0.0.1:8090/v1",
            modelId = "fixture",
        )
        assertFailsWith<IllegalArgumentException> {
            AiProviderConfig.normalized(
                AiProviderType.OPENAI_COMPATIBLE,
                "http://api.example.com/v1",
                modelId = "fixture",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AiProviderConfig.normalized(
                AiProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1/chat/completions",
                modelId = "fixture",
            )
        }
    }

    @Test
    fun `compatible endpoint keeps a supplied v1 prefix exactly once`() {
        val versioned = OpenAiCompatibleEndpoint.from("https://api.example.com/v1")
        val unversioned = OpenAiCompatibleEndpoint.from("https://api.example.com")

        assertEquals("https://api.example.com/v1", versioned.baseUrl)
        assertEquals("chat/completions", versioned.chatCompletionsPath)
        assertEquals("models", versioned.modelsPath)
        assertEquals("v1/chat/completions", unversioned.chatCompletionsPath)
    }

    @Test
    fun `credential resolver prioritizes session then secure storage then environment`() {
        val session = InMemoryAiCredentialStore()
        val secure = InMemoryAiCredentialStore()
        val config = AiProviderConfig.DefaultOpenRouter
        val reference = checkNotNull(config.credentialRef)
        val resolver = AiCredentialResolver(secure, session) { "environment-secret" }
        try {
            resolver.resolve(config).useResolved { source, value ->
                assertEquals(AiCredentialSource.ENVIRONMENT, source)
                assertEquals("environment-secret", value)
            }
            SecretValue.from("secure-secret").use { secure.save(reference, it) }
            resolver.resolve(config).useResolved { source, value ->
                assertEquals(AiCredentialSource.SECURE_STORAGE, source)
                assertEquals("secure-secret", value)
            }
            SecretValue.from("session-secret").use { session.save(reference, it) }
            resolver.resolve(config).useResolved { source, value ->
                assertEquals(AiCredentialSource.SESSION_ONLY, source)
                assertEquals("session-secret", value)
            }
        } finally {
            session.close()
            secure.close()
        }
    }

    @Test
    fun `missing configuration and credential failures never include secrets`() {
        val secretMarker = "SECRET_SHOULD_NEVER_APPEAR_12345"
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val resolver = AiCredentialResolver(secure, session) { null }
        try {
            assertNull(resolver.resolve(AiProviderConfig.DefaultOpenRouter))
            val error = AiProviderException(AiProviderErrorCode.NO_CREDENTIAL, "AI API Key is not configured.")
            assertFalse(error.toString().contains(secretMarker))
            assertTrue(error.toString().contains("NO_CREDENTIAL"))
            val mapped = IllegalStateException(secretMarker).toSafeProviderException()
            assertEquals(AiProviderErrorCode.PROVIDER_ERROR, mapped.code)
            assertNull(mapped.cause)
            assertFalse(mapped.toString().contains(secretMarker))
            assertFalse(mapped.stackTraceToString().contains(secretMarker))

            val resolved = ResolvedAiCredential(SecretValue.from(secretMarker), AiCredentialSource.SESSION_ONLY)
            resolved.use { assertFalse(it.toString().contains(secretMarker)) }
        } finally {
            secure.close()
            session.close()
        }
    }
}

private data class ProviderFactoryCase(
    val config: AiProviderConfig,
    val clientImplementation: String,
    val provider: LLMProvider,
)

private fun providerCase(
    providerType: AiProviderType,
    clientImplementation: String,
    provider: LLMProvider,
) = ProviderFactoryCase(providerConfig(providerType), clientImplementation, provider)

private fun providerConfig(providerType: AiProviderType): AiProviderConfig = AiProviderConfig.normalized(
    providerType = providerType,
    baseUrl = "https://api.example.com/v1".takeIf { providerType.requiresBaseUrl },
    modelId = "fixture-${providerType.name.lowercase()}",
)

private fun ResolvedAiCredential?.useResolved(block: (AiCredentialSource, String) -> Unit) {
    val credential = checkNotNull(this)
    try {
        credential.useSecret { secret -> secret.useString { block(credential.source, it) } }
    } finally {
        credential.close()
    }
}
