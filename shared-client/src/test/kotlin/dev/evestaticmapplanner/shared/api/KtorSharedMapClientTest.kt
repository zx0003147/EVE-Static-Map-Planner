package dev.evestaticmapplanner.shared.api

import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.protocol.MARKER_ID
import dev.evestaticmapplanner.shared.protocol.ME_JSON
import dev.evestaticmapplanner.shared.protocol.META_JSON
import dev.evestaticmapplanner.shared.protocol.SNAPSHOT_JSON
import dev.evestaticmapplanner.shared.protocol.TOKEN_ID
import dev.evestaticmapplanner.shared.protocol.USER_ID
import dev.evestaticmapplanner.shared.protocol.WORKSPACE_ID
import dev.evestaticmapplanner.shared.protocol.WORKSPACE_JSON
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtorSharedMapClientTest {
    private val server = SharedServerUrl.parse("https://example.com")

    @Test
    fun `exchange encodes invite and decodes one-time token`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/auth/exchange-invite", request.url.encodedPath)
            val body = request.body as TextContent
            assertTrue(body.text.contains("esm_inv_test"))
            respond(
                """{"accessToken":"esm_dev_test","tokenId":"$TOKEN_ID","expiresAt":"2026-12-01T00:00:00Z","user":{"userId":"$USER_ID","displayName":"Pilot"},"workspace":$WORKSPACE_JSON}""",
                HttpStatusCode.Created,
                JSON_HEADERS,
            )
        }
        KtorSharedMapClient(testClient(engine)).use { client ->
            SecretValue.from("esm_inv_test").use { invite ->
                client.exchangeInvite(server, invite, "Laptop").accessToken.use { token ->
                    token.useString { assertEquals("esm_dev_test", it) }
                }
            }
        }
    }

    @Test
    fun `authenticated reads inject bearer and decode resources`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer esm_dev_test", request.headers[HttpHeaders.Authorization])
            assertTrue(request.headers["X-Request-Id"]?.isNotBlank() == true)
            when (request.url.encodedPath) {
                "/api/v1/me" -> respond(ME_JSON, headers = JSON_HEADERS)
                "/api/v1/workspaces" -> respond("""{"workspaces":[$WORKSPACE_JSON]}""", headers = JSON_HEADERS)
                "/api/v1/workspaces/$WORKSPACE_ID/markers" -> respond(SNAPSHOT_JSON, headers = JSON_HEADERS)
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        KtorSharedMapClient(testClient(engine)).use { client ->
            SecretValue.from("esm_dev_test").use { token ->
                assertEquals(USER_ID, client.getMe(server, token).user.userId)
                assertEquals(WORKSPACE_ID, client.getWorkspaces(server, token).single().workspaceId)
                assertEquals(setOf(MARKER_ID), client.getMarkerSnapshot(server, token, WORKSPACE_ID).markers.keys)
            }
        }
    }

    @Test
    fun `maps auth server and malformed JSON errors`() = runTest {
        suspend fun mapped(status: HttpStatusCode, body: String): SharedMapError {
            val engine = MockEngine { respond(body, status, JSON_HEADERS) }
            return assertFailsWith<SharedMapException> {
                KtorSharedMapClient(testClient(engine)).use { it.getMeta(server) }
            }.error
        }
        assertTrue(mapped(HttpStatusCode.Unauthorized, errorJson("TOKEN_REVOKED")) is SharedMapError.Authentication)
        assertTrue(mapped(HttpStatusCode.InternalServerError, errorJson("INTERNAL_ERROR")) is SharedMapError.Server)
        assertTrue(mapped(HttpStatusCode.OK, "not-json") is SharedMapError.InvalidResponse)
    }

    @Test
    fun `request timeout maps to network error`() = runTest {
        val engine = MockEngine {
            delay(100)
            respond(META_JSON, headers = JSON_HEADERS)
        }
        val http = testClient(engine, requestTimeoutMillis = 10)
        val error = assertFailsWith<SharedMapException> {
            KtorSharedMapClient(http).use { it.getMeta(server) }
        }
        assertTrue(error.error is SharedMapError.Network)
    }

    @Test
    fun `closed credential is rejected without an HTTP call`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(ME_JSON, headers = JSON_HEADERS)
        }
        val token = SecretValue.from("esm_dev_test").also(AutoCloseable::close)
        val error = assertFailsWith<SharedMapException> {
            KtorSharedMapClient(testClient(engine)).use { it.getMe(server, token) }
        }
        assertTrue(error.error is SharedMapError.InvalidConfiguration)
        assertEquals(0, calls)
    }

    private fun testClient(engine: MockEngine, requestTimeoutMillis: Long = 1_000): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(KtorSharedMapClient.PROTOCOL_JSON) }
        install(HttpTimeout) { this.requestTimeoutMillis = requestTimeoutMillis }
    }

    private fun errorJson(code: String) =
        """{"code":"$code","message":"Safe failure","requestId":"01991d75-b87a-722f-ad20-24ac849c3a21"}"""

    companion object {
        private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
