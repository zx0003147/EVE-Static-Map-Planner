package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.shared.protocol.CreateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.UpdateSharedMarkerRequestDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserSharedMarkerTransportTest {
    @Test
    fun `browser transport preserves invite exchange bearer CRUD and optimistic version protocol`() = runTest {
        val requests = mutableListOf<BrowserHttpRequest>()
        val engine = BrowserHttpEngine { request ->
            requests += request
            when {
                request.url.endsWith("/api/v1/meta") -> response(META_JSON)
                request.url.endsWith("/api/v1/auth/exchange-invite") -> response(EXCHANGE_JSON, 201)
                request.url.endsWith("/api/v1/me") -> response(ME_JSON)
                request.url.endsWith("/api/v1/workspaces") -> response("{\"workspaces\":[$WORKSPACE_JSON]}")
                request.url.endsWith("/markers") && request.method == "GET" -> response(SNAPSHOT_JSON)
                request.url.endsWith("/markers") && request.method == "POST" -> response(MARKER_JSON, 201)
                request.method == "PATCH" -> response(MARKER_JSON.replace("\"version\":1", "\"version\":2"))
                request.method == "DELETE" -> response("", 204)
                else -> error("Unexpected request ${request.method} ${request.url}")
            }
        }
        val transport = BrowserSharedMarkerTransport(engine) { REQUEST_ID }

        assertEquals(1, transport.getMeta("https://marker.example.com").protocolVersion)
        assertEquals(TOKEN_ID, transport.exchangeInvite("https://marker.example.com", "esm_inv_secret", "Browser").tokenId)
        assertEquals(USER_ID, transport.getMe("https://marker.example.com", "esm_dev_secret").user.userId)
        assertEquals(WORKSPACE_ID, transport.getWorkspaces("https://marker.example.com", "esm_dev_secret").workspaces.single().workspaceId)
        assertEquals(1, transport.getMarkerSnapshot("https://marker.example.com", "esm_dev_secret", WORKSPACE_ID).markers.size)

        val create = CreateSharedMarkerRequestDto(30_004_759, "Staging", "BLUE", listOf("ops"), null)
        transport.createMarker("https://marker.example.com", "esm_dev_secret", WORKSPACE_ID, create, IDEMPOTENCY_ID)
        transport.updateMarker(
            "https://marker.example.com",
            "esm_dev_secret",
            WORKSPACE_ID,
            MARKER_ID,
            UpdateSharedMarkerRequestDto(1, "Staging 2", "ORANGE", emptyList(), null),
            IDEMPOTENCY_ID,
        )
        transport.deleteMarker(
            "https://marker.example.com",
            "esm_dev_secret",
            WORKSPACE_ID,
            MARKER_ID,
            2,
            IDEMPOTENCY_ID,
        )

        val inviteRequest = requests.single { it.url.endsWith("exchange-invite") }
        assertTrue(inviteRequest.body.orEmpty().contains("esm_inv_secret"))
        assertFalse(inviteRequest.url.contains("esm_inv_secret"))
        val authenticated = requests.filter { it.headers.containsKey("Authorization") }
        assertTrue(authenticated.isNotEmpty())
        assertTrue(authenticated.all { it.headers["Authorization"] == "Bearer esm_dev_secret" })
        assertTrue(requests.filter { it.method in setOf("POST", "PATCH", "DELETE") && !it.url.endsWith("exchange-invite") }
            .all { it.headers["Idempotency-Key"] == IDEMPOTENCY_ID })
        assertTrue(requests.single { it.method == "DELETE" }.url.endsWith("?expectedVersion=2"))
    }

    @Test
    fun `transport maps conflict body and malformed responses without exposing credentials`() = runTest {
        val conflictEngine = BrowserHttpEngine {
            response(
                """{"code":"MARKER_VERSION_CONFLICT","message":"Stale marker","requestId":"$REQUEST_ID","details":{"currentMarker":$MARKER_JSON}}""",
                409,
            )
        }
        val conflict = assertFailsWith<SharedMarkerTransportException> {
            BrowserSharedMarkerTransport(conflictEngine) { REQUEST_ID }.updateMarker(
                "https://marker.example.com",
                "esm_dev_secret",
                WORKSPACE_ID,
                MARKER_ID,
                UpdateSharedMarkerRequestDto(1, "Edit", "BLUE", emptyList(), null),
                IDEMPOTENCY_ID,
            )
        }
        assertEquals(SharedMarkerTransportErrorKind.CONFLICT, conflict.error.kind)
        assertEquals(MARKER_ID, conflict.error.currentMarker?.markerId)
        assertFalse(conflict.toString().contains("esm_dev_secret"))

        val malformed = assertFailsWith<SharedMarkerTransportException> {
            BrowserSharedMarkerTransport(BrowserHttpEngine { response("not-json") }) { REQUEST_ID }
                .getMeta("https://marker.example.com")
        }
        assertEquals(SharedMarkerTransportErrorKind.INVALID_RESPONSE, malformed.error.kind)
    }

    @Test
    fun `server origin parser enforces HTTPS remotely and origin-only URLs`() {
        assertEquals("https://example.com", normalizeSharedServerOrigin(" HTTPS://EXAMPLE.COM:443/ "))
        assertEquals("http://localhost:8080", normalizeSharedServerOrigin("http://LOCALHOST:8080/"))
        listOf(
            "http://example.com",
            "https://example.com/path",
            "https://user@example.com",
            "https://example.com?secret=1",
            "javascript:alert(1)",
        ).forEach { value -> assertFailsWith<IllegalArgumentException>(value) { normalizeSharedServerOrigin(value) } }
    }

    private fun response(body: String, status: Int = 200) = BrowserHttpResponse(
        status,
        mapOf("X-Request-Id" to REQUEST_ID),
        body,
    )
}

internal const val WORKSPACE_ID = "01991d60-b8a2-7a20-a311-b5114b27c219"
internal const val MEMBER_ID = "01991d62-1fcb-70d0-858b-1d65f6ce3cf6"
internal const val USER_ID = "01991d61-745e-7b08-a716-93c039cde2e2"
internal const val TOKEN_ID = "01991d6a-74ce-7ef5-8735-4e15444fc980"
internal const val MARKER_ID = "01991d67-5672-7514-9369-482ed563c63d"
internal const val REQUEST_ID = "01991d75-b87a-722f-ad20-24ac849c3a21"
internal const val IDEMPOTENCY_ID = "a3c1be66-724c-46f0-bb8a-678817343a58"
internal const val WORKSPACE_JSON =
    "{\"workspaceId\":\"$WORKSPACE_ID\",\"name\":\"Ops\",\"role\":\"EDITOR\",\"revision\":7,\"memberId\":\"$MEMBER_ID\"}"
internal const val META_JSON =
    "{\"serverVersion\":\"0.1.0\",\"protocolVersion\":1,\"minimumClientProtocolVersion\":1,\"maximumClientProtocolVersion\":1,\"features\":[\"shared-markers\"],\"universeBuild\":\"sde-1\"}"
internal const val EXCHANGE_JSON =
    "{\"accessToken\":\"esm_dev_secret\",\"tokenId\":\"$TOKEN_ID\",\"expiresAt\":\"2026-12-01T00:00:00Z\",\"user\":{\"userId\":\"$USER_ID\",\"displayName\":\"Pilot\"},\"workspace\":$WORKSPACE_JSON}"
internal const val ME_JSON =
    "{\"user\":{\"userId\":\"$USER_ID\",\"displayName\":\"Pilot\"},\"workspace\":$WORKSPACE_JSON,\"device\":{\"tokenId\":\"$TOKEN_ID\",\"deviceName\":\"Browser\",\"createdAt\":\"2026-09-01T00:00:00Z\",\"lastUsedAt\":null,\"expiresAt\":\"2026-12-01T00:00:00Z\"}}"
internal const val MARKER_JSON =
    "{\"markerId\":\"$MARKER_ID\",\"workspaceId\":\"$WORKSPACE_ID\",\"systemId\":30004759,\"name\":\"Staging\",\"color\":\"BLUE\",\"tags\":[\"ops\"],\"notes\":\"private\",\"createdBy\":{\"userId\":\"$USER_ID\",\"displayName\":\"Pilot\"},\"updatedBy\":{\"userId\":\"$USER_ID\",\"displayName\":\"Pilot\"},\"createdAt\":\"2026-09-01T00:00:00Z\",\"updatedAt\":\"2026-09-01T00:00:00Z\",\"version\":1}"
internal const val SNAPSHOT_JSON =
    "{\"workspaceId\":\"$WORKSPACE_ID\",\"revision\":7,\"generatedAt\":\"2026-09-01T00:00:00Z\",\"markers\":[$MARKER_JSON]}"
