package dev.evestaticmapplanner.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

const val SHARED_MAP_PROTOCOL_VERSION = 1
const val SHARED_MARKERS_FEATURE = "shared-markers"
const val ROUTE_HANDOFFS_FEATURE = "route-handoffs"
val SHARED_WORKSPACE_ROLES = setOf("VIEWER", "EDITOR", "ADMIN")
val SHARED_MARKER_COLORS = setOf("RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE", "WHITE")

val SHARED_MAP_PROTOCOL_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

@Serializable
data class MetaResponseDto(
    val serverVersion: String,
    val protocolVersion: Int,
    val minimumClientProtocolVersion: Int,
    val maximumClientProtocolVersion: Int,
    val features: List<String>,
    val universeBuild: String,
)

@Serializable
class ExchangeInviteRequestDto(
    val inviteToken: String,
    val deviceName: String,
) {
    override fun toString(): String = "ExchangeInviteRequestDto(inviteToken=<redacted>, deviceName=$deviceName)"
}

@Serializable
class ExchangeInviteResponseDto(
    val accessToken: String,
    val tokenId: String,
    val expiresAt: String,
    val user: UserDto,
    val workspace: WorkspaceDto,
) {
    override fun toString(): String =
        "ExchangeInviteResponseDto(accessToken=<redacted>, tokenId=$tokenId, workspace=${workspace.workspaceId})"
}

@Serializable
data class UserDto(val userId: String, val displayName: String)

@Serializable
data class WorkspaceDto(
    val workspaceId: String,
    val name: String,
    val role: String,
    val revision: Long,
    val memberId: String,
)

@Serializable
data class DeviceDto(
    val tokenId: String,
    val deviceName: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val expiresAt: String,
)

@Serializable
data class MeResponseDto(val user: UserDto, val workspace: WorkspaceDto, val device: DeviceDto)

@Serializable
data class WorkspacesResponseDto(val workspaces: List<WorkspaceDto>)

@Serializable
data class SharedMarkerDto(
    val markerId: String,
    val workspaceId: String,
    val systemId: Int,
    val name: String,
    val color: String,
    val tags: List<String>,
    val notes: String?,
    val createdBy: UserDto,
    val updatedBy: UserDto,
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
)

@Serializable
data class SharedMarkerSnapshotResponseDto(
    val workspaceId: String,
    val revision: Long,
    val generatedAt: String,
    val markers: List<SharedMarkerDto>,
)

@Serializable
data class RouteHandoffMapMetadataDto(
    val universeBuild: String,
    val plannerVersion: String,
    val webPackVersion: String? = null,
)

@Serializable
data class RouteHandoffResolvedEdgeDto(
    val fromSystemId: Int,
    val toSystemId: Int,
    val type: String,
    val distanceLy: Double? = null,
)

@Serializable
data class PublishRouteHandoffRequestDto(
    val type: String,
    val originSystemId: Int,
    val waypointSystemIds: List<Int> = emptyList(),
    val destinationSystemId: Int,
    val useAnsiblex: Boolean? = null,
    val capitalRangeLy: Double? = null,
    val jumpProfileId: String? = null,
    val resolvedSystemIds: List<Int>,
    val resolvedEdges: List<RouteHandoffResolvedEdgeDto>,
    val mapMetadata: RouteHandoffMapMetadataDto,
)

@Serializable
data class RouteHandoffPublisherDto(
    val memberId: String,
    val userId: String,
    val displayName: String,
    val deviceTokenId: String,
    val deviceName: String,
)

@Serializable
data class RouteHandoffDto(
    val routeHandoffId: String,
    val workspaceId: String,
    val publisher: RouteHandoffPublisherDto,
    val createdAt: String,
    val expiresAt: String,
    val type: String,
    val originSystemId: Int,
    val waypointSystemIds: List<Int>,
    val destinationSystemId: Int,
    val useAnsiblex: Boolean? = null,
    val capitalRangeLy: Double? = null,
    val jumpProfileId: String? = null,
    val resolvedSystemIds: List<Int>,
    val resolvedEdges: List<RouteHandoffResolvedEdgeDto>,
    val mapMetadata: RouteHandoffMapMetadataDto,
)

@Serializable
data class RouteHandoffListResponseDto(
    val generatedAt: String,
    val routeHandoffs: List<RouteHandoffDto>,
)

@Serializable
data class CreateSharedMarkerRequestDto(
    val systemId: Int,
    val name: String,
    val color: String,
    val tags: List<String>,
    val notes: String?,
)

@Serializable
data class UpdateSharedMarkerRequestDto(
    val expectedVersion: Long,
    val name: String,
    val color: String,
    val tags: List<String>,
    val notes: String?,
)

@Serializable
data class MemberDto(
    val memberId: String,
    val userId: String,
    val displayName: String,
    val role: String,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
    val revokedAt: String?,
)

@Serializable
data class MembersResponseDto(val members: List<MemberDto>)

@Serializable
data class CreateMemberRequestDto(val displayName: String, val role: String)

@Serializable
data class UpdateMemberRequestDto(
    val expectedVersion: Long,
    val displayName: String? = null,
    val role: String? = null,
)

@Serializable
data class CreateInviteRequestDto(val expiresInHours: Long = 72)

@Serializable
class InviteCreatedResponseDto(
    val inviteId: String,
    val inviteToken: String,
    val memberId: String,
    val expiresAt: String,
    val createdAt: String,
) {
    override fun toString(): String =
        "InviteCreatedResponseDto(inviteId=$inviteId, inviteToken=<redacted>, memberId=$memberId)"
}

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val requestId: String,
    val details: JsonObject? = null,
)
