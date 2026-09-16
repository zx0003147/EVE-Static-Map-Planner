package dev.evestaticmapplanner.embeddedai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.java.JavaKoogHttpClient
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
import kotlinx.serialization.json.Json

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
            assertTrue(requests.all { it.contentType == "application/json" })
            assertTrue(requests.all { it.authorization == "Bearer fixture-api-key" })
            assertTrue(requests[1].body.contains("provider_capability_probe"))
            assertTrue(requests[2].body.contains("probe-ok"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `native DeepSeek uses its official completion endpoint with JSON media type`() = runBlocking {
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        val server = recordingErrorServer(requests, path = "/chat/completions", status = 401)
        server.start()
        try {
            val redirectingFactory = RedirectingKoogHttpClientFactory(
                localBaseUrl = "http://127.0.0.1:${server.address.port}",
            )
            val config = AiProviderConfig.normalized(
                providerType = AiProviderType.DEEPSEEK,
                baseUrl = null,
                modelId = "deepseek-flash",
                temperature = 0.0,
                requestTimeoutSeconds = 10,
            )
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(
                    DefaultAiClientFactory(redirectingFactory),
                    nonceFactory = { "00000000-0000-0000-0000-000000000123" },
                ).test(config, secret)
            }

            assertEquals(AiProviderErrorCode.INVALID_CREDENTIAL, result.errorCode)
            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status)
            assertEquals(listOf("https://api.deepseek.com"), redirectingFactory.requestedBaseUrls)
            assertEquals(1, requests.size)
            assertTrue(requests.all { it.path == "/chat/completions" })
            assertTrue(requests.all { it.contentType == "application/json" })
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

    @Test
    fun `rate limit is mapped to a safe retry message`() = runBlocking {
        val rawBody = """{"error":{"message":"private quota and account details"}}"""
        val server = errorServer(429, rawBody)
        server.start()
        try {
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(DefaultAiClientFactory()).test(compatibleConfig(server), secret)
            }

            assertEquals(AiProviderErrorCode.RATE_LIMITED, result.errorCode)
            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status)
            assertEquals(AiConnectionCheckStatus.FAILED, result.model.status)
            assertTrue(result.model.message.contains("rate limit"))
            assertFalse(result.toString().contains("private quota"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP 401 proves reachability and maps to invalid credential`() = runBlocking {
        val server = errorServer(401, """{"error":{"message":"private authentication detail"}}""")
        server.start()
        try {
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(DefaultAiClientFactory()).test(compatibleConfig(server), secret)
            }

            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status)
            assertEquals(AiConnectionCheckStatus.FAILED, result.model.status)
            assertEquals(AiConnectionCheckStatus.NOT_RUN, result.toolCalling.status)
            assertEquals(AiProviderErrorCode.INVALID_CREDENTIAL, result.errorCode)
            assertFalse(result.toString().contains("private authentication detail"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP 404 proves reachability and maps to model not found`() = runBlocking {
        val server = errorServer(404, """{"error":{"message":"private model detail"}}""")
        server.start()
        try {
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(DefaultAiClientFactory()).test(compatibleConfig(server), secret)
            }

            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status)
            assertEquals(AiConnectionCheckStatus.FAILED, result.model.status)
            assertEquals(AiProviderErrorCode.MODEL_NOT_FOUND, result.errorCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP 400 request rejection is not reported as unreachable`() = runBlocking {
        val server = errorServer(400, """{"error":{"message":"private request detail"}}""")
        server.start()
        try {
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(DefaultAiClientFactory()).test(compatibleConfig(server), secret)
            }

            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status)
            assertEquals(AiConnectionCheckStatus.FAILED, result.model.status)
            assertEquals(AiProviderErrorCode.PROVIDER_ERROR, result.errorCode)
            assertFalse(result.connection.message.contains("could not be reached"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP 400 during tool probe maps to unsupported tool calling`() = runBlocking {
        val server = toolProbeErrorServer()
        server.start()
        try {
            val result = SecretValue.from("fixture-api-key").use { secret ->
                KoogAiConnectionTester(DefaultAiClientFactory(), nonceFactory = { "fixed-nonce" })
                    .test(compatibleConfig(server), secret)
            }

            assertEquals(AiConnectionCheckStatus.PASSED, result.connection.status)
            assertEquals(AiConnectionCheckStatus.PASSED, result.model.status)
            assertEquals(AiConnectionCheckStatus.FAILED, result.toolCalling.status)
            assertEquals(AiProviderErrorCode.TOOL_CALLING_UNSUPPORTED, result.errorCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `unreachable custom endpoint is distinguished from provider network failure`() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val config = AiProviderConfig.normalized(
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:$port/v1",
            modelId = "fixture-model",
            requestTimeoutSeconds = 5,
        )

        val result = SecretValue.from("fixture-api-key").use { secret ->
            KoogAiConnectionTester(DefaultAiClientFactory()).test(config, secret)
        }

        assertEquals(AiProviderErrorCode.BASE_URL_UNREACHABLE, result.errorCode)
        assertTrue(result.connection.message.contains("Base URL"))
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
    path: String = "/v1/chat/completions",
): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext(path) { exchange ->
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        requests += CapturedRequest(
            path = exchange.requestURI.path,
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
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

private fun toolProbeErrorServer(): HttpServer =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { exchange ->
            val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            if (requestBody.contains("provider_capability_probe")) {
                val body = """{"error":{"message":"private tool schema detail"}}"""
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            } else {
                exchange.respondJson(completionResponse("OK"))
            }
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

private fun recordingErrorServer(
    requests: MutableList<CapturedRequest>,
    path: String,
    status: Int,
): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext(path) { exchange ->
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        requests += CapturedRequest(
            path = exchange.requestURI.path,
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            body = body,
        )
        val response = """{"error":{"message":"fixture rejection"}}""".toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
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
    val contentType: String?,
    val body: String,
)

private class RedirectingKoogHttpClientFactory(
    private val localBaseUrl: String,
) : KoogHttpClient.Factory {
    private val delegate = JavaKoogHttpClient.Factory()
    val requestedBaseUrls = CopyOnWriteArrayList<String>()

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient {
        requestedBaseUrls += baseUrl
        return delegate.create(
            clientName = clientName,
            baseUrl = localBaseUrl,
            headers = headers,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json,
        )
    }
}
