package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemSummaryDto
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalControlServerSecurityTest {
    @Test
    fun `server binds only ephemeral IPv4 loopback and rotates session identity across restart`() {
        val server = LocalControlServer(StubMapControlService(), "0.1.2")
        val first = server.start()
        val firstCredentials = server.sessionCredentials()
        val firstAuthorization = firstCredentials.authorizationHeaderValue()

        assertEquals("127.0.0.1", first.boundAddress.hostAddress)
        assertTrue(first.boundAddress.isLoopbackAddress)
        assertFalse(first.boundAddress.isAnyLocalAddress)
        assertTrue(first.port > 0)
        assertEquals(LocalControlServerState.RUNNING, server.state)
        assertEquals(43, firstAuthorization.removePrefix("Bearer ").length)
        assertFalse(firstAuthorization.contains('='))
        assertFailsWith<IllegalStateException> { server.start() }

        server.stop()
        server.stop()
        assertEquals(LocalControlServerState.STOPPED, server.state)
        assertTrue(server.lastExecutorTerminated)
        assertFailsWith<IllegalStateException> { firstCredentials.authorizationHeaderValue() }

        val second = server.start()
        val secondAuthorization = server.sessionCredentials().authorizationHeaderValue()
        assertNotEquals(first.instanceId, second.instanceId)
        assertNotEquals(firstAuthorization, secondAuthorization)
        assertEquals(401, rawRequest(server, LocalControlOperation.HANDSHAKE.path, authorization = firstAuthorization).status)
        assertEquals(200, LocalControlTestClient(server).handshake().status)
        server.stop()
    }

    @Test
    fun `wildcard and non-loopback binding configurations fail closed`() {
        listOf("0.0.0.0", "192.0.2.1", "::1").forEach { address ->
            val server = LocalControlServer(StubMapControlService(), "0.1.2")
            server.bindAddressProvider = { InetAddress.getByName(address) }
            assertFailsWith<IllegalArgumentException>(address) { server.start() }
            assertEquals(LocalControlServerState.STOPPED, server.state)
            assertEquals(null, server.boundAddress)
        }
    }

    @Test
    fun `authentication browser boundary methods and media type are fail closed without CORS`() {
        val server = LocalControlServer(StubMapControlService(), "0.1.2")
        server.start()
        try {
            val token = server.sessionCredentials().authorizationHeaderValue()
            assertEquals(200, LocalControlTestClient(server).handshake().status)

            val missing = rawRequest(server, LocalControlOperation.HANDSHAKE.path, authorization = null)
            val malformed = rawRequest(server, LocalControlOperation.HANDSHAKE.path, authorization = "Basic abc")
            val wrong = rawRequest(server, LocalControlOperation.HANDSHAKE.path, authorization = "Bearer AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            listOf(missing, malformed, wrong).forEach {
                assertEquals(401, it.status)
                assertTrue(it.body.contains("UNAUTHORIZED"))
                assertFalse(it.body.contains(token))
            }

            val origin = rawRequest(server, LocalControlOperation.HANDSHAKE.path, origin = "https://attacker.invalid")
            assertEquals(403, origin.status)
            assertFalse(origin.headers.map().keys.any { it.equals("Access-Control-Allow-Origin", true) })

            val options = rawRequest(
                server,
                LocalControlOperation.HANDSHAKE.path,
                method = "OPTIONS",
                body = HttpRequest.BodyPublishers.noBody(),
                contentType = null,
            )
            assertEquals(405, options.status)
            assertFalse(options.headers.map().keys.any { it.startsWith("Access-Control-Allow", true) })

            assertEquals(
                415,
                rawRequest(server, LocalControlOperation.HANDSHAKE.path, contentType = "text/plain").status,
            )
            assertEquals(
                200,
                rawRequest(server, LocalControlOperation.HANDSHAKE.path, contentType = "application/json; charset=utf-8").status,
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `body limit is enforced before unbounded reads and JSON parsing is strict`() {
        val service = object : StubMapControlService() {
            override suspend fun searchSystems(request: SearchSystemsRequest) =
                ControlResult.Success(request.requestId, listOf(SystemSummaryDto(1, "Jita", 1, 1, 0.9)))
        }
        val server = LocalControlServer(service, "0.1.2")
        server.start()
        try {
            val prefix = "{\"requestId\":\"limit-1\",\"query\":\""
            val suffix = "\"}"
            val exact = (prefix + "x".repeat(LocalControlProtocol.REQUEST_BODY_LIMIT_BYTES - prefix.length - suffix.length) + suffix)
                .toByteArray()
            assertEquals(LocalControlProtocol.REQUEST_BODY_LIMIT_BYTES, exact.size)
            val acceptedAtBoundary = rawRequest(
                server,
                LocalControlOperation.SEARCH_SYSTEM.path,
                HttpRequest.BodyPublishers.ofByteArray(exact),
            )
            assertEquals(200, acceptedAtBoundary.status)
            assertFalse(acceptedAtBoundary.body.contains("PAYLOAD_TOO_LARGE"))

            val oversized = ByteArray(LocalControlProtocol.REQUEST_BODY_LIMIT_BYTES + 1) { 'x'.code.toByte() }
            assertEquals(
                413,
                rawRequest(
                    server,
                    LocalControlOperation.SEARCH_SYSTEM.path,
                    HttpRequest.BodyPublishers.ofByteArray(oversized),
                ).status,
            )
            val chunked = rawRequest(
                server,
                LocalControlOperation.SEARCH_SYSTEM.path,
                HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(oversized) },
            )
            assertEquals(413, chunked.status)
            assertTrue(chunked.body.contains("PAYLOAD_TOO_LARGE"))

            val malformed = rawRequest(
                server,
                LocalControlOperation.SEARCH_SYSTEM.path,
                HttpRequest.BodyPublishers.ofString("{broken"),
            )
            val unknown = rawRequest(
                server,
                LocalControlOperation.SEARCH_SYSTEM.path,
                HttpRequest.BodyPublishers.ofString("{\"requestId\":\"a\",\"query\":\"Jita\",\"className\":\"X\"}"),
            )
            val missing = rawRequest(
                server,
                LocalControlOperation.SEARCH_SYSTEM.path,
                HttpRequest.BodyPublishers.ofString("{\"requestId\":\"a\"}"),
            )
            val malformedUtf8 = rawRequest(
                server,
                LocalControlOperation.SEARCH_SYSTEM.path,
                HttpRequest.BodyPublishers.ofByteArray(
                    "{\"requestId\":\"utf8-1\",\"query\":\"".toByteArray() +
                        byteArrayOf(0xC3.toByte(), 0x28) + "\"}".toByteArray(),
                ),
            )
            val queryString = rawRequest(server, LocalControlOperation.HANDSHAKE.path + "?command=invoke")
            listOf(malformed, unknown, missing, malformedUtf8, queryString).forEach {
                assertEquals(400, it.status)
                assertTrue(it.body.contains("INVALID_ARGUMENT"))
                assertFalse(it.body.contains("broken"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `only explicit endpoint allowlist exists and handshake reveals no internals`() {
        val server = LocalControlServer(StubMapControlService(), "0.1.2")
        server.start()
        try {
            LocalControlOperation.allowedPaths.forEach { path ->
                val response = rawRequest(server, path, HttpRequest.BodyPublishers.ofString("{}"))
                assertNotEquals(404, response.status, path)
            }
            listOf("/invoke", "/execute", "/call", "/rpc", "/sql", "/file", "/shell", "/v1/query/unknown").forEach { path ->
                val response = rawRequest(server, path)
                assertEquals(404, response.status, path)
                assertTrue(response.body.contains("\"ok\":false"))
            }

            val handshake = LocalControlTestClient(server).handshake("capabilities-1")
            assertEquals(200, handshake.status)
            assertTrue(handshake.body.contains("\"protocolVersion\":1"))
            assertTrue(handshake.body.contains("\"appVersion\":\"0.1.2\""))
            assertTrue(handshake.body.contains("\"controlApiVersion\":1"))
            assertNotNull(server.sessionMetadata?.instanceId)
            listOf("sessionSecret", "repository", "static.db", "user.db", "className", "stackTrace").forEach {
                assertFalse(handshake.body.contains(it, ignoreCase = true), it)
            }
        } finally {
            server.stop()
        }
    }
}
