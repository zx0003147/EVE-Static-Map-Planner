package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.shared.protocol.CreateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.DeviceDto
import dev.evestaticmapplanner.shared.protocol.ExchangeInviteResponseDto
import dev.evestaticmapplanner.shared.protocol.MeResponseDto
import dev.evestaticmapplanner.shared.protocol.MetaResponseDto
import dev.evestaticmapplanner.shared.protocol.ROUTE_HANDOFFS_FEATURE
import dev.evestaticmapplanner.shared.protocol.RouteHandoffDto
import dev.evestaticmapplanner.shared.protocol.RouteHandoffListResponseDto
import dev.evestaticmapplanner.shared.protocol.RouteHandoffMapMetadataDto
import dev.evestaticmapplanner.shared.protocol.RouteHandoffPublisherDto
import dev.evestaticmapplanner.shared.protocol.RouteHandoffResolvedEdgeDto
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
    fun `Viewer reads Desktop routes when feature is advertised and old server remains compatible`() = runTest {
        val featured = FakeSharedMarkerClient(role = "VIEWER", routeFeature = true)
        val featuredController = controller(featured)
        featuredController.connect("https://marker.example.com", "esm_inv_once")
        assertTrue(featuredController.state.supportsRouteHandoffs)
        assertEquals(SHARED_ROUTE_HANDOFF_ID, featuredController.state.routeHandoffs.single().routeHandoffId)
        assertEquals(1, featured.routeReadCalls)

        val legacy = FakeSharedMarkerClient(role = "VIEWER", routeFeature = false)
        val legacyController = controller(legacy)
        legacyController.connect("https://marker.example.com", "esm_inv_once")
        assertFalse(legacyController.state.supportsRouteHandoffs)
        assertTrue(legacyController.state.routeHandoffs.isEmpty())
        assertEquals(0, legacy.routeReadCalls)
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
        val store = MemoryWebDeviceSessionStore()
        val controller = controller(client, store)
        controller.connect("https://marker.example.com", "esm_inv_once")
        assertTrue(store.value != null)
        client.failure = failure(SharedMarkerTransportErrorKind.AUTHENTICATION, "expired")

        controller.refreshNow()
        assertEquals(WebSharedMarkerStatus.AUTH_FAILED, controller.state.status)
        assertTrue(controller.state.error.orEmpty().contains("new Invite Code"))
        assertNull(store.value)
        controller.refreshNow()
        assertEquals(1, client.exchangeCalls)
    }

    @Test
    fun `remembered Device Token restores a validated session without another Invite`() = runTest {
        val client = FakeSharedMarkerClient(routeFeature = true)
        val store = MemoryWebDeviceSessionStore()
        val first = controller(client, store)
        first.connect("https://marker.example.com", "esm_inv_once", "Browser PWA", rememberDevice = true)

        assertEquals("esm_dev_memory", store.value?.accessToken)
        assertEquals(WORKSPACE_ID, store.value?.workspaceId)
        assertFalse(store.value.toString().contains("esm_dev_memory"))
        first.close()

        val reopened = controller(client, store)
        assertTrue(reopened.restoreRememberedDevice())
        assertEquals(WebSharedMarkerStatus.CONNECTED, reopened.state.status)
        assertEquals(WORKSPACE_ID, reopened.state.workspace?.workspaceId)
        assertEquals("Browser PWA", reopened.state.deviceName)
        assertEquals(1, client.exchangeCalls)

        reopened.disconnect()
        assertNull(store.value)
        assertEquals(WebSharedMarkerStatus.DISCONNECTED, reopened.state.status)
    }

    @Test
    fun `ephemeral connect is not restored and invalid remembered token is cleared`() = runTest {
        val ephemeralStore = MemoryWebDeviceSessionStore()
        val ephemeral = controller(FakeSharedMarkerClient(), ephemeralStore)
        ephemeral.connect("https://marker.example.com", "esm_inv_once", rememberDevice = false)
        assertNull(ephemeralStore.value)
        assertFalse(controller(FakeSharedMarkerClient(), ephemeralStore).restoreRememberedDevice())

        val rememberedStore = MemoryWebDeviceSessionStore(
            RememberedWebDeviceSession("https://marker.example.com", "esm_dev_revoked", "Browser", WORKSPACE_ID),
        )
        val revokedClient = FakeSharedMarkerClient().apply {
            failure = failure(SharedMarkerTransportErrorKind.AUTHENTICATION, "revoked")
        }
        val restored = controller(revokedClient, rememberedStore)
        assertFalse(restored.restoreRememberedDevice())
        assertNull(rememberedStore.value)
        assertEquals(WebSharedMarkerStatus.AUTH_FAILED, restored.state.status)

        val expiredStore = MemoryWebDeviceSessionStore(
            RememberedWebDeviceSession("https://marker.example.com", "esm_dev_expired", "PWA", WORKSPACE_ID),
        )
        val expiredClient = FakeSharedMarkerClient().apply {
            failure = failure(SharedMarkerTransportErrorKind.AUTHENTICATION, "expired")
        }
        val expired = controller(expiredClient, expiredStore)
        assertFalse(expired.restoreRememberedDevice())
        assertNull(expiredStore.value)
        assertEquals(WebSharedMarkerStatus.AUTH_FAILED, expired.state.status)

        val corruptStore = MemoryWebDeviceSessionStore(loadFailure = IllegalStateException("corrupt"))
        val corrupt = controller(FakeSharedMarkerClient(), corruptStore)
        assertFalse(corrupt.restoreRememberedDevice())
        assertEquals(1, corruptStore.clearCalls)
        assertEquals(WebSharedMarkerStatus.AUTH_FAILED, corrupt.state.status)
    }

    @Test
    fun `publisher deletes Desktop Route immediately and refresh cannot revive it`() = runTest {
        val client = FakeSharedMarkerClient(routeFeature = true)
        val controller = controller(client)
        controller.connect("https://marker.example.com", "esm_inv_once")

        assertTrue(controller.deleteRouteHandoff(SHARED_ROUTE_HANDOFF_ID))
        assertTrue(controller.state.routeHandoffs.isEmpty())
        assertEquals(1, client.routeDeleteCalls)
        controller.refreshNow()
        assertTrue(controller.state.routeHandoffs.isEmpty())

        client.routeHandoffs += routeHandoff()
        controller.refreshNow()
        client.routeDeleteFailure = failure(SharedMarkerTransportErrorKind.SERVER, "delete unavailable")
        assertFalse(controller.deleteRouteHandoff(SHARED_ROUTE_HANDOFF_ID))
        assertTrue(controller.state.error.orEmpty().contains("delete unavailable"))
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

    private fun kotlinx.coroutines.test.TestScope.controller(
        client: FakeSharedMarkerClient,
        store: WebDeviceSessionStore = EphemeralWebDeviceSessionStore,
    ) = WebSharedMarkerController(
        client,
        backgroundScope,
        onStateChanged = {},
        idempotencyKeyFactory = { IDEMPOTENCY_ID },
        pollingEnabled = false,
        sessionStore = store,
    )
}

private class FakeSharedMarkerClient(
    role: String = "EDITOR",
    private val routeFeature: Boolean = false,
) : SharedMarkerClient {
    private val user = UserDto(USER_ID, "Pilot")
    private val workspace = WorkspaceDto(WORKSPACE_ID, "Ops", role, 7, MEMBER_ID)
    private var nextVersion = 1L
    private val values = linkedMapOf(MARKER_ID to marker(MARKER_ID, 30_004_759, "Staging", nextVersion))
    var failure: SharedMarkerTransportException? = null
    var exchangeCalls = 0
    var createCalls = 0
    var routeReadCalls = 0
    var routeDeleteCalls = 0
    var routeDeleteFailure: SharedMarkerTransportException? = null
    val routeHandoffs = mutableListOf(routeHandoff())

    override suspend fun getMeta(serverOrigin: String): MetaResponseDto {
        failure?.let { throw it }
        val features = listOf("shared-markers") + if (routeFeature) listOf(ROUTE_HANDOFFS_FEATURE) else emptyList()
        return MetaResponseDto("0.1.0", 1, 1, 1, features, "sde-1")
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

    override suspend fun getRouteHandoffs(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
    ): RouteHandoffListResponseDto {
        routeReadCalls++
        return RouteHandoffListResponseDto("2026-09-08T01:00:00Z", routeHandoffs.toList())
    }

    override suspend fun deleteRouteHandoff(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        handoffId: String,
        idempotencyKey: String,
    ) {
        routeDeleteCalls++
        routeDeleteFailure?.let { throw it }
        routeHandoffs.removeAll { it.routeHandoffId == handoffId }
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

private class MemoryWebDeviceSessionStore(
    var value: RememberedWebDeviceSession? = null,
    private val loadFailure: Throwable? = null,
) : WebDeviceSessionStore {
    var clearCalls: Int = 0
        private set

    override suspend fun load(): RememberedWebDeviceSession? {
        loadFailure?.let { throw it }
        return value?.copy()
    }
    override suspend fun save(session: RememberedWebDeviceSession) {
        value = session.copy()
    }
    override suspend fun clear() {
        clearCalls++
        value = null
    }
}

private fun routeHandoff() = RouteHandoffDto(
    routeHandoffId = SHARED_ROUTE_HANDOFF_ID,
    workspaceId = WORKSPACE_ID,
    publisher = RouteHandoffPublisherDto(MEMBER_ID, USER_ID, "Pilot", TOKEN_ID, "Desktop"),
    createdAt = "2026-09-08T01:00:00Z",
    expiresAt = "2026-09-15T01:00:00Z",
    type = "NORMAL",
    originSystemId = 30_000_001,
    waypointSystemIds = emptyList(),
    destinationSystemId = 30_000_002,
    useAnsiblex = false,
    resolvedSystemIds = listOf(30_000_001, 30_000_002),
    resolvedEdges = listOf(RouteHandoffResolvedEdgeDto(30_000_001, 30_000_002, "STARGATE")),
    mapMetadata = RouteHandoffMapMetadataDto("sde-1", "1.8.0"),
)

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

private const val SHARED_ROUTE_HANDOFF_ID = "01991d67-5672-7514-9369-482ed563c647"
