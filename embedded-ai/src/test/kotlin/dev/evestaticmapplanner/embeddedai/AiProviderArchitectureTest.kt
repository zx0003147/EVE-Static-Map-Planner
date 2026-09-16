package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.shared.auth.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiProviderArchitectureTest {
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
            assertEquals(AiProviderErrorCode.UNKNOWN_PROVIDER_ERROR, mapped.code)
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

private fun ResolvedAiCredential?.useResolved(block: (AiCredentialSource, String) -> Unit) {
    val credential = checkNotNull(this)
    try {
        credential.useSecret { secret -> secret.useString { block(credential.source, it) } }
    } finally {
        credential.close()
    }
}
