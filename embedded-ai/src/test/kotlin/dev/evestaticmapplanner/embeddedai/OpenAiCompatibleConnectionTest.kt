package dev.evestaticmapplanner.embeddedai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OpenAiCompatibleConnectionTest {
    @Test
    fun `custom base URL performs completion and a real structured tool probe`() = runBlocking {
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        val server = toolCallingServer(requests, supportTools = true)
        server.start()
        try {
            val config = compatibleConfig(server)
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(
                    DefaultAiClientFactory(),
                    nonceFactory = { "00000000-0000-0000-0000-000000000123" },
                ).test(config, secret)
            }

            assertTrue(result.successful, result.toString())
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.path == "/v1/chat/completions" })
            assertTrue(requests.all { it.authorization == "Bearer fixture-api-key" })
            assertTrue(requests[1].body.contains("provider_capability_probe"))
            assertTrue(requests[2].body.contains("probe-ok"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `plain completion does not falsely claim Tool Calling support`() = runBlocking {
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        val server = toolCallingServer(requests, supportTools = false)
        server.start()
        try {
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(DefaultAiClientFactory(), nonceFactory = { "fixed-nonce" })
                    .test(compatibleConfig(server), secret)
            }

            assertFalse(result.successful)
            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status, result.toString())
            assertEquals(AiConnectionCheckStatus.PASSED, result.model.status)
            assertEquals(AiConnectionCheckStatus.FAILED, result.toolCalling.status)
            assertEquals(AiProviderErrorCode.TOOL_CALLING_UNSUPPORTED, result.errorCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `regional provider rejection is mapped without exposing the response body`() = runBlocking {
        val rawBody = """{"error":{"message":"This model is not available in your region.","debug":"private-provider-detail"}}"""
        val server = errorServer(403, rawBody)
        server.start()
        try {
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(DefaultAiClientFactory()).test(compatibleConfig(server), secret)
            }

            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status)
            assertEquals(AiConnectionCheckStatus.FAILED, result.model.status)
            assertEquals(AiConnectionCheckStatus.NOT_RUN, result.toolCalling.status)
            assertEquals(AiProviderErrorCode.REGION_RESTRICTED, result.errorCode)
            assertFalse(result.toString().contains("private-provider-detail"))
        } finally {
            server.stop(0)
        }
    }
}

private fun compatibleConfig(server: HttpServer) = AiProviderConfig.normalized(
    providerType = AiProviderType.OPENAI_COMPATIBLE,
    baseUrl = "http://127.0.0.1:${server.address.port}/v1",
    modelId = "fixture-model",
    temperature = 0.0,
    requestTimeoutSeconds = 10,
)

private fun toolCallingServer(
    requests: MutableList<CapturedRequest>,
    supportTools: Boolean,
): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/v1/chat/completions") { exchange ->
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        requests += CapturedRequest(
            path = exchange.requestURI.path,
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            body = body,
        )
        val hasTools = body.contains("provider_capability_probe")
        val hasToolResult = body.contains("probe-ok")
        val response = when {
            !supportTools || !hasTools -> completionResponse("OK")
            hasToolResult -> completionResponse("probe complete")
            else -> toolCallResponse(body.extractNonce())
        }
        exchange.respondJson(response)
    }
}

private fun errorServer(status: Int, body: String): HttpServer =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

private fun String.extractNonce(): String = Regex("nonce ([0-9a-f-]{5,})")
    .findAll(this)
    .lastOrNull()
    ?.groupValues
    ?.get(1)
    ?: "fixed-nonce"

private fun completionResponse(content: String) = """
    {"id":"chatcmpl-fixture","object":"chat.completion","created":1,"model":"fixture-model",
     "choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}],
     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
""".trimIndent()

private fun toolCallResponse(nonce: String) = """
    {"id":"chatcmpl-tool","object":"chat.completion","created":1,"model":"fixture-model",
     "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
       {"id":"call-fixture","type":"function","function":{"name":"provider_capability_probe","arguments":"{\"nonce\":\"$nonce\"}"}}
     ]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
""".trimIndent()

private fun HttpExchange.respondJson(body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
    close()
}

private data class CapturedRequest(
    val path: String,
    val authorization: String?,
    val body: String,
)
