package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

enum class AiConnectionCheckStatus { PASSED, FAILED, NOT_RUN }

data class AiConnectionCheck(
    val status: AiConnectionCheckStatus,
    val message: String,
)

data class AiConnectionTestResult(
    val connection: AiConnectionCheck,
    val model: AiConnectionCheck,
    val toolCalling: AiConnectionCheck,
    val errorCode: AiProviderErrorCode? = null,
) {
    val successful: Boolean
        get() = connection.status == AiConnectionCheckStatus.PASSED &&
            model.status == AiConnectionCheckStatus.PASSED &&
            toolCalling.status == AiConnectionCheckStatus.PASSED
}

fun interface AiConnectionTester {
    suspend fun test(config: AiProviderConfig, secret: SecretValue): AiConnectionTestResult
}

class KoogAiConnectionTester(
    private val clientFactory: AiClientFactory = DefaultAiClientFactory(),
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() },
) : AiConnectionTester {
    override suspend fun test(config: AiProviderConfig, secret: SecretValue): AiConnectionTestResult {
        val managedClient = try {
            clientFactory.create(config, secret)
        } catch (failure: Throwable) {
            return failedBeforeConnection(failure.toSafeProviderException())
        }
        return try {
            runTest(config, managedClient)
        } catch (failure: Throwable) {
            failedBeforeConnection(failure.toSafeProviderException())
        } finally {
            managedClient.close()
        }
    }

    private suspend fun runTest(config: AiProviderConfig, client: ManagedAiClient): AiConnectionTestResult {
        try {
            withTimeout(config.requestTimeoutSeconds * 1_000L) {
                client.promptExecutor.execute(
                    prompt(
                        id = "provider-reachability-probe",
                        params = LLMParams(temperature = 0.0),
                    ) {
                        system("You are a provider connection probe.")
                        user("Reply with the single word OK.")
                    },
                    client.model,
                    emptyList(),
                )
            }
        } catch (failure: Throwable) {
            val safe = failure.toSafeProviderException()
            return when (safe.code) {
                AiProviderErrorCode.MODEL_NOT_FOUND,
                AiProviderErrorCode.MODEL_UNAVAILABLE,
                AiProviderErrorCode.REGION_RESTRICTED,
                -> AiConnectionTestResult(
                    connection = passed("API connection successful"),
                    model = failed(safe.safeMessage),
                    toolCalling = notRun("Tool Calling was not tested"),
                    errorCode = safe.code,
                )
                else -> failedBeforeConnection(safe)
            }
        }

        val nonce = nonceFactory()
        val probe = ProviderCapabilityProbe(nonce)
        return try {
            val agent = AIAgent(
                promptExecutor = client.promptExecutor,
                llmModel = client.model,
                toolRegistry = ToolRegistry { tool(probe) },
                systemPrompt = "Call provider_capability_probe exactly once with the nonce supplied by the user.",
                temperature = 0.0,
                strategy = singleRunStrategy(),
                maxIterations = 6,
            )
            withTimeout(config.requestTimeoutSeconds * 1_000L) {
                agent.run("Call provider_capability_probe with nonce $nonce, then report success.")
            }
            if (!probe.wasCalled()) throw AiProviderException(
                AiProviderErrorCode.TOOL_CALLING_UNSUPPORTED,
                "The selected model did not call the capability probe.",
            )
            successfulResult(config)
        } catch (failure: Throwable) {
            if (probe.wasCalled()) return successfulResult(config)
            val safe = failure.toSafeProviderException(toolProbe = true)
            AiConnectionTestResult(
                connection = passed("API connection successful"),
                model = passed("Model: ${config.modelId}"),
                toolCalling = failed(safe.safeMessage),
                errorCode = safe.code,
            )
        }
    }

    private fun successfulResult(config: AiProviderConfig) = AiConnectionTestResult(
        connection = passed("API connection successful"),
        model = passed("Model: ${config.modelId}"),
        toolCalling = passed("Tool Calling supported"),
    )

    private fun failedBeforeConnection(error: AiProviderException) = AiConnectionTestResult(
        connection = failed(error.safeMessage),
        model = notRun("Model was not tested"),
        toolCalling = notRun("Tool Calling was not tested"),
        errorCode = error.code,
    )
}

private class ProviderCapabilityProbe(
    private val expectedNonce: String,
) : SimpleTool<ProviderCapabilityProbe.Args>(
    argsType = typeToken<Args>(),
    name = "provider_capability_probe",
    description = "A no-side-effect internal probe used only to verify structured tool calling.",
) {
    @Serializable
    data class Args(val nonce: String)

    private val called = AtomicBoolean()

    override suspend fun execute(args: Args): String {
        if (args.nonce != expectedNonce) throw IllegalArgumentException("Capability probe nonce did not match")
        called.set(true)
        return "probe-ok"
    }

    fun wasCalled(): Boolean = called.get()
}

private fun passed(message: String) = AiConnectionCheck(AiConnectionCheckStatus.PASSED, message)
private fun failed(message: String) = AiConnectionCheck(AiConnectionCheckStatus.FAILED, message)
private fun notRun(message: String) = AiConnectionCheck(AiConnectionCheckStatus.NOT_RUN, message)
