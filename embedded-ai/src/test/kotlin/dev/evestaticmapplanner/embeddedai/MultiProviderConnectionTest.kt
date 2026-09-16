package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MultiProviderConnectionTest {
    @Test
    fun `every provider runs connection model and structured Tool Calling checks`() = runBlocking {
        AiProviderType.entries.forEach { providerType ->
            val config = connectionConfig(providerType)
            val closeCount = AtomicInteger()
            val probe = ProviderCapabilityProbe(FIXED_NONCE)
            val executor = getMockExecutor {
                mockLLMAnswer("OK") onRequestContains "single word OK"
                mockLLMToolCall(probe, ProviderCapabilityProbe.Args(FIXED_NONCE)) onRequestContains FIXED_NONCE
                mockLLMAnswer("probe complete") onRequestContains "probe-ok"
            }
            val factory = AiClientFactory { _, _ ->
                object : ManagedAiClient {
                    override val promptExecutor: PromptExecutor = executor
                    override val model: LLModel = config.toKoogModel()
                    override val clientImplementation: String = "Fixture"
                    override val httpBackend: String = "Fixture"
                    override fun close() {
                        closeCount.incrementAndGet()
                        executor.close()
                    }
                }
            }

            val result = SecretValue.from("fixture-key").use { secret ->
                KoogAiConnectionTester(factory, nonceFactory = { FIXED_NONCE }).test(config, secret)
            }

            assertTrue(result.successful, "$providerType: $result")
            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status)
            assertEquals(AiConnectionCheckStatus.PASSED, result.model.status)
            assertEquals(AiConnectionCheckStatus.PASSED, result.toolCalling.status)
            assertEquals(1, closeCount.get())
        }
    }
}

private fun connectionConfig(providerType: AiProviderType) = AiProviderConfig.normalized(
    providerType = providerType,
    baseUrl = "https://api.example.com/v1".takeIf { providerType.requiresBaseUrl },
    modelId = "fixture-${providerType.name.lowercase()}",
    temperature = 0.0,
    requestTimeoutSeconds = 10,
)

private const val FIXED_NONCE = "00000000-0000-0000-0000-000000000123"
