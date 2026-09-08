package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.shared.protocol.CreateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.DeviceDto
import dev.evestaticmapplanner.shared.protocol.ExchangeInviteResponseDto
import dev.evestaticmapplanner.shared.protocol.MeResponseDto
import dev.evestaticmapplanner.shared.protocol.MetaResponseDto
import dev.evestaticmapplanner.shared.protocol.SharedMarkerDto
import dev.evestaticmapplanner.shared.protocol.SharedMarkerSnapshotResponseDto
import dev.evestaticmapplanner.shared.protocol.UpdateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.UserDto
import dev.evestaticmapplanner.shared.protocol.WorkspaceDto
import dev.evestaticmapplanner.shared.protocol.WorkspacesResponseDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSharedMarkerControllerTest {
    @Test
    fun `connect loads markers and CRUD reconciles shared state immediately`() = runTest {
        val client = FakeSharedMarkerClient()
        val published = mutableListOf<WebSharedMarkerState>()
        val controller = WebSharedMarkerController(
            client,
            backgroundScope,
            published::add,
            idempotencyKeyFactory = { IDEMPOTENCY_ID },
            pollingEnabled = false,
        )

        controller.connect("https://marker.example.com", "esm_inv_once", "Browser")
        assertEquals(WebSharedMarkerStatus.CONNECTED, controller.state.status)
        assertEquals(MARKER_ID, controller.state.markers.values.single().markerId)
        assertTrue(controller.state.canWrite)

        controller.createMarker(30_000_002, WebSharedMarkerDraft("Mid", "GREEN", listOf("mid"), null))
        val created = controller.state.markers.values.single { it.systemId == 30_000_002 }
        assertEquals("Mid", created.name)
        assertEquals(created.markerId, controller.state.selectedMarkerId)

        controller.updateMarker(created.markerId, created.version, WebSharedMarkerDraft("Mid Updated", "ORANGE", emptyList(), "note"))
        assertEquals("Mid Updated", controller.state.markers.getValue(created.markerId).name)

        val updated = controller.state.markers.getValue(created.markerId)
        controller.deleteMarker(updated.markerId, updated.version)
        assertFalse(updated.markerId in controller.state.markers)
        assertNull(controller.state.selectedMarkerId)
        assertTrue(published.any { it.status == WebSharedMarkerStatus.CONNECTING })
    }

    @Test
    fun `viewer remains read only and server stays the permission boundary`() = runTest {
        val client = FakeSharedMarkerClient(role = "VIEWER")
        val controller = controller(client)
        controller.connect("https://marker.example.com", "esm_inv_once")

        assertFalse(controller.state.canWrite)
        controller.createMarker(30_000_002, WebSharedMarkerDraft("Denied", "RED", emptyList(), null))
        assertTrue(controller.state.error.orEmpty().contains("read-only"))
        assertEquals(0, client.createCalls)
    }

    @Test
    fun `network loss retains markers and reconnect restores connected state`() = runTest {
        val client = FakeSharedMarkerClient()
        val controller = controller(client)
        controller.connect("https://marker.example.com", "esm_inv_once")
        val initialMarkers = controller.state.markers

        client.failure = failure(SharedMarkerTransportErrorKind.NETWORK, "offline")
        controller.refreshNow()
        assertEquals(WebSharedMarkerStatus.RECONNECTING, controller.state.status)
        assertEquals(initialMarkers, controller.state.markers)
        assertEquals(1, controller.state.reconnectAttempt)

        client.failure = null
        controller.refreshNow()
        assertEquals(WebSharedMarkerStatus.CONNECTED, controller.state.status)
        assertEquals(0, controller.state.reconnectAttempt)
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L), (1..5).map(WebSharedMarkerReconnectPolicy::delayMillis))
    }

    @Test
    fun `auth expiry clears the in-memory session and requires another invite`() = runTest {
        val client = FakeSharedMarkerClient()
        val controller = controller(client)
        controller.connect("https://marker.example.com", "esm_inv_once")
        client.failure = failure(SharedMarkerTransportErrorKind.AUTHENTICATION, "expired")

        controller.refreshNow()
        assertEquals(WebSharedMarkerStatus.AUTH_FAILED, controller.state.status)
        assertTrue(controller.state.error.orEmpty().contains("new Invite Code"))
        controller.refreshNow()
        assertEquals(1, client.exchangeCalls)
    }

    @Test
    fun `malformed workspace response is visible and does not publish connected`() = runTest {
        val client = FakeSharedMarkerClient(role = "OWNER")
        val controller = controller(client)
        controller.connect("https://marker.example.com", "esm_inv_once")

        assertEquals(WebSharedMarkerStatus.FAILED, controller.state.status)
        assertTrue(controller.state.error.orEmpty().contains("invalid", ignoreCase = true))
    }

    @Test
    fun `version conflict adopts the authoritative marker and disconnect clears shared state`() = runTest {
        val client = FakeSharedMarkerClient()
        val controller = controller(client)
        controller.connect("https://marker.example.com", "esm_inv_once")
        val authoritative = marker(MARKER_ID, 30_004_759, "Other pilot edit", 2)
        client.failure = SharedMarkerTransportException(
            SharedMarkerTransportError(
                kind = SharedMarkerTransportErrorKind.CONFLICT,
                message = "The Shared Marker changed.",
                currentMarker = authoritative,
            ),
        )

        controller.updateMarker(MARKER_ID, 1, WebSharedMarkerDraft("Stale edit", "RED", emptyList(), null))

        assertEquals(authoritative, controller.state.markers.getValue(MARKER_ID))
        assertTrue(controller.state.error.orEmpty().contains("changed"))
        controller.disconnect()
        assertEquals(WebSharedMarkerStatus.DISCONNECTED, controller.state.status)
        assertTrue(controller.state.markers.isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.controller(client: FakeSharedMarkerClient) = WebSharedMarkerController(
        client,
        backgroundScope,
        onStateChanged = {},
        idempotencyKeyFactory = { IDEMPOTENCY_ID },
        pollingEnabled = false,
    )
}

private class FakeSharedMarkerClient(role: String = "EDITOR") : SharedMarkerClient {
    private val user = UserDto(USER_ID, "Pilot")
    private val workspace = WorkspaceDto(WORKSPACE_ID, "Ops", role, 7, MEMBER_ID)
    private var nextVersion = 1L
    private val values = linkedMapOf(MARKER_ID to marker(MARKER_ID, 30_004_759, "Staging", nextVersion))
    var failure: SharedMarkerTransportException? = null
    var exchangeCalls = 0
    var createCalls = 0

    override suspend fun getMeta(serverOrigin: String): MetaResponseDto {
        failure?.let { throw it }
        return MetaResponseDto("0.1.0", 1, 1, 1, listOf("shared-markers"), "sde-1")
    }

    override suspend fun exchangeInvite(
        serverOrigin: String,
        inviteCode: String,
        deviceName: String,
    ): ExchangeInviteResponseDto {
        exchangeCalls++
        failure?.let { throw it }
        return ExchangeInviteResponseDto("esm_dev_memory", TOKEN_ID, "2026-12-01T00:00:00Z", user, workspace)
    }

    override suspend fun getMe(serverOrigin: String, accessToken: String): MeResponseDto {
        failure?.let { throw it }
        return MeResponseDto(
            user,
            workspace,
            DeviceDto(TOKEN_ID, "Browser", "2026-09-01T00:00:00Z", null, "2026-12-01T00:00:00Z"),
        )
    }

    override suspend fun getWorkspaces(serverOrigin: String, accessToken: String) = WorkspacesResponseDto(listOf(workspace))

    override suspend fun getMarkerSnapshot(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
    ): SharedMarkerSnapshotResponseDto {
        failure?.let { throw it }
        return SharedMarkerSnapshotResponseDto(WORKSPACE_ID, 7, "2026-09-01T00:00:00Z", values.values.toList())
    }

    override suspend fun createMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        request: CreateSharedMarkerRequestDto,
        idempotencyKey: String,
    ): SharedMarkerDto {
        createCalls++
        failure?.let { throw it }
        nextVersion = 1
        return marker(CREATED_MARKER_ID, request.systemId, request.name, nextVersion, request.color, request.tags, request.notes)
            .also { values[it.markerId] = it }
    }

    override suspend fun updateMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        markerId: String,
        request: UpdateSharedMarkerRequestDto,
        idempotencyKey: String,
    ): SharedMarkerDto {
        failure?.let { throw it }
        val current = values.getValue(markerId)
        nextVersion = current.version + 1
        return marker(markerId, current.systemId, request.name, nextVersion, request.color, request.tags, request.notes)
            .also { values[markerId] = it }
    }

    override suspend fun deleteMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        markerId: String,
        expectedVersion: Long,
        idempotencyKey: String,
    ) {
        failure?.let { throw it }
        values.remove(markerId)
    }

    companion object {
        private const val CREATED_MARKER_ID = "01991d67-5672-7514-9369-482ed563c640"
        internal fun marker(
            markerId: String,
            systemId: Int,
            name: String,
            version: Long,
            color: String = "BLUE",
            tags: List<String> = listOf("ops"),
            notes: String? = null,
        ) = SharedMarkerDto(
            markerId,
            WORKSPACE_ID,
            systemId,
            name,
            color,
            tags,
            notes,
            UserDto(USER_ID, "Pilot"),
            UserDto(USER_ID, "Pilot"),
            "2026-09-01T00:00:00Z",
            "2026-09-01T00:00:00Z",
            version,
        )
    }
}

private fun marker(markerId: String, systemId: Int, name: String, version: Long): SharedMarkerDto = SharedMarkerDto(
    markerId,
    WORKSPACE_ID,
    systemId,
    name,
    "BLUE",
    listOf("ops"),
    null,
    UserDto(USER_ID, "Pilot"),
    UserDto(USER_ID, "Pilot"),
    "2026-09-01T00:00:00Z",
    "2026-09-01T00:00:00Z",
    version,
)

private fun failure(kind: SharedMarkerTransportErrorKind, message: String) =
    SharedMarkerTransportException(SharedMarkerTransportError(kind, message))
