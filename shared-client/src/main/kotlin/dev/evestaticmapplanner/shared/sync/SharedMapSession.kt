package dev.evestaticmapplanner.shared.sync

import dev.evestaticmapplanner.shared.api.SharedMapClient
import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.api.SharedMapException
import dev.evestaticmapplanner.shared.api.SharedServerUrl
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.auth.SecureCredentialStore
import dev.evestaticmapplanner.shared.auth.SharedCredentialKey
import dev.evestaticmapplanner.shared.model.DEFAULT_SHARED_MAP_DEVICE_NAME
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedIdentity
import dev.evestaticmapplanner.shared.model.SharedMapConfiguration
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedServerMeta
import dev.evestaticmapplanner.shared.model.SharedWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

interface SharedMapConfigurationSink {
    suspend fun save(configuration: SharedMapConfiguration)
}

class SharedMapSession(
    private val client: SharedMapClient,
    private val credentialStore: SecureCredentialStore,
    private val configurationSink: SharedMapConfigurationSink,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val pollingIntervalMillis: Long = DEFAULT_POLLING_INTERVAL_MILLIS,
    private val delayFunction: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    private val lifecycleMutex = Mutex()
    private val refreshMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val _state = MutableStateFlow(SharedMapState())

    private var configuration = SharedMapConfiguration()
    private var server: SharedServerUrl? = null
    private var credentialKey: SharedCredentialKey? = null
    private var accessToken: SecretValue? = null
    private var pollingJob: Job? = null

    val state: StateFlow<SharedMapState> = _state.asStateFlow()

    init {
        require(pollingIntervalMillis > 0) { "Polling interval must be positive" }
    }

    suspend fun restore(savedConfiguration: SharedMapConfiguration) = lifecycleMutex.withLock {
        ensureOpen()
        stopPolling()
        clearActiveCredential()
        credentialKey = null
        configuration = savedConfiguration.normalizedDeviceName()
        val rawServer = configuration.serverUrl
        if (rawServer.isNullOrBlank()) {
            server = null
            credentialKey = null
            _state.value = SharedMapState(connectionState = SharedConnectionState.DISCONNECTED)
            return@withLock
        }
        val parsedServer = parseServerOrPublish(rawServer) ?: return@withLock
        server = parsedServer
        val workspaceId = canonicalWorkspaceIdOrNull(configuration.selectedWorkspaceId)
        if (workspaceId == null) {
            credentialKey = null
            configuration = configuration.copy(serverUrl = parsedServer.origin, selectedWorkspaceId = null)
            _state.value = SharedMapState(
                connectionState = SharedConnectionState.DISCONNECTED,
                serverUrl = parsedServer.origin,
                statusMessage = "Use an invite to connect to Shared Map.",
            )
            return@withLock
        }
        val key = SharedCredentialKey(parsedServer.origin, workspaceId)
        credentialKey = key
        val token = try {
            credentialStore.load(key)
        } catch (_: Exception) {
            publishCredentialFailure(parsedServer.origin, workspaceId)
            return@withLock
        }
        if (token == null) {
            publishCredentialFailure(parsedServer.origin, workspaceId)
            return@withLock
        }
        accessToken = token
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.CONNECTING,
            serverUrl = parsedServer.origin,
            selectedWorkspaceId = workspaceId,
            statusMessage = "Connecting to Shared Map…",
        )
        val continuePolling = refreshAuthenticated(initial = true)
        if (continuePolling) startPolling()
    }

    suspend fun connect(
        rawServerUrl: String,
        invite: SecretValue,
        deviceName: String = DEFAULT_SHARED_MAP_DEVICE_NAME,
    ) = lifecycleMutex.withLock {
        ensureOpen()
        stopPolling()
        val previousCredentialKey = credentialKey
        clearActiveCredential()
        credentialKey = null
        val parsedServer = parseServerOrPublish(rawServerUrl) ?: return@withLock
        val normalizedDeviceName = deviceName.trim().ifEmpty { DEFAULT_SHARED_MAP_DEVICE_NAME }
        require(normalizedDeviceName.codePointCount(0, normalizedDeviceName.length) <= MAX_DEVICE_NAME_CODE_POINTS) {
            "Device name is too long"
        }
        server = parsedServer
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.CONNECTING,
            serverUrl = parsedServer.origin,
            statusMessage = "Connecting to Shared Map…",
        )

        val meta = try {
            client.getMeta(parsedServer)
        } catch (error: Exception) {
            publishFailure(error, initial = true)
            return@withLock
        }
        if (!publishProtocolResult(meta, initial = true)) return@withLock

        val exchanged = try {
            client.exchangeInvite(parsedServer, invite, normalizedDeviceName)
        } catch (error: Exception) {
            publishFailure(error, initial = true)
            return@withLock
        }
        val key = SharedCredentialKey(parsedServer.origin, exchanged.workspace.workspaceId)
        try {
            credentialStore.save(key, exchanged.accessToken)
            if (previousCredentialKey != null && previousCredentialKey != key) {
                credentialStore.delete(previousCredentialKey)
            }
            val newConfiguration = SharedMapConfiguration(
                serverUrl = parsedServer.origin,
                selectedWorkspaceId = exchanged.workspace.workspaceId,
                deviceName = normalizedDeviceName,
            )
            configurationSink.save(newConfiguration)
            configuration = newConfiguration
        } catch (_: Exception) {
            runCatching { credentialStore.delete(key) }
            exchanged.accessToken.close()
            credentialKey = null
            _state.value = SharedMapState(
                connectionState = SharedConnectionState.AUTH_REQUIRED,
                serverUrl = parsedServer.origin,
                statusMessage = "The Device Token could not be stored securely.",
            )
            return@withLock
        }
        credentialKey = key
        accessToken = exchanged.accessToken
        _state.value = _state.value.copy(
            meta = meta,
            selectedWorkspaceId = exchanged.workspace.workspaceId,
        )
        val continuePolling = refreshAuthenticated(initial = true)
        if (continuePolling) startPolling()
    }

    suspend fun switchWorkspace(workspaceId: String) = lifecycleMutex.withLock {
        ensureOpen()
        val currentServer = server ?: return@withLock
        val canonicalWorkspaceId = canonicalWorkspaceIdOrNull(workspaceId)
            ?: throw IllegalArgumentException("Workspace ID is invalid")
        if (_state.value.selectedWorkspaceId == canonicalWorkspaceId && _state.value.snapshot != null) return@withLock
        stopPolling()
        clearActiveCredential()
        credentialKey = null
        val key = SharedCredentialKey(currentServer.origin, canonicalWorkspaceId)
        val token = try {
            credentialStore.load(key)
        } catch (_: Exception) {
            publishCredentialFailure(currentServer.origin, canonicalWorkspaceId)
            return@withLock
        }
        if (token == null) {
            publishCredentialFailure(currentServer.origin, canonicalWorkspaceId)
            return@withLock
        }
        credentialKey = key
        accessToken = token
        val newConfiguration = configuration.copy(
            serverUrl = currentServer.origin,
            selectedWorkspaceId = canonicalWorkspaceId,
        )
        try {
            configurationSink.save(newConfiguration)
            configuration = newConfiguration
        } catch (_: Exception) {
            token.close()
            accessToken = null
            credentialKey = null
            _state.value = SharedMapState(
                connectionState = SharedConnectionState.AUTH_REQUIRED,
                serverUrl = currentServer.origin,
                statusMessage = "The Workspace selection could not be saved.",
            )
            return@withLock
        }
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.CONNECTING,
            serverUrl = currentServer.origin,
            selectedWorkspaceId = canonicalWorkspaceId,
            statusMessage = "Loading Shared Map Workspace…",
        )
        val continuePolling = refreshAuthenticated(initial = true)
        if (continuePolling) startPolling()
    }

    suspend fun refreshNow() {
        ensureOpen()
        if (_state.value.connectionState !in REFRESHABLE_STATES) return
        if (!refreshMutex.tryLock()) return
        try {
            refreshAuthenticatedLocked(initial = false)
        } finally {
            refreshMutex.unlock()
        }
    }

    suspend fun disconnect() = lifecycleMutex.withLock {
        ensureOpen()
        stopPolling()
        val currentServerUrl = server?.origin ?: configuration.serverUrl
        try {
            credentialKey?.let(credentialStore::delete)
        } catch (error: Exception) {
            if (_state.value.connectionState in REFRESHABLE_STATES && accessToken != null) startPolling()
            throw error
        }
        clearActiveCredential()
        credentialKey = null
        val disconnectedConfiguration = configuration.copy(
            serverUrl = currentServerUrl,
            selectedWorkspaceId = null,
        )
        configuration = disconnectedConfiguration
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.DISCONNECTED,
            serverUrl = currentServerUrl,
            statusMessage = if (currentServerUrl == null) null else "Disconnected from Shared Map.",
        )
        configurationSink.save(disconnectedConfiguration)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pollingJob?.cancel()
        pollingJob = null
        clearActiveCredential()
        credentialKey = null
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.DISCONNECTED,
            serverUrl = server?.origin ?: configuration.serverUrl,
        )
        scope.cancel()
        client.close()
    }

    private suspend fun refreshAuthenticated(initial: Boolean): Boolean = refreshMutex.withLock {
        refreshAuthenticatedLocked(initial)
    }

    private suspend fun refreshAuthenticatedLocked(initial: Boolean): Boolean {
        val currentServer = server ?: return false
        val token = accessToken ?: return false
        val selectedWorkspaceId = credentialKey?.workspaceId ?: configuration.selectedWorkspaceId ?: return false
        return try {
            val meta = client.getMeta(currentServer)
            if (!publishProtocolResult(meta, initial)) return false
            val identity = client.getMe(currentServer, token)
            val workspaces = client.getWorkspaces(currentServer, token)
            val selected = selectWorkspace(selectedWorkspaceId, identity, workspaces)
            if (selected == null) {
                _state.value = SharedMapState(
                    connectionState = SharedConnectionState.FORBIDDEN,
                    serverUrl = currentServer.origin,
                    meta = meta,
                    identity = identity,
                    workspaces = workspaces,
                    statusMessage = "This Device Token no longer has access to the selected Workspace.",
                )
                false
            } else {
                val snapshot = client.getMarkerSnapshot(currentServer, token, selected.workspaceId)
                require(snapshot.workspaceId == selected.workspaceId) { "Snapshot Workspace does not match the selection" }
                publishOnline(meta, identity, workspaces, selected, snapshot)
                true
            }
        } catch (error: Exception) {
            publishFailure(error, initial)
        }
    }

    private fun startPolling() {
        check(pollingJob == null || pollingJob?.isActive != true) { "A Shared Map polling loop is already active" }
        pollingJob = scope.launch {
            while (isActive) {
                delayFunction(pollingIntervalMillis)
                if (!isActive) break
                val continuePolling = try {
                    refreshAuthenticated(initial = false)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (error: Exception) {
                    publishFailure(error, initial = false)
                }
                if (!continuePolling) break
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun publishOnline(
        meta: SharedServerMeta,
        identity: SharedIdentity,
        workspaces: List<SharedWorkspace>,
        selected: SharedWorkspace,
        snapshot: SharedMarkerSnapshot,
    ) {
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.ONLINE,
            serverUrl = server?.origin,
            meta = meta,
            identity = identity.copy(workspace = selected),
            workspaces = workspaces,
            selectedWorkspaceId = selected.workspaceId,
            snapshot = snapshot,
            stale = false,
            lastSuccessfulSyncAt = clock.instant(),
            statusMessage = "Shared Map is connected.",
        )
    }

    private fun publishProtocolResult(meta: SharedServerMeta, initial: Boolean): Boolean {
        if (meta.supportsClient && meta.supportsSharedMarkers) {
            _state.value = _state.value.copy(meta = meta)
            return true
        }
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.PROTOCOL_UNSUPPORTED,
            serverUrl = server?.origin,
            meta = meta,
            selectedWorkspaceId = configuration.selectedWorkspaceId,
            snapshot = if (initial) null else _state.value.snapshot,
            stale = !initial && _state.value.snapshot != null,
            lastSuccessfulSyncAt = if (initial) null else _state.value.lastSuccessfulSyncAt,
            statusMessage = if (!meta.supportsClient) {
                "The Shared Map server requires a different protocol version."
            } else {
                "The server does not advertise Shared Marker support."
            },
        )
        return false
    }

    private suspend fun publishFailure(error: Exception, initial: Boolean): Boolean {
        if (error is CancellationException) throw error
        val mapped = (error as? SharedMapException)?.error
            ?: SharedMapError.InvalidResponse("The Shared Map response could not be processed.")
        val previous = _state.value
        return when (mapped) {
            is SharedMapError.Authentication -> {
                _state.value = previous.copy(
                    connectionState = SharedConnectionState.AUTH_REQUIRED,
                    identity = null,
                    workspaces = emptyList(),
                    stale = previous.snapshot != null,
                    statusMessage = "Authentication is required to reconnect Shared Map.",
                    requestId = mapped.requestId,
                )
                false
            }
            is SharedMapError.Forbidden -> {
                val refreshedAuthority = refreshIdentityAfterForbidden()
                _state.value = SharedMapState(
                    connectionState = SharedConnectionState.FORBIDDEN,
                    serverUrl = server?.origin,
                    meta = previous.meta,
                    identity = refreshedAuthority?.first,
                    workspaces = refreshedAuthority?.second.orEmpty(),
                    selectedWorkspaceId = previous.selectedWorkspaceId,
                    statusMessage = "Access to this Shared Map Workspace was removed.",
                    requestId = mapped.requestId,
                )
                false
            }
            is SharedMapError.Protocol -> {
                _state.value = previous.copy(
                    connectionState = SharedConnectionState.PROTOCOL_UNSUPPORTED,
                    stale = previous.snapshot != null,
                    statusMessage = mapped.message,
                    requestId = mapped.requestId,
                )
                false
            }
            is SharedMapError.InvalidConfiguration -> {
                _state.value = SharedMapState(
                    connectionState = SharedConnectionState.DISCONNECTED,
                    serverUrl = server?.origin,
                    statusMessage = mapped.message,
                )
                false
            }
            is SharedMapError.NotFound -> {
                _state.value = SharedMapState(
                    connectionState = SharedConnectionState.FORBIDDEN,
                    serverUrl = server?.origin,
                    meta = previous.meta,
                    selectedWorkspaceId = previous.selectedWorkspaceId,
                    statusMessage = "The selected Shared Map Workspace is unavailable.",
                    requestId = mapped.requestId,
                )
                false
            }
            is SharedMapError.Network,
            is SharedMapError.Server,
            is SharedMapError.RateLimited,
            is SharedMapError.InvalidResponse,
            -> {
                val snapshot = if (initial) null else previous.snapshot
                val hasSnapshot = snapshot != null
                _state.value = previous.copy(
                    connectionState = if (hasSnapshot) SharedConnectionState.DEGRADED else SharedConnectionState.OFFLINE,
                    snapshot = snapshot,
                    stale = hasSnapshot,
                    lastSuccessfulSyncAt = if (hasSnapshot) previous.lastSuccessfulSyncAt else null,
                    statusMessage = if (hasSnapshot) {
                        "Shared Map is temporarily unavailable; the last successful snapshot is retained."
                    } else {
                        "Shared Map is offline; the main map remains available."
                    },
                    requestId = mapped.requestId,
                )
                true
            }
        }
    }

    private suspend fun refreshIdentityAfterForbidden(): Pair<SharedIdentity, List<SharedWorkspace>>? {
        val currentServer = server ?: return null
        val token = accessToken ?: return null
        return try {
            val identity = client.getMe(currentServer, token)
            val workspaces = client.getWorkspaces(currentServer, token)
            identity to workspaces
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A revoked membership commonly rejects this classification refresh too.
            null
        }
    }

    private fun selectWorkspace(
        workspaceId: String,
        identity: SharedIdentity,
        workspaces: List<SharedWorkspace>,
    ): SharedWorkspace? {
        val selected = workspaces.firstOrNull { it.workspaceId == workspaceId } ?: return null
        if (identity.workspace.workspaceId != workspaceId) return null
        return selected
    }

    private fun parseServerOrPublish(rawServerUrl: String): SharedServerUrl? = try {
        SharedServerUrl.parse(rawServerUrl)
    } catch (_: IllegalArgumentException) {
        server = null
        credentialKey = null
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.DISCONNECTED,
            statusMessage = "Use HTTPS for remote Shared Map servers; HTTP is allowed only for localhost.",
        )
        null
    }

    private fun publishCredentialFailure(serverUrl: String, workspaceId: String) {
        _state.value = SharedMapState(
            connectionState = SharedConnectionState.AUTH_REQUIRED,
            serverUrl = serverUrl,
            selectedWorkspaceId = workspaceId,
            statusMessage = "The saved Device Token is missing or unreadable. Use a new invite to reconnect.",
        )
    }

    private fun clearActiveCredential() {
        accessToken?.close()
        accessToken = null
    }

    private fun ensureOpen() = check(!closed.get()) { "Shared Map session is closed" }

    private fun canonicalWorkspaceIdOrNull(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { UUID.fromString(value).toString() }
            .getOrNull()
            ?.takeIf { it == value }
    }

    private fun SharedMapConfiguration.normalizedDeviceName(): SharedMapConfiguration = copy(
        deviceName = deviceName.trim().ifEmpty { DEFAULT_SHARED_MAP_DEVICE_NAME },
    )

    companion object {
        const val DEFAULT_POLLING_INTERVAL_MILLIS = 30_000L
        private const val MAX_DEVICE_NAME_CODE_POINTS = 80
        private val REFRESHABLE_STATES = setOf(
            SharedConnectionState.ONLINE,
            SharedConnectionState.DEGRADED,
            SharedConnectionState.OFFLINE,
        )
    }
}
