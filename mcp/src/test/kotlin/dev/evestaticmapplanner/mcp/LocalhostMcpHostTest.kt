package dev.evestaticmapplanner.mcp

import dev.evestaticmapplanner.control.transport.LocalControlClientResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@OptIn(ExperimentalMcpApi::class)
class LocalhostMcpHostTest {
    @Test
    fun `production endpoint is fixed to IPv4 loopback port 27892`() {
        val configuration = LocalhostMcpHostConfiguration()

        assertEquals(27892, configuration.port)
        assertEquals(LocalhostMcpHost.ENDPOINT, configuration.endpoint)
        assertEquals("http://127.0.0.1:27892/mcp", configuration.endpoint)
    }

    @Test
    fun `official client initializes lists exactly 30 tools and calls search over stateless HTTP`(): Unit = runBlocking {
        withOfficialClientHost { port, client ->
            val httpToolNames = client.listTools().tools.map { it.name }
            assertEquals(30, httpToolNames.size)
            assertEquals(McpToolCatalog.names, httpToolNames)
            val result = client.callTool("search_system", mapOf("query" to "Jita"))
            assertFalse(result.isError == true)
            assertEquals("Jita", result.structuredContent
                ?.get("systems")
                ?.let { it as JsonArray }
                ?.single()
                ?.let { it as kotlinx.serialization.json.JsonObject }
                ?.get("canonicalName")
                ?.let { it as kotlinx.serialization.json.JsonPrimitive }
                ?.content)
            assertEquals("http://127.0.0.1:$port/mcp", LocalhostMcpHostConfiguration(port = port).endpoint)
        }
    }

    @Test
    fun `two independent clients can use the stateless endpoint concurrently`(): Unit = runBlocking {
        val port = availablePort()
        val host = testHost(port)
        val clients = (1..2).map { index ->
            val http = HttpClient(CIO) { install(SSE) }
            Triple(
                http,
                Client(Implementation("localhost-http-client-$index", "1.0")),
                StreamableHttpClientTransport(http, "http://127.0.0.1:$port/mcp"),
            )
        }
        try {
            host.start()
            clients.map { (_, client, transport) -> async { client.connect(transport) } }.awaitAll()
            val results = clients.map { (_, client, _) ->
                async {
                    val names = client.listTools().tools.map { it.name }
                    val search = client.callTool("search_system", mapOf("query" to "Jita"))
                    names to search
                }
            }.awaitAll()
            assertTrue(results.all { (names, _) -> names == McpToolCatalog.names })
            assertTrue(results.all { (_, search) -> search.isError != true })
        } finally {
            clients.forEach { (http, client, _) ->
                runCatching { client.close() }
                http.close()
            }
            host.shutdown()
        }
    }

    @Test
    fun `security and request validation reject origin host options malformed and oversized requests`(): Unit = runBlocking {
        val port = availablePort()
        val host = testHost(port)
        try {
            host.start()
            val allowed = post(port, initializeRequest())
            assertEquals(200, allowed.statusCode())
            assertTrue(allowed.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
            assertEquals(403, post(port, initializeRequest(), mapOf("Origin" to "http://127.0.0.1:$port")).statusCode())
            assertEquals(403, post(port, initializeRequest(), mapOf("Origin" to "https://evil.example")).statusCode())
            assertEquals(405, request(port, "OPTIONS", "").statusCode())
            assertEquals(400, post(port, "{").statusCode())
            val invalidJsonRpc = post(port, invalidJsonRpcRequest())
            assertTrue(invalidJsonRpc.statusCode() !in 200..299 || "\"error\"" in invalidJsonRpc.body())
            assertEquals(415, post(port, initializeRequest(), contentType = "text/plain").statusCode())
            assertEquals(415, postWithoutContentType(port, initializeRequest()).statusCode())
            assertEquals(413, post(port, "x".repeat(64 * 1024 + 1)).statusCode())
            assertEquals(403, rawStatus(port, "localhost:$port"))
            assertEquals(200, post(port, initializeRequest()).statusCode())
        } finally {
            host.shutdown()
        }
    }

    @Test
    fun `only eight requests enter and the ninth is rejected without reaching the tool`(): Unit = runBlocking {
        val port = availablePort()
        val mapClient = BlockingSearchMapClient()
        val host = LocalhostMcpHost.createForTest(
            LocalhostMcpHostConfiguration(port = port),
            clientFactory = { mapClient },
        )
        val pending = mutableListOf<CompletableFuture<HttpResponse<String>>>()
        try {
            host.start()
            repeat(8) { pending += postAsync(port, searchRequest(it + 1)) }
            waitUntil { mapClient.entered.get() == 8 }

            assertEquals(429, post(port, searchRequest(9)).statusCode())
            assertEquals(8, mapClient.entered.get())

            mapClient.release.complete(Unit)
            assertTrue(pending.map { it.join().statusCode() }.all { it == 200 })
        } finally {
            mapClient.release.complete(Unit)
            pending.forEach { it.cancel(true) }
            host.shutdown()
        }
    }

    @Test
    fun `shutdown closes endpoint and shared Control client once`(): Unit = runBlocking {
        val port = availablePort()
        val mapClient = CloseCountingMapClient()
        val host = LocalhostMcpHost.createForTest(
            LocalhostMcpHostConfiguration(port = port),
            clientFactory = { mapClient },
        )
        host.start()
        assertIs<LocalhostMcpHostStatus.Available>(host.status.value)

        host.shutdown()
        host.shutdown()

        assertEquals(LocalhostMcpHostStatus.Stopped, host.status.value)
        assertEquals(1, mapClient.closeCount.get())
        assertFails { post(port, initializeRequest()) }
    }

    @Test
    fun `tool timeout is bounded and returns a safe gateway timeout`(): Unit = runBlocking {
        val port = availablePort()
        val mapClient = BlockingSearchMapClient()
        val host = LocalhostMcpHost.createForTest(
            LocalhostMcpHostConfiguration(port = port, requestTimeoutMillis = 100),
            clientFactory = { mapClient },
        )
        try {
            host.start()
            assertEquals(504, post(port, searchRequest(1)).statusCode())
        } finally {
            mapClient.release.complete(Unit)
            host.shutdown()
        }
    }

    @Test
    fun `closing map cancels an active request and releases listener`(): Unit = runBlocking {
        val port = availablePort()
        val mapClient = BlockingSearchMapClient()
        val host = LocalhostMcpHost.createForTest(
            LocalhostMcpHostConfiguration(
                port = port,
                shutdownGraceMillis = 50,
                shutdownTimeoutMillis = 250,
            ),
            clientFactory = { mapClient },
        )
        val request = try {
            host.start()
            postAsync(port, searchRequest(1)).also {
                waitUntil { mapClient.entered.get() == 1 }
            }
        } catch (failure: Throwable) {
            mapClient.release.complete(Unit)
            host.shutdown()
            throw failure
        }

        val elapsed = measureTime { host.shutdown() }
        assertTrue(elapsed < 2.seconds, "Shutdown took $elapsed")
        ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { rebound ->
            assertEquals(port, rebound.localPort)
        }
        mapClient.release.complete(Unit)
        runCatching { request.join() }
    }

    @Test
    fun `port conflict is nonfatal and a second map remains running`(): Unit = runBlocking {
        val port = availablePort()
        ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use {
            val occupiedHost = testHost(port)
            occupiedHost.start()
            assertEquals(LocalhostMcpHostStatus.UnavailablePortInUse, occupiedHost.status.value)
            occupiedHost.shutdown()
        }

        val firstMap = testHost(port)
        val secondMap = testHost(port)
        try {
            firstMap.start()
            secondMap.start()
            assertIs<LocalhostMcpHostStatus.Available>(firstMap.status.value)
            assertEquals(LocalhostMcpHostStatus.UnavailablePortInUse, secondMap.status.value)
            assertEquals(200, post(port, initializeRequest()).statusCode())

            firstMap.shutdown()
            secondMap.start()
            assertEquals(LocalhostMcpHostStatus.UnavailablePortInUse, secondMap.status.value)
            assertFails { post(port, initializeRequest()) }
        } finally {
            secondMap.shutdown()
            firstMap.shutdown()
        }
    }

    @Test
    fun `handler exception is isolated and map endpoint remains alive`(): Unit = runBlocking {
        val port = availablePort()
        val host = LocalhostMcpHost.createForTest(
            LocalhostMcpHostConfiguration(port = port),
            clientFactory = { ThrowingSearchMapClient() },
        )
        try {
            host.start()
            val failure = post(port, searchRequest(1))
            assertEquals(200, failure.statusCode())
            assertTrue("\"error\"" in failure.body())
            assertFalse("sensitive fixture failure" in failure.body())
            assertEquals(200, post(port, initializeRequest()).statusCode())
            assertIs<LocalhostMcpHostStatus.Available>(host.status.value)
        } finally {
            host.shutdown()
        }
    }

    @Test
    fun `production connector contract rejects non-loopback binding`() {
        assertFailsWith<IllegalArgumentException> {
            LocalhostMcpHostConfiguration(bindAddress = "0.0.0.0")
        }
    }
}

private suspend fun withOfficialClientHost(block: suspend (Int, Client) -> Unit) {
    val port = availablePort()
    val host = testHost(port)
    val httpClient = HttpClient(CIO) { install(SSE) }
    val client = Client(Implementation("localhost-http-test", "1.0"))
    try {
        host.start()
        client.connect(StreamableHttpClientTransport(httpClient, "http://127.0.0.1:$port/mcp"))
        block(port, client)
    } finally {
        runCatching { client.close() }
        httpClient.close()
        host.shutdown()
    }
}

private fun testHost(port: Int): LocalhostMcpHost = LocalhostMcpHost.createForTest(
    configuration = LocalhostMcpHostConfiguration(port = port),
    clientFactory = { HttpProtocolMapClient() },
)

private open class HttpProtocolMapClient : RecordingProtocolClient() {
    override suspend fun searchSystem(query: String): LocalControlClientResult = searchResult(query)
}

private class BlockingSearchMapClient : HttpProtocolMapClient() {
    val entered = AtomicInteger()
    val release = CompletableDeferred<Unit>()

    override suspend fun searchSystem(query: String): LocalControlClientResult {
        entered.incrementAndGet()
        release.await()
        return searchResult(query)
    }
}

private class CloseCountingMapClient : HttpProtocolMapClient() {
    val closeCount = AtomicInteger()
    override fun close() {
        closeCount.incrementAndGet()
    }
}

private class ThrowingSearchMapClient : HttpProtocolMapClient() {
    override suspend fun searchSystem(query: String): LocalControlClientResult = error("sensitive fixture failure")
}

private fun searchResult(query: String) = LocalControlClientResult.Success(
    JsonArray(listOf(buildJsonObject {
        put("systemId", 30000142)
        put("name", query)
        put("regionId", 10000002)
        put("constellationId", 20000020)
        put("securityStatus", 0.9)
    })),
    null,
)

private val rawClient: JdkHttpClient = JdkHttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build()

private fun post(
    port: Int,
    body: String,
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json",
): HttpResponse<String> =
    rawClient.send(httpRequest(port, "POST", body, headers, contentType), HttpResponse.BodyHandlers.ofString())

private fun request(port: Int, method: String, body: String): HttpResponse<String> =
    rawClient.send(httpRequest(port, method, body), HttpResponse.BodyHandlers.ofString())

private fun postWithoutContentType(port: Int, body: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
        .timeout(Duration.ofSeconds(5))
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    return rawClient.send(request, HttpResponse.BodyHandlers.ofString())
}

private fun postAsync(port: Int, body: String): CompletableFuture<HttpResponse<String>> =
    rawClient.sendAsync(httpRequest(port, "POST", body), HttpResponse.BodyHandlers.ofString())

private fun httpRequest(
    port: Int,
    method: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json",
): HttpRequest {
    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
        .timeout(Duration.ofSeconds(5))
        .header("Accept", "application/json, text/event-stream")
        .header("Content-Type", contentType)
        .method(method, HttpRequest.BodyPublishers.ofString(body))
    headers.forEach(builder::header)
    return builder.build()
}

private fun rawStatus(port: Int, host: String): Int = Socket("127.0.0.1", port).use { socket ->
    socket.soTimeout = 3_000
    val request = "POST /mcp HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n" +
        "Accept: application/json, text/event-stream\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
    socket.getOutputStream().flush()
    val statusLine = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
    statusLine.split(' ')[1].toInt()
}

private suspend fun waitUntil(condition: () -> Boolean) {
    repeat(200) {
        if (condition()) return
        delay(25)
    }
    error("Timed out waiting for condition")
}

private fun initializeRequest(): String =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"raw-test","version":"1.0"}}}"""

private fun invalidJsonRpcRequest(): String =
    """{"jsonrpc":"1.0","id":1,"method":"initialize","params":{}}"""

private fun searchRequest(id: Int): String =
    """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"search_system","arguments":{"query":"Jita"}}}"""

private fun availablePort(): Int = ServerSocket(0).use { it.localPort }
