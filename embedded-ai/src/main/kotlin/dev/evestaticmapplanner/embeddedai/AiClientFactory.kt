package dev.evestaticmapplanner.embeddedai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

interface ManagedAiClient : AutoCloseable {
    val promptExecutor: PromptExecutor
    val model: LLModel
    val clientImplementation: String
    val httpBackend: String
}

fun interface AiClientFactory {
    fun create(config: AiProviderConfig, secret: SecretValue): ManagedAiClient
}

class DefaultAiClientFactory(
    private val httpClientFactory: KoogHttpClient.Factory = JavaKoogHttpClient.Factory(),
) : AiClientFactory {
    override fun create(config: AiProviderConfig, secret: SecretValue): ManagedAiClient {
        val timeoutMillis = config.requestTimeoutSeconds * 1_000L
        val timeouts = ConnectionTimeoutConfig(
            requestTimeoutMillis = timeoutMillis,
            connectTimeoutMillis = minOf(timeoutMillis, CONNECT_TIMEOUT_MILLIS),
            socketTimeoutMillis = timeoutMillis,
        )
        val client = secret.useString { apiKey ->
            when (config.providerType) {
                AiProviderType.OPENROUTER -> OpenRouterLLMClient(
                    apiKey = apiKey,
                    settings = OpenRouterClientSettings(timeoutConfig = timeouts),
                    httpClientFactory = httpClientFactory,
                )
                AiProviderType.OPENAI -> OpenAILLMClient(
                    apiKey = apiKey,
                    settings = OpenAIClientSettings(timeoutConfig = timeouts),
                    httpClientFactory = httpClientFactory,
                )
                AiProviderType.ANTHROPIC -> AnthropicLLMClient(
                    apiKey = apiKey,
                    settings = AnthropicClientSettings(timeoutConfig = timeouts),
                    httpClientFactory = httpClientFactory,
                )
                AiProviderType.DEEPSEEK -> DeepSeekLLMClient(
                    apiKey = apiKey,
                    settings = DeepSeekClientSettings(timeoutConfig = timeouts),
                    httpClientFactory = httpClientFactory,
                )
                AiProviderType.GOOGLE -> GoogleLLMClient(
                    apiKey = apiKey,
                    settings = GoogleClientSettings(timeoutConfig = timeouts),
                    httpClientFactory = httpClientFactory,
                )
                AiProviderType.OPENAI_COMPATIBLE -> {
                    val endpoint = try {
                        OpenAiCompatibleEndpoint.from(checkNotNull(config.baseUrl))
                    } catch (_: IllegalArgumentException) {
                        throw AiProviderException(
                            AiProviderErrorCode.INVALID_BASE_URL,
                            "The provider Base URL is invalid.",
                        )
                    }
                    OpenAILLMClient(
                        apiKey = apiKey,
                        settings = OpenAIClientSettings(
                            baseUrl = endpoint.baseUrl,
                            timeoutConfig = timeouts,
                            chatCompletionsPath = endpoint.chatCompletionsPath,
                            responsesAPIPath = endpoint.responsesPath,
                            embeddingsPath = endpoint.embeddingsPath,
                            moderationsPath = endpoint.moderationsPath,
                            modelsPath = endpoint.modelsPath,
                        ),
                        httpClientFactory = httpClientFactory,
                    )
                }
            }
        }
        return KoogManagedAiClient(
            client = client,
            model = config.toKoogModel(),
            httpBackend = httpClientFactory::class.simpleName ?: "KoogHttpClient.Factory",
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}

private class KoogManagedAiClient(
    client: LLMClient,
    override val model: LLModel,
    override val httpBackend: String,
) : ManagedAiClient {
    override val promptExecutor: PromptExecutor = MultiLLMPromptExecutor(client)
    override val clientImplementation: String = client::class.simpleName ?: "LLMClient"
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) promptExecutor.close()
    }
}

internal data class OpenAiCompatibleEndpoint(
    val baseUrl: String,
    val chatCompletionsPath: String,
    val responsesPath: String,
    val embeddingsPath: String,
    val moderationsPath: String,
    val modelsPath: String,
) {
    companion object {
        fun from(value: String): OpenAiCompatibleEndpoint {
            AiProviderConfig.validateBaseUrl(value)
            val uri = URI(value.trimEnd('/'))
            val includesVersion = uri.path.orEmpty().trimEnd('/').endsWith("/v1")
            val prefix = if (includesVersion) "" else "v1/"
            return OpenAiCompatibleEndpoint(
                baseUrl = uri.toString().trimEnd('/'),
                chatCompletionsPath = "${prefix}chat/completions",
                responsesPath = "${prefix}responses",
                embeddingsPath = "${prefix}embeddings",
                moderationsPath = "${prefix}moderations",
                modelsPath = "${prefix}models",
            )
        }
    }
}

internal fun AiProviderConfig.toKoogModel(): LLModel = LLModel(
    provider = when (providerType) {
        AiProviderType.OPENROUTER -> LLMProvider.OpenRouter
        AiProviderType.OPENAI -> LLMProvider.OpenAI
        AiProviderType.ANTHROPIC -> LLMProvider.Anthropic
        AiProviderType.DEEPSEEK -> LLMProvider.DeepSeek
        AiProviderType.GOOGLE -> LLMProvider.Google
        AiProviderType.OPENAI_COMPATIBLE -> LLMProvider.OpenAI
    },
    id = modelId,
    capabilities = buildList {
        add(LLMCapability.Completion)
        add(LLMCapability.Temperature)
        add(LLMCapability.Tools)
        add(LLMCapability.ToolChoice)
        if (providerType == AiProviderType.OPENAI || providerType == AiProviderType.OPENAI_COMPATIBLE) {
            add(LLMCapability.OpenAIEndpoint.Completions)
        }
    },
)
