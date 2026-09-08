package dev.evestaticmapplanner.shared.model

import java.time.Instant

const val SHARED_MAP_PROTOCOL_VERSION = 1
const val SHARED_MARKERS_FEATURE = "shared-markers"
const val ROUTE_HANDOFFS_FEATURE = "route-handoffs"
const val DEFAULT_SHARED_MAP_DEVICE_NAME = "EVE Static Map Planner"

data class SharedMapConfiguration(
    val serverUrl: String? = null,
    val selectedWorkspaceId: String? = null,
    val deviceName: String = DEFAULT_SHARED_MAP_DEVICE_NAME,
)

data class SharedServerMeta(
    val serverVersion: String,
    val protocolVersion: Int,
    val minimumClientProtocolVersion: Int,
    val maximumClientProtocolVersion: Int,
    val features: Set<String>,
    val universeBuild: String,
) {
    val supportsClient: Boolean
        get() = SHARED_MAP_PROTOCOL_VERSION in minimumClientProtocolVersion..maximumClientProtocolVersion
    val supportsSharedMarkers: Boolean get() = SHARED_MARKERS_FEATURE in features
    val supportsRouteHandoffs: Boolean get() = ROUTE_HANDOFFS_FEATURE in features
}

enum class SharedWorkspaceRole { VIEWER, EDITOR, ADMIN }

data class SharedUser(val userId: String, val displayName: String)

data class SharedWorkspace(
    val workspaceId: String,
    val name: String,
    val role: SharedWorkspaceRole,
    val revision: Long,
    val memberId: String,
)

data class SharedDevice(
    val tokenId: String,
    val deviceName: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant,
)

data class SharedIdentity(
    val user: SharedUser,
    val workspace: SharedWorkspace,
    val device: SharedDevice,
)

enum class SharedMarkerColor { RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, WHITE }

data class SharedMarker(
    val markerId: String,
    val workspaceId: String,
    val systemId: Int,
    val name: String,
    val color: SharedMarkerColor,
    val tags: List<String>,
    val notes: String?,
    val createdBy: SharedUser,
    val updatedBy: SharedUser,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class SharedMarkerDraft(
    val name: String,
    val color: SharedMarkerColor,
    val tags: List<String>,
    val notes: String?,
)

data class SharedMember(
    val memberId: String,
    val userId: String,
    val displayName: String,
    val role: SharedWorkspaceRole,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revokedAt: Instant?,
) {
    val isActive: Boolean get() = revokedAt == null
}

data class SharedInvite(
    val inviteId: String,
    val memberId: String,
    val expiresAt: Instant,
    val createdAt: Instant,
)

data class SharedMarkerSnapshot(
    val workspaceId: String,
    val revision: Long,
    val generatedAt: Instant,
    val markers: Map<String, SharedMarker>,
)

enum class SharedRouteHandoffType { NORMAL, CAPITAL }

data class SharedRouteHandoffMapMetadata(
    val universeBuild: String,
    val plannerVersion: String,
    val webPackVersion: String?,
)

data class SharedRouteHandoffEdge(
    val fromSystemId: Int,
    val toSystemId: Int,
    val type: String,
    val distanceLy: Double?,
)

data class SharedRouteHandoffDraft(
    val type: SharedRouteHandoffType,
    val originSystemId: Int,
    val waypointSystemIds: List<Int>,
    val destinationSystemId: Int,
    val useAnsiblex: Boolean?,
    val capitalRangeLy: Double?,
    val jumpProfileId: String?,
    val resolvedSystemIds: List<Int>,
    val resolvedEdges: List<SharedRouteHandoffEdge>,
    val mapMetadata: SharedRouteHandoffMapMetadata,
)

data class SharedRouteHandoffPublisher(
    val memberId: String,
    val userId: String,
    val displayName: String,
    val deviceTokenId: String,
    val deviceName: String,
)

data class SharedRouteHandoff(
    val routeHandoffId: String,
    val workspaceId: String,
    val publisher: SharedRouteHandoffPublisher,
    val createdAt: Instant,
    val expiresAt: Instant,
    val route: SharedRouteHandoffDraft,
)

enum class SharedConnectionState {
    DISCONNECTED,
    CONNECTING,
    ONLINE,
    DEGRADED,
    OFFLINE,
    AUTH_REQUIRED,
    FORBIDDEN,
    PROTOCOL_UNSUPPORTED,
}

data class SharedMapState(
    val connectionState: SharedConnectionState = SharedConnectionState.DISCONNECTED,
    val serverUrl: String? = null,
    val meta: SharedServerMeta? = null,
    val identity: SharedIdentity? = null,
    val workspaces: List<SharedWorkspace> = emptyList(),
    val selectedWorkspaceId: String? = null,
    val snapshot: SharedMarkerSnapshot? = null,
    val stale: Boolean = false,
    val lastSuccessfulSyncAt: Instant? = null,
    val statusMessage: String? = null,
    val requestId: String? = null,
) {
    val markerCount: Int get() = snapshot?.markers?.size ?: 0
    val selectedWorkspace: SharedWorkspace?
        get() = workspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }
}
