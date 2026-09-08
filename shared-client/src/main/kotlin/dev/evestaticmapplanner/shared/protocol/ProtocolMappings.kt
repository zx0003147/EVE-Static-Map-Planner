package dev.evestaticmapplanner.shared.protocol

import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.model.SharedDevice
import dev.evestaticmapplanner.shared.model.SharedIdentity
import dev.evestaticmapplanner.shared.model.SharedInvite
import dev.evestaticmapplanner.shared.model.SharedMember
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedServerMeta
import dev.evestaticmapplanner.shared.model.SharedUser
import dev.evestaticmapplanner.shared.model.SharedWorkspace
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.shared.model.SharedRouteHandoff
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffDraft
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffEdge
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffMapMetadata
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffPublisher
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffType
import java.time.Instant
import java.util.UUID

data class ExchangedCredential(
    val accessToken: SecretValue,
    val tokenId: String,
    val expiresAt: Instant,
    val user: SharedUser,
    val workspace: SharedWorkspace,
)

fun MetaResponseDto.toDomain(): SharedServerMeta = SharedServerMeta(
    serverVersion = serverVersion,
    protocolVersion = protocolVersion,
    minimumClientProtocolVersion = minimumClientProtocolVersion,
    maximumClientProtocolVersion = maximumClientProtocolVersion,
    features = features.toSet(),
    universeBuild = universeBuild,
)

fun ExchangeInviteResponseDto.toDomain(): ExchangedCredential = ExchangedCredential(
    accessToken = SecretValue.from(accessToken),
    tokenId = canonicalUuid(tokenId, "tokenId"),
    expiresAt = instant(expiresAt, "expiresAt"),
    user = user.toDomain(),
    workspace = workspace.toDomain(),
)

fun MeResponseDto.toDomain(): SharedIdentity = SharedIdentity(
    user = user.toDomain(),
    workspace = workspace.toDomain(),
    device = device.toDomain(),
)

fun WorkspaceDto.toDomain(): SharedWorkspace = SharedWorkspace(
    workspaceId = canonicalUuid(workspaceId, "workspaceId"),
    name = name,
    role = enumValue<SharedWorkspaceRole>(role, "role"),
    revision = revision.also { require(it >= 0) { "revision is invalid" } },
    memberId = canonicalUuid(memberId, "memberId"),
)

fun SharedMarkerSnapshotResponseDto.toDomain(): SharedMarkerSnapshot {
    val canonicalWorkspaceId = canonicalUuid(workspaceId, "workspaceId")
    require(revision >= 0) { "revision is invalid" }
    val markerMap = markers.associate { dto ->
        val marker = dto.toDomain()
        require(marker.workspaceId == canonicalWorkspaceId) { "marker workspace does not match snapshot" }
        marker.markerId to marker
    }
    require(markerMap.size == markers.size) { "snapshot contains duplicate marker IDs" }
    return SharedMarkerSnapshot(
        workspaceId = canonicalWorkspaceId,
        revision = revision,
        generatedAt = instant(generatedAt, "generatedAt"),
        markers = markerMap,
    )
}

fun SharedMarkerDto.toDomain(): SharedMarker = SharedMarker(
    markerId = canonicalUuid(markerId, "markerId"),
    workspaceId = canonicalUuid(workspaceId, "workspaceId"),
    systemId = systemId.also { require(it > 0) { "systemId is invalid" } },
    name = name,
    color = enumValue<SharedMarkerColor>(color, "color"),
    tags = tags.toList(),
    notes = notes,
    createdBy = createdBy.toDomain(),
    updatedBy = updatedBy.toDomain(),
    createdAt = instant(createdAt, "createdAt"),
    updatedAt = instant(updatedAt, "updatedAt"),
    version = version.also { require(it > 0) { "version is invalid" } },
)

fun MemberDto.toDomain(): SharedMember = SharedMember(
    memberId = canonicalUuid(memberId, "memberId"),
    userId = canonicalUuid(userId, "userId"),
    displayName = displayName,
    role = enumValue<SharedWorkspaceRole>(role, "role"),
    version = version.also { require(it > 0) { "version is invalid" } },
    createdAt = instant(createdAt, "createdAt"),
    updatedAt = instant(updatedAt, "updatedAt"),
    revokedAt = revokedAt?.let { instant(it, "revokedAt") },
)

fun InviteCreatedResponseDto.toDomain(): Pair<SharedInvite, SecretValue> = SharedInvite(
    inviteId = canonicalUuid(inviteId, "inviteId"),
    memberId = canonicalUuid(memberId, "memberId"),
    expiresAt = instant(expiresAt, "expiresAt"),
    createdAt = instant(createdAt, "createdAt"),
) to SecretValue.from(inviteToken)

fun RouteHandoffDto.toDomain(): SharedRouteHandoff = SharedRouteHandoff(
    routeHandoffId = canonicalUuid(routeHandoffId, "routeHandoffId"),
    workspaceId = canonicalUuid(workspaceId, "workspaceId"),
    publisher = SharedRouteHandoffPublisher(
        canonicalUuid(publisher.memberId, "publisher.memberId"),
        canonicalUuid(publisher.userId, "publisher.userId"),
        publisher.displayName,
        canonicalUuid(publisher.deviceTokenId, "publisher.deviceTokenId"),
        publisher.deviceName,
    ),
    createdAt = instant(createdAt, "createdAt"),
    expiresAt = instant(expiresAt, "expiresAt"),
    route = SharedRouteHandoffDraft(
        type = enumValue<SharedRouteHandoffType>(type, "type"),
        originSystemId = originSystemId,
        waypointSystemIds = waypointSystemIds,
        destinationSystemId = destinationSystemId,
        useAnsiblex = useAnsiblex,
        capitalRangeLy = capitalRangeLy,
        jumpProfileId = jumpProfileId,
        resolvedSystemIds = resolvedSystemIds,
        resolvedEdges = resolvedEdges.map {
            SharedRouteHandoffEdge(it.fromSystemId, it.toSystemId, it.type, it.distanceLy)
        },
        mapMetadata = SharedRouteHandoffMapMetadata(
            mapMetadata.universeBuild,
            mapMetadata.plannerVersion,
            mapMetadata.webPackVersion,
        ),
    ),
)

fun SharedRouteHandoffDraft.toRequestDto(): PublishRouteHandoffRequestDto = PublishRouteHandoffRequestDto(
    type = type.name,
    originSystemId = originSystemId,
    waypointSystemIds = waypointSystemIds,
    destinationSystemId = destinationSystemId,
    useAnsiblex = useAnsiblex,
    capitalRangeLy = capitalRangeLy,
    jumpProfileId = jumpProfileId,
    resolvedSystemIds = resolvedSystemIds,
    resolvedEdges = resolvedEdges.map {
        RouteHandoffResolvedEdgeDto(it.fromSystemId, it.toSystemId, it.type, it.distanceLy)
    },
    mapMetadata = RouteHandoffMapMetadataDto(
        mapMetadata.universeBuild,
        mapMetadata.plannerVersion,
        mapMetadata.webPackVersion,
    ),
)

private fun UserDto.toDomain(): SharedUser = SharedUser(
    userId = canonicalUuid(userId, "userId"),
    displayName = displayName,
)

private fun DeviceDto.toDomain(): SharedDevice = SharedDevice(
    tokenId = canonicalUuid(tokenId, "tokenId"),
    deviceName = deviceName,
    createdAt = instant(createdAt, "createdAt"),
    lastUsedAt = lastUsedAt?.let { instant(it, "lastUsedAt") },
    expiresAt = instant(expiresAt, "expiresAt"),
)

private fun canonicalUuid(value: String, field: String): String {
    val parsed = runCatching { UUID.fromString(value) }.getOrElse { throw IllegalArgumentException("$field is invalid") }
    require(parsed.toString() == value) { "$field is not canonical" }
    return value
}

private fun instant(value: String, field: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw IllegalArgumentException("$field is invalid") }

private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    runCatching { enumValueOf<T>(value) }.getOrElse { throw IllegalArgumentException("$field is invalid") }
