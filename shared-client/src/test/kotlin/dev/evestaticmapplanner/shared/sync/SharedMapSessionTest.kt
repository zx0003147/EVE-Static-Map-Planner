package dev.evestaticmapplanner.shared.sync

import dev.evestaticmapplanner.shared.api.SharedMapClient
import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.api.SharedMapException
import dev.evestaticmapplanner.shared.api.SharedServerUrl
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.auth.SecureCredentialStore
import dev.evestaticmapplanner.shared.auth.SharedCredentialKey
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedDevice
import dev.evestaticmapplanner.shared.model.SharedIdentity
import dev.evestaticmapplanner.shared.model.SharedMapConfiguration
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedServerMeta
import dev.evestaticmapplanner.shared.model.SharedUser
import dev.evestaticmapplanner.shared.model.SharedWorkspace
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.shared.protocol.ExchangedCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMapSessionTest {
    @Test
    fun `connect publishes online state and stores credential before authenticated reads`() = runTest {
        val events = mutableListOf<String>()
        val client = FakeClient(events = events)
        val store = MemoryCredentialStore(events)
        val saved = mutableListOf<SharedMapConfiguration>()
        val session = session(client, store) { saved += it; events += "configuration" }

        SecretValue.from("esm_inv_secret").use { session.connect(SERVER, it, "Laptop") }

        assertEquals(SharedConnectionState.ONLINE, session.state.value.connectionState)
        assertEquals(1, session.state.value.markerCount)
        assertEquals(7, session.state.value.snapshot?.revision)
        assertEquals(FIXED_NOW, session.state.value.lastSuccessfulSyncAt)
        assertTrue(events.indexOf("credential-save") < events.indexOf("me"))
        assertTrue(events.indexOf("configuration") < events.indexOf("me"))
        assertEquals(WORKSPACE_A, saved.last().selectedWorkspaceId)
        session.close()
    }

    @Test
    fun `startup without configuration performs no requests`() = runTest {
        val client = FakeClient()
        val session = session(client, MemoryCredentialStore()) {}
        session.restore(SharedMapConfiguration())
        assertEquals(SharedConnectionState.DISCONNECTED, session.state.value.connectionState)
        assertEquals(0, client.calls)
        session.close()
    }

    @Test
    fun `offline startup recovers on the next poll without rebuilding session`() = runTest {
        val client = FakeClient().apply { failure = SharedMapError.Network() }
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = session(client, store, dispatcher = dispatcher) {}

        session.restore(CONFIG_A)
        assertEquals(SharedConnectionState.OFFLINE, session.state.value.connectionState)
        assertNull(session.state.value.snapshot)

        client.failure = null
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(SharedConnectionState.ONLINE, session.state.value.connectionState)
        assertEquals(1, session.state.value.markerCount)
        session.close()
    }

    @Test
    fun `poll replaces snapshot atomically and survives transient failure`() = runTest {
        val client = FakeClient().apply { snapshot = snapshot(setOf(MARKER_A, MARKER_B, MARKER_C)) }
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = session(client, store, dispatcher = dispatcher) {}
        session.restore(CONFIG_A)
        assertEquals(setOf(MARKER_A, MARKER_B, MARKER_C), session.state.value.snapshot?.markers?.keys)

        client.snapshot = snapshot(setOf(MARKER_A, MARKER_C, MARKER_D), revision = 8)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(setOf(MARKER_A, MARKER_C, MARKER_D), session.state.value.snapshot?.markers?.keys)

        client.failure = SharedMapError.Server("maintenance")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(SharedConnectionState.DEGRADED, session.state.value.connectionState)
        assertTrue(session.state.value.stale)
        assertEquals(setOf(MARKER_A, MARKER_C, MARKER_D), session.state.value.snapshot?.markers?.keys)

        client.failure = null
        client.snapshot = snapshot(setOf(MARKER_A, MARKER_D), revision = 9)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(SharedConnectionState.ONLINE, session.state.value.connectionState)
        assertEquals(setOf(MARKER_A, MARKER_D), session.state.value.snapshot?.markers?.keys)
        session.close()
    }

    @Test
    fun `401 retains stale current-session snapshot and stops polling`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = session(client, store, dispatcher = dispatcher) {}
        session.restore(CONFIG_A)
        val callsBeforeFailure = client.calls

        client.failure = SharedMapError.Authentication("revoked")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(SharedConnectionState.AUTH_REQUIRED, session.state.value.connectionState)
        assertTrue(session.state.value.stale)
        assertEquals(setOf(MARKER_A), session.state.value.snapshot?.markers?.keys)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(callsBeforeFailure + 1, client.calls)
        session.close()
    }

    @Test
    fun `403 clears snapshot and stops polling`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = session(client, store, dispatcher = dispatcher) {}
        session.restore(CONFIG_A)

        client.failure = SharedMapError.Forbidden("membership revoked")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(SharedConnectionState.FORBIDDEN, session.state.value.connectionState)
        assertNull(session.state.value.snapshot)
        session.close()
    }

    @Test
    fun `protocol mismatch never calls authenticated endpoints`() = runTest {
        val client = FakeClient().apply {
            meta = compatibleMeta.copy(minimumClientProtocolVersion = 2, maximumClientProtocolVersion = 2)
        }
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val session = session(client, store) {}
        session.restore(CONFIG_A)
        assertEquals(SharedConnectionState.PROTOCOL_UNSUPPORTED, session.state.value.connectionState)
        assertEquals(1, client.metaCalls)
        assertEquals(0, client.meCalls)
        assertEquals(0, client.snapshotCalls)
        session.close()
    }

    @Test
    fun `missing Shared Marker feature never calls authenticated endpoints`() = runTest {
        val client = FakeClient().apply { meta = compatibleMeta.copy(features = setOf("members")) }
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val session = session(client, store) {}
        session.restore(CONFIG_A)
        assertEquals(SharedConnectionState.PROTOCOL_UNSUPPORTED, session.state.value.connectionState)
        assertEquals(0, client.meCalls)
        assertEquals(0, client.snapshotCalls)
        session.close()
    }

    @Test
    fun `missing secure credential enters auth required without network`() = runTest {
        val client = FakeClient()
        val session = session(client, MemoryCredentialStore()) {}
        session.restore(CONFIG_A)
        assertEquals(SharedConnectionState.AUTH_REQUIRED, session.state.value.connectionState)
        assertEquals(0, client.calls)
        session.close()
    }

    @Test
    fun `workspace switch clears old snapshot and uses isolated credential`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply {
            put(KEY_A, "esm_dev_a")
            put(KEY_B, "esm_dev_b")
        }
        val saved = mutableListOf<SharedMapConfiguration>()
        val session = session(client, store) { saved += it }
        session.restore(CONFIG_A)

        client.workspace = workspace(WORKSPACE_B)
        client.snapshot = snapshot(setOf(MARKER_D), workspaceId = WORKSPACE_B, revision = 3)
        session.switchWorkspace(WORKSPACE_B)

        assertEquals(SharedConnectionState.ONLINE, session.state.value.connectionState)
        assertEquals(WORKSPACE_B, session.state.value.selectedWorkspaceId)
        assertEquals(setOf(MARKER_D), session.state.value.snapshot?.markers?.keys)
        assertEquals(WORKSPACE_B, saved.last().selectedWorkspaceId)
        session.close()
    }

    @Test
    fun `disconnect deletes credential clears authority and stops poll`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val saved = mutableListOf<SharedMapConfiguration>()
        val session = session(client, store, dispatcher = dispatcher) { saved += it }
        session.restore(CONFIG_A)
        val calls = client.calls

        session.disconnect()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(SharedConnectionState.DISCONNECTED, session.state.value.connectionState)
        assertNull(session.state.value.snapshot)
        assertNull(session.state.value.identity)
        assertTrue(!store.contains(KEY_A))
        assertNull(saved.last().selectedWorkspaceId)
        assertEquals(calls, client.calls)
        session.close()
    }

    @Test
    fun `failed credential deletion keeps session connected and polling`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply {
            put(KEY_A, "esm_dev_saved")
            deleteFailure = true
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = session(client, store, dispatcher = dispatcher) {}
        session.restore(CONFIG_A)
        val before = client.snapshotCalls

        assertFailsWith<IllegalStateException> { session.disconnect() }
        assertEquals(SharedConnectionState.ONLINE, session.state.value.connectionState)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(before + 1, client.snapshotCalls)
        session.close()
    }

    @Test
    fun `manual refresh is single-flight`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val session = session(client, store) {}
        session.restore(CONFIG_A)
        val before = client.snapshotCalls
        client.snapshotGate = CompletableDeferred()
        val first = launch { session.refreshNow() }
        runCurrent()
        val coalesced = launch { session.refreshNow() }
        runCurrent()
        assertEquals(before + 1, client.snapshotCalls)
        assertEquals(1, client.maximumConcurrentSnapshots)
        client.snapshotGate?.complete(Unit)
        first.join()
        coalesced.join()
        session.close()
    }

    @Test
    fun `role downgrade is refreshed by polling without invalidating token`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = session(client, store, dispatcher = dispatcher) {}
        session.restore(CONFIG_A)

        client.workspace = client.workspace.copy(role = SharedWorkspaceRole.VIEWER)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(SharedConnectionState.ONLINE, session.state.value.connectionState)
        assertEquals(SharedWorkspaceRole.VIEWER, session.state.value.identity?.workspace?.role)
        session.close()
    }

    @Test
    fun `restoring twice replaces rather than duplicates polling loop`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = session(client, store, dispatcher = dispatcher) {}
        session.restore(CONFIG_A)
        session.restore(CONFIG_A)
        val before = client.snapshotCalls

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(before + 1, client.snapshotCalls)
        session.close()
    }

    @Test
    fun `close stops polling closes HTTP client and clears snapshot`() = runTest {
        val client = FakeClient()
        val store = MemoryCredentialStore().apply { put(KEY_A, "esm_dev_saved") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = session(client, store, dispatcher = dispatcher) {}
        session.restore(CONFIG_A)
        val calls = client.calls

        session.close()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(calls, client.calls)
        assertTrue(client.closed)
        assertNull(session.state.value.snapshot)
        assertEquals(SharedConnectionState.DISCONNECTED, session.state.value.connectionState)
    }

    private fun TestScope.session(
        client: FakeClient,
        store: MemoryCredentialStore,
        dispatcher: TestDispatcher = StandardTestDispatcher(testScheduler),
        configurationSave: (SharedMapConfiguration) -> Unit,
    ): SharedMapSession = SharedMapSession(
        client = client,
        credentialStore = store,
        configurationSink = object : SharedMapConfigurationSink {
            override suspend fun save(configuration: SharedMapConfiguration) = configurationSave(configuration)
        },
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        pollingIntervalMillis = 30_000,
    )
}

private class FakeClient(private val events: MutableList<String> = mutableListOf()) : SharedMapClient {
    var meta: SharedServerMeta = compatibleMeta
    var workspace: SharedWorkspace = workspace(WORKSPACE_A)
    var snapshot: SharedMarkerSnapshot = snapshot(setOf(MARKER_A))
    var failure: SharedMapError? = null
    var calls = 0
    var metaCalls = 0
    var meCalls = 0
    var snapshotCalls = 0
    var snapshotGate: CompletableDeferred<Unit>? = null
    var maximumConcurrentSnapshots = 0
    var closed = false
    private var concurrentSnapshots = 0

    override suspend fun getMeta(server: SharedServerUrl): SharedServerMeta {
        call("meta")
        metaCalls++
        return meta
    }

    override suspend fun exchangeInvite(
        server: SharedServerUrl,
        invite: SecretValue,
        deviceName: String,
    ): ExchangedCredential {
        call("exchange")
        return ExchangedCredential(
            accessToken = SecretValue.from("esm_dev_issued"),
            tokenId = TOKEN_ID,
            expiresAt = Instant.parse("2026-12-01T00:00:00Z"),
            user = USER,
            workspace = workspace,
        )
    }

    override suspend fun getMe(server: SharedServerUrl, token: SecretValue): SharedIdentity {
        call("me")
        meCalls++
        return identity(workspace)
    }

    override suspend fun getWorkspaces(server: SharedServerUrl, token: SecretValue): List<SharedWorkspace> {
        call("workspaces")
        return listOf(workspace)
    }

    override suspend fun getMarkerSnapshot(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
    ): SharedMarkerSnapshot {
        call("snapshot")
        snapshotCalls++
        concurrentSnapshots++
        maximumConcurrentSnapshots = maxOf(maximumConcurrentSnapshots, concurrentSnapshots)
        return try {
            snapshotGate?.await()
            snapshot
        } finally {
            concurrentSnapshots--
        }
    }

    override fun close() {
        closed = true
    }

    private fun call(name: String) {
        calls++
        events += name
        failure?.let { throw SharedMapException(it) }
    }
}

private class MemoryCredentialStore(private val events: MutableList<String> = mutableListOf()) : SecureCredentialStore {
    private val entries = mutableMapOf<SharedCredentialKey, SecretValue>()
    var deleteFailure = false

    override fun load(key: SharedCredentialKey): SecretValue? = entries[key]?.copy()

    override fun save(key: SharedCredentialKey, secret: SecretValue) {
        events += "credential-save"
        entries.put(key, secret.copy())?.close()
    }

    override fun delete(key: SharedCredentialKey) {
        check(!deleteFailure) { "safe test deletion failure" }
        entries.remove(key)?.close()
    }

    fun put(key: SharedCredentialKey, value: String) {
        entries.put(key, SecretValue.from(value))?.close()
    }

    fun contains(key: SharedCredentialKey): Boolean = key in entries
}

private val compatibleMeta = SharedServerMeta("0.1.0", 1, 1, 1, setOf("shared-markers"), "sde-1")
private val USER = SharedUser(USER_ID, "Pilot")
private val FIXED_NOW: Instant = Instant.parse("2026-09-02T00:00:00Z")
private const val SERVER = "https://example.com"
private const val WORKSPACE_A = "01991d60-b8a2-7a20-a311-b5114b27c219"
private const val WORKSPACE_B = "01991d60-b8a2-7a20-a311-b5114b27c220"
private const val MEMBER_ID = "01991d62-1fcb-70d0-858b-1d65f6ce3cf6"
private const val TOKEN_ID = "01991d6a-74ce-7ef5-8735-4e15444fc980"
private const val USER_ID = "01991d61-745e-7b08-a716-93c039cde2e2"
private const val MARKER_A = "01991d67-5672-7514-9369-482ed563c63d"
private const val MARKER_B = "01991d67-5672-7514-9369-482ed563c640"
private const val MARKER_C = "01991d67-5672-7514-9369-482ed563c63e"
private const val MARKER_D = "01991d67-5672-7514-9369-482ed563c63f"
private val KEY_A = SharedCredentialKey(SERVER, WORKSPACE_A)
private val KEY_B = SharedCredentialKey(SERVER, WORKSPACE_B)
private val CONFIG_A = SharedMapConfiguration(SERVER, WORKSPACE_A, "Laptop")

private fun workspace(id: String) = SharedWorkspace(id, "Ops $id", SharedWorkspaceRole.EDITOR, 7, MEMBER_ID)

private fun identity(workspace: SharedWorkspace) = SharedIdentity(
    user = USER,
    workspace = workspace,
    device = SharedDevice(
        tokenId = TOKEN_ID,
        deviceName = "Laptop",
        createdAt = Instant.parse("2026-09-01T00:00:00Z"),
        lastUsedAt = null,
        expiresAt = Instant.parse("2026-12-01T00:00:00Z"),
    ),
)

private fun snapshot(
    ids: Set<String>,
    workspaceId: String = WORKSPACE_A,
    revision: Long = 7,
) = SharedMarkerSnapshot(
    workspaceId = workspaceId,
    revision = revision,
    generatedAt = Instant.parse("2026-09-01T00:00:00Z"),
    markers = ids.associateWith { id ->
        SharedMarker(
            markerId = id,
            workspaceId = workspaceId,
            systemId = 30_004_759 + ids.indexOf(id),
            name = "Marker $id",
            color = SharedMarkerColor.BLUE,
            tags = emptyList(),
            notes = "private",
            createdBy = USER,
            updatedBy = USER,
            createdAt = Instant.parse("2026-09-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-09-01T00:00:00Z"),
            version = 1,
        )
    },
)
