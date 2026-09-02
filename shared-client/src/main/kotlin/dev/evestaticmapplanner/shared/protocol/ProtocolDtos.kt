package dev.evestaticmapplanner.shared.protocol

import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.auth.SecretValueSerializer
import dev.evestaticmapplanner.shared.model.SharedDevice
import dev.evestaticmapplanner.shared.model.SharedIdentity
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedServerMeta
import dev.evestaticmapplanner.shared.model.SharedUser
import dev.evestaticmapplanner.shared.model.SharedWorkspace
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

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
    @Serializable(with = SecretValueSerializer::class)
    val inviteToken: SecretValue,
    val deviceName: String,
) {
    override fun toString(): String = "ExchangeInviteRequestDto(inviteToken=${SecretValue.REDACTED}, deviceName=$deviceName)"
}

@Serializable
class ExchangeInviteResponseDto(
    @Serializable(with = SecretValueSerializer::class)
    val accessToken: SecretValue,
    val tokenId: String,
    val expiresAt: String,
    val user: UserDto,
    val workspace: WorkspaceDto,
) {
    override fun toString(): String =
        "ExchangeInviteResponseDto(accessToken=${SecretValue.REDACTED}, tokenId=$tokenId, workspace=${workspace.workspaceId})"
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
data class ApiErrorDto(
    val code: String,
    val message: String,
    val requestId: String,
    val details: JsonObject? = null,
)

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
    accessToken = accessToken,
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

private fun SharedMarkerDto.toDomain(): SharedMarker = SharedMarker(
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
