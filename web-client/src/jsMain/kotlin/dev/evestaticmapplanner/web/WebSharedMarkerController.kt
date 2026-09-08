package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.shared.protocol.CreateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.MeResponseDto
import dev.evestaticmapplanner.shared.protocol.MetaResponseDto
import dev.evestaticmapplanner.shared.protocol.SHARED_MAP_PROTOCOL_VERSION
import dev.evestaticmapplanner.shared.protocol.SHARED_MARKER_COLORS
import dev.evestaticmapplanner.shared.protocol.SHARED_MARKERS_FEATURE
import dev.evestaticmapplanner.shared.protocol.SHARED_WORKSPACE_ROLES
import dev.evestaticmapplanner.shared.protocol.SharedMarkerDto
import dev.evestaticmapplanner.shared.protocol.SharedMarkerSnapshotResponseDto
import dev.evestaticmapplanner.shared.protocol.UpdateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.WorkspaceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class WebSharedMarkerStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_FAILED,
    FORBIDDEN,
    FAILED,
}

data class WebSharedMarkerState(
    val status: WebSharedMarkerStatus = WebSharedMarkerStatus.DISCONNECTED,
    val serverOrigin: String? = null,
    val workspace: WorkspaceDto? = null,
    val markers: Map<String, SharedMarkerDto> = emptyMap(),
    val revision: Long? = null,
    val generatedAt: String? = null,
    val selectedMarkerId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val requestId: String? = null,
    val reconnectAttempt: Int = 0,
) {
    val canWrite: Boolean
        get() = status == WebSharedMarkerStatus.CONNECTED && workspace?.role in setOf("EDITOR", "ADMIN")
    val selectedMarker: SharedMarkerDto? get() = selectedMarkerId?.let(markers::get)
    val markersBySystemId: Map<Int, SharedMarkerDto> get() = markers.values.associateBy(SharedMarkerDto::systemId)
}

data class WebSharedMarkerDraft(
    val name: String,
    val color: String,
    val tags: List<String>,
    val notes: String?,
)

object WebSharedMarkerReconnectPolicy {
    const val POLL_INTERVAL_MILLIS = 30_000L
    private const val INITIAL_RECONNECT_MILLIS = 5_000L
    private const val MAX_RECONNECT_MILLIS = 30_000L

    fun delayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 3)
        return (INITIAL_RECONNECT_MILLIS * (1 shl exponent)).coerceAtMost(MAX_RECONNECT_MILLIS)
    }
}

class WebSharedMarkerController(
    private val client: SharedMarkerClient,
    private val scope: CoroutineScope,
    private val onStateChanged: (WebSharedMarkerState) -> Unit,
    private val delayFunction: suspend (Long) -> Unit = { delay(it) },
    private val idempotencyKeyFactory: () -> String = ::browserUuid,
    private val pollingEnabled: Boolean = true,
) : AutoCloseable {
    var state: WebSharedMarkerState = WebSharedMarkerState()
        private set

    private var accessToken: String? = null
    private var pollJob: Job? = null
    private var closed = false

    suspend fun connect(rawServerUrl: String, inviteCode: String, deviceName: String = DEFAULT_WEB_DEVICE_NAME) {
        ensureOpen()
        stopPolling()
        clearCredential()
        val origin = try {
            normalizeSharedServerOrigin(rawServerUrl)
        } catch (error: IllegalArgumentException) {
            publish(
                WebSharedMarkerState(
                    status = WebSharedMarkerStatus.FAILED,
                    error = error.message ?: "Server URL is invalid.",
                ),
            )
            return
        }
        val normalizedInvite = inviteCode.trim()
        val normalizedDevice = deviceName.trim().ifEmpty { DEFAULT_WEB_DEVICE_NAME }
        if (normalizedInvite.isEmpty()) {
            publish(WebSharedMarkerState(WebSharedMarkerStatus.FAILED, origin, error = "Invite Code is required."))
            return
        }
        if (normalizedDevice.length > 80) {
            publish(WebSharedMarkerState(WebSharedMarkerStatus.FAILED, origin, error = "Device name is too long."))
            return
        }
        publish(
            WebSharedMarkerState(
                status = WebSharedMarkerStatus.CONNECTING,
                serverOrigin = origin,
                message = "Connecting to Shared Marker…",
            ),
        )
        try {
            val meta = client.getMeta(origin).validated()
            val exchanged = client.exchangeInvite(origin, normalizedInvite, normalizedDevice)
            val exchangedWorkspace = exchanged.workspace.validated()
            canonicalUuid(exchanged.tokenId)
            require(exchanged.accessToken.isNotBlank()) { "Access token is invalid." }
            accessToken = exchanged.accessToken
            publish(
                state.copy(
                    workspace = exchangedWorkspace,
                    message = "Invite accepted; loading Shared Markers…",
                    error = null,
                ),
            )
            refreshAuthenticated(meta)
            startPolling()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleFailure(error, initial = accessToken == null)
            if (accessToken != null && state.status == WebSharedMarkerStatus.RECONNECTING) startPolling()
        }
    }

    suspend fun refreshNow() {
        ensureOpen()
        if (accessToken == null || state.serverOrigin == null || state.workspace == null || state.busy) return
        try {
            refreshAuthenticated()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleFailure(error, initial = false)
        }
    }

    suspend fun createMarker(systemId: Int, draft: WebSharedMarkerDraft) = mutate {
        require(systemId > 0) { "Choose a valid system." }
        val normalized = draft.validated()
        val context = writeContext()
        val marker = client.createMarker(
            context.origin,
            context.token,
            context.workspaceId,
            CreateSharedMarkerRequestDto(systemId, normalized.name, normalized.color, normalized.tags, normalized.notes),
            idempotencyKeyFactory(),
        ).validated(context.workspaceId)
        reconcile(marker)
        publish(state.copy(selectedMarkerId = marker.markerId, message = "Shared Marker created.", error = null))
    }

    suspend fun updateMarker(markerId: String, expectedVersion: Long, draft: WebSharedMarkerDraft) = mutate {
        require(expectedVersion > 0) { "Marker version is invalid." }
        val normalized = draft.validated()
        val context = writeContext()
        val marker = client.updateMarker(
            context.origin,
            context.token,
            context.workspaceId,
            canonicalUuid(markerId),
            UpdateSharedMarkerRequestDto(
                expectedVersion,
                normalized.name,
                normalized.color,
                normalized.tags,
                normalized.notes,
            ),
            idempotencyKeyFactory(),
        ).validated(context.workspaceId)
        reconcile(marker)
        publish(state.copy(selectedMarkerId = marker.markerId, message = "Shared Marker updated.", error = null))
    }

    suspend fun deleteMarker(markerId: String, expectedVersion: Long) = mutate {
        require(expectedVersion > 0) { "Marker version is invalid." }
        val context = writeContext()
        val canonicalMarkerId = canonicalUuid(markerId)
        client.deleteMarker(
            context.origin,
            context.token,
            context.workspaceId,
            canonicalMarkerId,
            expectedVersion,
            idempotencyKeyFactory(),
        )
        publish(
            state.copy(
                markers = state.markers - canonicalMarkerId,
                selectedMarkerId = state.selectedMarkerId.takeUnless { it == canonicalMarkerId },
                message = "Shared Marker deleted.",
                error = null,
            ),
        )
    }

    fun selectMarker(markerId: String?) {
        val selected = markerId?.takeIf(state.markers::containsKey)
        if (selected != state.selectedMarkerId) publish(state.copy(selectedMarkerId = selected))
    }

    fun selectMarkerAtSystem(systemId: Int?) {
        selectMarker(systemId?.let(state.markersBySystemId::get)?.markerId)
    }

    fun disconnect() {
        if (closed) return
        stopPolling()
        clearCredential()
        publish(
            WebSharedMarkerState(
                status = WebSharedMarkerStatus.DISCONNECTED,
                serverOrigin = state.serverOrigin,
                message = "Disconnected from Shared Marker.",
            ),
        )
    }

    fun onPageVisible() {
        if (!closed && state.status in setOf(WebSharedMarkerStatus.CONNECTED, WebSharedMarkerStatus.RECONNECTING)) {
            scope.launch { refreshNow() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stopPolling()
        clearCredential()
        scope.cancel()
    }

    private suspend fun refreshAuthenticated(knownMeta: MetaResponseDto? = null) {
        val origin = checkNotNull(state.serverOrigin)
        val token = checkNotNull(accessToken)
        val expectedWorkspace = checkNotNull(state.workspace).workspaceId
        (knownMeta ?: client.getMeta(origin)).validated()
        val me = client.getMe(origin, token).validated()
        val workspaces = client.getWorkspaces(origin, token).workspaces.map(WorkspaceDto::validated)
        val selected = workspaces.singleOrNull { it.workspaceId == expectedWorkspace }
            ?: throw SharedMarkerTransportException(
                SharedMarkerTransportError(
                    SharedMarkerTransportErrorKind.FORBIDDEN,
                    "This Device Token no longer has access to the Shared Marker workspace.",
                ),
            )
        require(me.workspace.workspaceId == selected.workspaceId) { "Authenticated workspace does not match." }
        val snapshot = client.getMarkerSnapshot(origin, token, selected.workspaceId).validated(selected.workspaceId)
        publish(
            state.copy(
                status = WebSharedMarkerStatus.CONNECTED,
                workspace = selected,
                markers = snapshot.markers.associateBy(SharedMarkerDto::markerId),
                revision = snapshot.revision,
                generatedAt = snapshot.generatedAt,
                selectedMarkerId = state.selectedMarkerId?.takeIf { it in snapshot.markers.map(SharedMarkerDto::markerId) },
                busy = false,
                message = "Shared Marker is connected.",
                error = null,
                requestId = null,
                reconnectAttempt = 0,
            ),
        )
    }

    private fun startPolling() {
        if (!pollingEnabled || pollJob?.isActive == true || accessToken == null) return
        pollJob = scope.launch {
            while (isActive && accessToken != null) {
                val delayMillis = if (state.status == WebSharedMarkerStatus.RECONNECTING) {
                    WebSharedMarkerReconnectPolicy.delayMillis(state.reconnectAttempt.coerceAtLeast(1))
                } else {
                    WebSharedMarkerReconnectPolicy.POLL_INTERVAL_MILLIS
                }
                delayFunction(delayMillis)
                if (!isActive || accessToken == null) break
                refreshNow()
                if (state.status in setOf(
                        WebSharedMarkerStatus.AUTH_FAILED,
                        WebSharedMarkerStatus.FORBIDDEN,
                        WebSharedMarkerStatus.FAILED,
                        WebSharedMarkerStatus.DISCONNECTED,
                    )
                ) break
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun mutate(operation: suspend () -> Unit) {
        ensureOpen()
        if (state.busy) return
        if (!state.canWrite) {
            publish(state.copy(error = "Shared Marker is read-only unless an EDITOR or ADMIN is connected.", message = null))
            return
        }
        publish(state.copy(busy = true, error = null, message = null))
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val transport = (error as? SharedMarkerTransportException)?.error
            transport?.currentMarker?.let { current ->
                runCatching { current.validated(state.workspace?.workspaceId) }.getOrNull()?.let(::reconcile)
            }
            if (transport?.kind in setOf(
                    SharedMarkerTransportErrorKind.NETWORK,
                    SharedMarkerTransportErrorKind.SERVER,
                    SharedMarkerTransportErrorKind.AUTHENTICATION,
                    SharedMarkerTransportErrorKind.FORBIDDEN,
                )
            ) {
                handleFailure(error, initial = false)
            } else {
                publish(
                    state.copy(
                        busy = false,
                        message = null,
                        error = transport?.message ?: error.message ?: "Shared Marker operation failed.",
                        requestId = transport?.requestId,
                    ),
                )
            }
        } finally {
            if (state.busy) publish(state.copy(busy = false))
        }
    }

    private fun handleFailure(error: Throwable, initial: Boolean) {
        val transport = (error as? SharedMarkerTransportException)?.error
        val kind = transport?.kind ?: SharedMarkerTransportErrorKind.INVALID_RESPONSE
        when (kind) {
            SharedMarkerTransportErrorKind.AUTHENTICATION -> {
                stopPolling()
                clearCredential()
                publish(
                    state.copy(
                        status = WebSharedMarkerStatus.AUTH_FAILED,
                        busy = false,
                        message = null,
                        error = "Authentication expired or was revoked. Enter a new Invite Code to reconnect.",
                        requestId = transport?.requestId,
                    ),
                )
            }
            SharedMarkerTransportErrorKind.FORBIDDEN,
            SharedMarkerTransportErrorKind.NOT_FOUND,
            -> {
                stopPolling()
                clearCredential()
                publish(
                    state.copy(
                        status = WebSharedMarkerStatus.FORBIDDEN,
                        markers = emptyMap(),
                        selectedMarkerId = null,
                        busy = false,
                        message = null,
                        error = transport?.message ?: "Access to this Shared Marker workspace was removed.",
                        requestId = transport?.requestId,
                    ),
                )
            }
            SharedMarkerTransportErrorKind.NETWORK,
            SharedMarkerTransportErrorKind.SERVER,
            SharedMarkerTransportErrorKind.RATE_LIMITED,
            -> {
                val attempt = if (initial) 0 else state.reconnectAttempt + 1
                publish(
                    state.copy(
                        status = if (initial && accessToken == null) WebSharedMarkerStatus.FAILED else WebSharedMarkerStatus.RECONNECTING,
                        busy = false,
                        message = if (accessToken == null) null else "Connection lost; retrying automatically.",
                        error = transport?.message ?: "Shared Marker server is unavailable.",
                        requestId = transport?.requestId,
                        reconnectAttempt = attempt,
                    ),
                )
            }
            else -> publish(
                state.copy(
                    status = WebSharedMarkerStatus.FAILED,
                    busy = false,
                    message = null,
                    error = transport?.message ?: error.message ?: "Shared Marker response is invalid.",
                    requestId = transport?.requestId,
                ),
            )
        }
    }

    private fun reconcile(marker: SharedMarkerDto) {
        val withoutSystemDuplicate = state.markers.filterValues {
            it.markerId == marker.markerId || it.systemId != marker.systemId
        }
        publish(state.copy(markers = withoutSystemDuplicate + (marker.markerId to marker)))
    }

    private fun writeContext(): WriteContext {
        check(state.canWrite) { "Shared Marker is read-only." }
        return WriteContext(
            origin = checkNotNull(state.serverOrigin),
            token = checkNotNull(accessToken),
            workspaceId = checkNotNull(state.workspace).workspaceId,
        )
    }

    private fun publish(newState: WebSharedMarkerState) {
        state = newState
        onStateChanged(newState)
    }

    private fun clearCredential() {
        accessToken = null
    }

    private fun ensureOpen() = check(!closed) { "Shared Marker controller is closed." }

    private data class WriteContext(val origin: String, val token: String, val workspaceId: String)
}

private fun MetaResponseDto.validated(): MetaResponseDto {
    require(SHARED_MAP_PROTOCOL_VERSION in minimumClientProtocolVersion..maximumClientProtocolVersion) {
        "The Shared Marker server requires a different protocol version."
    }
    require(SHARED_MARKERS_FEATURE in features) { "The server does not advertise Shared Marker support." }
    return this
}

private fun MeResponseDto.validated(): MeResponseDto {
    user.validated()
    workspace.validated()
    canonicalUuid(device.tokenId)
    require(device.deviceName.isNotBlank()) { "Device name is invalid." }
    return this
}

private fun WorkspaceDto.validated(): WorkspaceDto {
    canonicalUuid(workspaceId)
    canonicalUuid(memberId)
    require(name.isNotBlank() && revision >= 0 && role in SHARED_WORKSPACE_ROLES) { "Workspace response is invalid." }
    return this
}

private fun SharedMarkerSnapshotResponseDto.validated(expectedWorkspaceId: String): SharedMarkerSnapshotResponseDto {
    require(canonicalUuid(workspaceId) == expectedWorkspaceId && revision >= 0) { "Marker snapshot is invalid." }
    val validatedMarkers = markers.map { it.validated(expectedWorkspaceId) }
    require(validatedMarkers.map(SharedMarkerDto::markerId).toSet().size == validatedMarkers.size) {
        "Marker snapshot contains duplicate IDs."
    }
    require(validatedMarkers.map(SharedMarkerDto::systemId).toSet().size == validatedMarkers.size) {
        "Marker snapshot contains duplicate systems."
    }
    return this
}

private fun SharedMarkerDto.validated(expectedWorkspaceId: String?): SharedMarkerDto {
    canonicalUuid(markerId)
    canonicalUuid(workspaceId)
    require(expectedWorkspaceId == null || workspaceId == expectedWorkspaceId) { "Marker workspace is invalid." }
    require(systemId > 0 && version > 0 && name.isNotBlank() && color in SHARED_MARKER_COLORS) {
        "Marker response is invalid."
    }
    createdBy.validated()
    updatedBy.validated()
    return this
}

private fun dev.evestaticmapplanner.shared.protocol.UserDto.validated() {
    canonicalUuid(userId)
    require(displayName.isNotBlank()) { "User response is invalid." }
}

private fun WebSharedMarkerDraft.validated(): WebSharedMarkerDraft {
    val normalizedName = name.trim()
    val normalizedTags = tags.map(String::trim).filter(String::isNotEmpty)
    val normalizedNotes = notes?.replace("\r\n", "\n")?.trim()?.takeIf(String::isNotEmpty)
    require(normalizedName.isNotEmpty() && normalizedName.length <= 80) { "Name is required and must be 80 characters or fewer." }
    require(color in SHARED_MARKER_COLORS) { "Marker color is invalid." }
    require(normalizedTags.size <= 9 && normalizedTags.distinct().size == normalizedTags.size) {
        "Use no more than 9 unique tags."
    }
    require(normalizedTags.all { TAG_PATTERN.matches(it) }) {
        "Tags must be lowercase and use letters, numbers, dots, underscores, or hyphens."
    }
    require(normalizedNotes == null || normalizedNotes.length <= 2_000) { "Notes must be 2,000 characters or fewer." }
    return copy(name = normalizedName, tags = normalizedTags, notes = normalizedNotes)
}

private val TAG_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private const val DEFAULT_WEB_DEVICE_NAME = "EVE Static Map Planner Web"
