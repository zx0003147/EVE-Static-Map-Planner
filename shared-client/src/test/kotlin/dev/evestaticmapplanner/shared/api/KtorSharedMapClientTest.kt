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
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMarkerDraft
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
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
import java.io.IOException
import java.util.UUID
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

    @Test
    fun `marker writes use frozen bodies versions and idempotency keys`() = runTest {
        val key = UUID.fromString("a3c1be66-724c-46f0-bb8a-678817343a58")
        var requestNumber = 0
        val engine = MockEngine { request ->
            requestNumber++
            assertEquals(key.toString(), request.headers["Idempotency-Key"])
            assertEquals("Bearer esm_dev_test", request.headers[HttpHeaders.Authorization])
            when (requestNumber) {
                1 -> {
                    assertEquals("POST", request.method.value)
                    assertEquals("/api/v1/workspaces/$WORKSPACE_ID/markers", request.url.encodedPath)
                    val body = (request.body as TextContent).text
                    assertTrue(body.contains("\"systemId\":30004759"))
                    assertTrue(!body.contains("markerId"))
                    respond(MARKER_JSON, HttpStatusCode.Created, JSON_HEADERS)
                }
                2 -> {
                    assertEquals("PATCH", request.method.value)
                    val body = (request.body as TextContent).text
                    assertTrue(body.contains("\"expectedVersion\":1"))
                    assertTrue(!body.contains("systemId"))
                    respond(MARKER_JSON.replace("\"version\":1", "\"version\":2"), headers = JSON_HEADERS)
                }
                else -> {
                    assertEquals("DELETE", request.method.value)
                    assertEquals("1", request.url.parameters["expectedVersion"])
                    respond("", HttpStatusCode.NoContent)
                }
            }
        }
        KtorSharedMapClient(testClient(engine)).use { client ->
            SecretValue.from("esm_dev_test").use { token ->
                val draft = SharedMarkerDraft("Staging", SharedMarkerColor.BLUE, listOf("ops"), "private note")
                assertEquals(MARKER_ID, client.createSharedMarker(server, token, WORKSPACE_ID, 30_004_759, draft, key).markerId)
                assertEquals(2, client.updateSharedMarker(server, token, WORKSPACE_ID, MARKER_ID, 1, draft, key).version)
                client.deleteSharedMarker(server, token, WORKSPACE_ID, MARKER_ID, 1, key)
            }
        }
    }

    @Test
    fun `response lost retry reuses the same marker idempotency key`() = runTest {
        val keys = mutableListOf<String?>()
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            keys += request.headers["Idempotency-Key"]
            if (calls == 1) throw IOException("response lost")
            respond(MARKER_JSON, HttpStatusCode.Created, JSON_HEADERS)
        }
        KtorSharedMapClient(testClient(engine)).use { client ->
            SecretValue.from("esm_dev_test").use { token ->
                val marker = client.createSharedMarker(
                    server,
                    token,
                    WORKSPACE_ID,
                    30_004_759,
                    SharedMarkerDraft("Staging", SharedMarkerColor.BLUE, emptyList(), null),
                    UUID.randomUUID(),
                )
                assertEquals(MARKER_ID, marker.markerId)
            }
        }
        assertEquals(2, calls)
        assertEquals(1, keys.distinct().size)
    }

    @Test
    fun `marker and admin errors retain their safe semantic category`() = runTest {
        suspend fun mapped(code: String, details: String? = null): SharedMapError {
            val detailField = details?.let { ",\"details\":$it" }.orEmpty()
            val engine = MockEngine {
                respond(
                    "{\"code\":\"$code\",\"message\":\"Safe failure\",\"requestId\":\"01991d75-b87a-722f-ad20-24ac849c3a21\"$detailField}",
                    HttpStatusCode.Conflict,
                    JSON_HEADERS,
                )
            }
            return assertFailsWith<SharedMapException> {
                KtorSharedMapClient(testClient(engine)).use { client ->
                    SecretValue.from("esm_dev_test").use { token ->
                        client.createSharedMarker(
                            server,
                            token,
                            WORKSPACE_ID,
                            30_004_759,
                            SharedMarkerDraft("Staging", SharedMarkerColor.BLUE, emptyList(), null),
                            UUID.randomUUID(),
                        )
                    }
                }
            }.error
        }
        assertTrue(mapped("MARKER_ALREADY_EXISTS") is SharedMapError.MarkerAlreadyExists)
        val conflict = mapped("MARKER_VERSION_CONFLICT", "{\"currentMarker\":$MARKER_JSON}")
        assertEquals(1, (conflict as SharedMapError.MarkerVersionConflict).currentMarker?.version)
        assertTrue(mapped("LAST_ADMIN_REQUIRED") is SharedMapError.LastAdminRequired)
    }

    @Test
    fun `admin member and invite endpoints use role and one-time secret DTOs`() = runTest {
        val memberId = "01991d62-1fcb-70d0-858b-1d65f6ce3cf7"
        val key = UUID.randomUUID()
        var call = 0
        val engine = MockEngine { request ->
            call++
            when (call) {
                1 -> respond("{\"members\":[$MEMBER_JSON]}", headers = JSON_HEADERS)
                2 -> {
                    assertTrue((request.body as TextContent).text.contains("\"role\":\"VIEWER\""))
                    respond(MEMBER_JSON, HttpStatusCode.Created, JSON_HEADERS)
                }
                else -> respond(
                    "{\"inviteId\":\"01991d7c-c79d-7771-904d-72ce4212a75a\",\"inviteToken\":\"esm_inv_once\",\"memberId\":\"$memberId\",\"expiresAt\":\"2026-09-04T00:00:00Z\",\"createdAt\":\"2026-09-01T00:00:00Z\"}",
                    HttpStatusCode.Created,
                    JSON_HEADERS,
                )
            }
        }
        KtorSharedMapClient(testClient(engine)).use { client ->
            SecretValue.from("esm_dev_test").use { token ->
                assertEquals(memberId, client.getMembers(server, token, WORKSPACE_ID).single().memberId)
                assertEquals(SharedWorkspaceRole.VIEWER, client.createMember(
                    server, token, WORKSPACE_ID, "Scout", SharedWorkspaceRole.VIEWER, key,
                ).role)
                val (_, secret) = client.createInvite(
                    server, token, WORKSPACE_ID, memberId, 72, UUID.randomUUID(),
                )
                secret.use { it.useString { raw -> assertEquals("esm_inv_once", raw) } }
            }
        }
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
        private val MARKER_JSON =
            "{\"markerId\":\"$MARKER_ID\",\"workspaceId\":\"$WORKSPACE_ID\",\"systemId\":30004759,\"name\":\"Staging\",\"color\":\"BLUE\",\"tags\":[\"ops\"],\"notes\":\"private note\",\"createdBy\":{\"userId\":\"$USER_ID\",\"displayName\":\"Pilot\"},\"updatedBy\":{\"userId\":\"$USER_ID\",\"displayName\":\"Pilot\"},\"createdAt\":\"2026-09-01T00:00:00Z\",\"updatedAt\":\"2026-09-01T00:00:00Z\",\"version\":1}"
        private val MEMBER_JSON =
            "{\"memberId\":\"01991d62-1fcb-70d0-858b-1d65f6ce3cf7\",\"userId\":\"$USER_ID\",\"displayName\":\"Scout\",\"role\":\"VIEWER\",\"version\":1,\"createdAt\":\"2026-09-01T00:00:00Z\",\"updatedAt\":\"2026-09-01T00:00:00Z\",\"revokedAt\":null}"
    }
}
