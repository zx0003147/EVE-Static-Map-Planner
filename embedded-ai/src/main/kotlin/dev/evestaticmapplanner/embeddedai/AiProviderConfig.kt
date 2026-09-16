package dev.evestaticmapplanner.embeddedai

import java.net.URI

enum class AiProviderType(
    val displayName: String,
    val environmentVariable: String,
) {
    OPENROUTER("OpenRouter", "OPENROUTER_API_KEY"),
    OPENAI_COMPATIBLE("OpenAI Compatible", "OPENAI_API_KEY"),
}

@JvmInline
value class AiCredentialRef(val value: String) {
    init {
        require(value.matches(VALID_VALUE)) { "Credential reference is invalid" }
    }

    companion object {
        fun forProvider(providerType: AiProviderType): AiCredentialRef = when (providerType) {
            AiProviderType.OPENROUTER -> AiCredentialRef("openrouter")
            AiProviderType.OPENAI_COMPATIBLE -> AiCredentialRef("openai-compatible")
        }

        private val VALID_VALUE = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}

data class AiProviderConfig(
    val providerType: AiProviderType,
    val baseUrl: String?,
    val credentialRef: AiCredentialRef?,
    val modelId: String,
    val temperature: Double?,
    val requestTimeoutSeconds: Int,
) {
    init {
        require(modelId.isNotEmpty() && modelId == modelId.trim() && modelId.length <= MAX_MODEL_ID_LENGTH) {
            "Model ID must be non-empty, trimmed, and at most $MAX_MODEL_ID_LENGTH characters"
        }
        require(modelId.none(Char::isISOControl)) { "Model ID contains control characters" }
        require(temperature == null || temperature.isFinite() && temperature in MIN_TEMPERATURE..MAX_TEMPERATURE) {
            "Temperature must be between $MIN_TEMPERATURE and $MAX_TEMPERATURE"
        }
        require(requestTimeoutSeconds in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            "Request timeout must be between $MIN_TIMEOUT_SECONDS and $MAX_TIMEOUT_SECONDS seconds"
        }
        when (providerType) {
            AiProviderType.OPENROUTER -> require(baseUrl == null) { "OpenRouter uses its official endpoint" }
            AiProviderType.OPENAI_COMPATIBLE -> requireNotNull(baseUrl) {
                "OpenAI-compatible providers require a Base URL"
            }.also(::validateBaseUrl)
        }
    }

    companion object {
        const val DEFAULT_OPENROUTER_MODEL = "deepseek/deepseek-v4-flash-0731"
        const val DEFAULT_TIMEOUT_SECONDS = 90
        const val DEFAULT_TEMPERATURE = 0.2
        const val MIN_TIMEOUT_SECONDS = 5
        const val MAX_TIMEOUT_SECONDS = 300
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
        const val MAX_MODEL_ID_LENGTH = 200

        val DefaultOpenRouter = AiProviderConfig(
            providerType = AiProviderType.OPENROUTER,
            baseUrl = null,
            credentialRef = AiCredentialRef.forProvider(AiProviderType.OPENROUTER),
            modelId = DEFAULT_OPENROUTER_MODEL,
            temperature = DEFAULT_TEMPERATURE,
            requestTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS,
        )

        fun normalized(
            providerType: AiProviderType,
            baseUrl: String?,
            credentialRef: AiCredentialRef? = AiCredentialRef.forProvider(providerType),
            modelId: String,
            temperature: Double? = DEFAULT_TEMPERATURE,
            requestTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        ): AiProviderConfig = AiProviderConfig(
            providerType = providerType,
            baseUrl = when (providerType) {
                AiProviderType.OPENROUTER -> null
                AiProviderType.OPENAI_COMPATIBLE -> baseUrl?.trim()?.trimEnd('/')
            },
            credentialRef = credentialRef,
            modelId = modelId.trim(),
            temperature = temperature,
            requestTimeoutSeconds = requestTimeoutSeconds,
        )

        internal fun validateBaseUrl(value: String) {
            require(value == value.trim() && value.length <= 2_048) { "Base URL is invalid" }
            val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Base URL is invalid") }
            require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
                "Base URL must use HTTPS"
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Base URL is invalid"
            }
            if (uri.scheme.equals("http", ignoreCase = true)) {
                require(uri.host.lowercase() in LOOPBACK_HOSTS) { "HTTP Base URLs are allowed only for localhost" }
            }
            require(!uri.path.orEmpty().endsWith("/chat/completions")) {
                "Base URL must not include the chat/completions endpoint"
            }
        }

        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}

fun interface AiProviderConfigSource {
    fun current(): AiProviderConfig?
}

class SavedOrEnvironmentAiProviderConfigSource(
    private val savedConfig: () -> AiProviderConfig?,
    private val environment: (String) -> String? = System::getenv,
) : AiProviderConfigSource {
    override fun current(): AiProviderConfig? = savedConfig()
        ?: AiProviderConfig.DefaultOpenRouter.takeIf {
            !environment(AiProviderType.OPENROUTER.environmentVariable).isNullOrBlank()
        }
}
