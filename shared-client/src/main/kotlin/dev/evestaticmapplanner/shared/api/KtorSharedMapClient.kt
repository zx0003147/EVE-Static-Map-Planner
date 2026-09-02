package dev.evestaticmapplanner.shared.api

import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.model.SharedIdentity
import dev.evestaticmapplanner.shared.model.SharedInvite
import dev.evestaticmapplanner.shared.model.SharedMember
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerDraft
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedServerMeta
import dev.evestaticmapplanner.shared.model.SharedWorkspace
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.shared.protocol.CreateInviteRequestDto
import dev.evestaticmapplanner.shared.protocol.CreateMemberRequestDto
import dev.evestaticmapplanner.shared.protocol.CreateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.ApiErrorDto
import dev.evestaticmapplanner.shared.protocol.ExchangeInviteRequestDto
import dev.evestaticmapplanner.shared.protocol.ExchangeInviteResponseDto
import dev.evestaticmapplanner.shared.protocol.ExchangedCredential
import dev.evestaticmapplanner.shared.protocol.MeResponseDto
import dev.evestaticmapplanner.shared.protocol.MetaResponseDto
import dev.evestaticmapplanner.shared.protocol.InviteCreatedResponseDto
import dev.evestaticmapplanner.shared.protocol.MemberDto
import dev.evestaticmapplanner.shared.protocol.MembersResponseDto
import dev.evestaticmapplanner.shared.protocol.SharedMarkerSnapshotResponseDto
import dev.evestaticmapplanner.shared.protocol.SharedMarkerDto
import dev.evestaticmapplanner.shared.protocol.UpdateMemberRequestDto
import dev.evestaticmapplanner.shared.protocol.UpdateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.WorkspacesResponseDto
import dev.evestaticmapplanner.shared.protocol.toDomain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

class KtorSharedMapClient(
    private val client: HttpClient = defaultHttpClient(),
    private val ownsClient: Boolean = true,
) : SharedMapClient {
    override suspend fun getMeta(server: SharedServerUrl): SharedServerMeta = request {
        client.get(server.endpoint("/api/v1/meta")) { commonHeaders() }
    }.decode<MetaResponseDto>().toDomain()

    override suspend fun exchangeInvite(
        server: SharedServerUrl,
        invite: SecretValue,
        deviceName: String,
    ): ExchangedCredential = request {
        client.post(server.endpoint("/api/v1/auth/exchange-invite")) {
            commonHeaders()
            contentType(ContentType.Application.Json)
            setBody(ExchangeInviteRequestDto(invite, deviceName))
        }
    }.decode<ExchangeInviteResponseDto>().toDomain()

    override suspend fun getMe(server: SharedServerUrl, token: SecretValue): SharedIdentity = request {
        client.get(server.endpoint("/api/v1/me")) {
            commonHeaders()
            token.useString { bearerAuth(it) }
        }
    }.decode<MeResponseDto>().toDomain()

    override suspend fun getWorkspaces(server: SharedServerUrl, token: SecretValue): List<SharedWorkspace> = request {
        client.get(server.endpoint("/api/v1/workspaces")) {
            commonHeaders()
            token.useString { bearerAuth(it) }
        }
    }.decode<WorkspacesResponseDto>().workspaces.map { it.toDomain() }

    override suspend fun getMarkerSnapshot(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
    ): SharedMarkerSnapshot = request {
        val canonicalWorkspaceId = UUID.fromString(workspaceId).also {
            require(it.toString() == workspaceId) { "Workspace ID must be canonical" }
        }
        client.get(server.endpoint("/api/v1/workspaces/$canonicalWorkspaceId/markers")) {
            commonHeaders()
            token.useString { bearerAuth(it) }
        }
    }.decode<SharedMarkerSnapshotResponseDto>().toDomain()

    override suspend fun createSharedMarker(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        systemId: Int,
        draft: SharedMarkerDraft,
        idempotencyKey: UUID,
    ): SharedMarker = mutationRequest {
        client.post(server.endpoint("/api/v1/workspaces/${canonicalUuid(workspaceId)}/markers")) {
            authenticatedMutationHeaders(token, idempotencyKey)
            setBody(CreateSharedMarkerRequestDto(systemId, draft.name, draft.color.name, draft.tags, draft.notes))
        }
    }.decode<SharedMarkerDto>().toDomain()

    override suspend fun updateSharedMarker(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        markerId: String,
        expectedVersion: Long,
        draft: SharedMarkerDraft,
        idempotencyKey: UUID,
    ): SharedMarker = mutationRequest {
        client.patch(
            server.endpoint(
                "/api/v1/workspaces/${canonicalUuid(workspaceId)}/markers/${canonicalUuid(markerId)}",
            ),
        ) {
            authenticatedMutationHeaders(token, idempotencyKey)
            setBody(UpdateSharedMarkerRequestDto(expectedVersion, draft.name, draft.color.name, draft.tags, draft.notes))
        }
    }.decode<SharedMarkerDto>().toDomain()

    override suspend fun deleteSharedMarker(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        markerId: String,
        expectedVersion: Long,
        idempotencyKey: UUID,
    ) {
        mutationRequest {
            client.delete(
                server.endpoint(
                    "/api/v1/workspaces/${canonicalUuid(workspaceId)}/markers/${canonicalUuid(markerId)}" +
                        "?expectedVersion=$expectedVersion",
                ),
            ) { authenticatedMutationHeaders(token, idempotencyKey) }
        }
    }

    override suspend fun getMembers(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
    ): List<SharedMember> = request {
        client.get(server.endpoint("/api/v1/workspaces/${canonicalUuid(workspaceId)}/members")) {
            commonHeaders()
            token.useString { bearerAuth(it) }
        }
    }.decode<MembersResponseDto>().members.map(MemberDto::toDomain)

    override suspend fun createMember(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        displayName: String,
        role: SharedWorkspaceRole,
        idempotencyKey: UUID,
    ): SharedMember = mutationRequest {
        client.post(server.endpoint("/api/v1/workspaces/${canonicalUuid(workspaceId)}/members")) {
            authenticatedMutationHeaders(token, idempotencyKey)
            setBody(CreateMemberRequestDto(displayName, role.name))
        }
    }.decode<MemberDto>().toDomain()

    override suspend fun updateMember(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        memberId: String,
        expectedVersion: Long,
        displayName: String?,
        role: SharedWorkspaceRole?,
        idempotencyKey: UUID,
    ): SharedMember = mutationRequest {
        client.patch(
            server.endpoint(
                "/api/v1/workspaces/${canonicalUuid(workspaceId)}/members/${canonicalUuid(memberId)}",
            ),
        ) {
            authenticatedMutationHeaders(token, idempotencyKey)
            setBody(UpdateMemberRequestDto(expectedVersion, displayName, role?.name))
        }
    }.decode<MemberDto>().toDomain()

    override suspend fun deleteMember(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        memberId: String,
        expectedVersion: Long,
        idempotencyKey: UUID,
    ) {
        mutationRequest {
            client.delete(
                server.endpoint(
                    "/api/v1/workspaces/${canonicalUuid(workspaceId)}/members/${canonicalUuid(memberId)}" +
                        "?expectedVersion=$expectedVersion",
                ),
            ) { authenticatedMutationHeaders(token, idempotencyKey) }
        }
    }

    override suspend fun createInvite(
        server: SharedServerUrl,
        token: SecretValue,
        workspaceId: String,
        memberId: String,
        expiresInHours: Long,
        idempotencyKey: UUID,
    ): Pair<SharedInvite, SecretValue> = request {
        client.post(
            server.endpoint(
                "/api/v1/workspaces/${canonicalUuid(workspaceId)}/members/${canonicalUuid(memberId)}/invites",
            ),
        ) {
            authenticatedMutationHeaders(token, idempotencyKey)
            setBody(CreateInviteRequestDto(expiresInHours))
        }
    }.decode<InviteCreatedResponseDto>().toDomain()

    override fun close() {
        if (ownsClient) client.close()
    }

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: HttpRequestTimeoutException) {
            throw SharedMapException(SharedMapError.Network("The Shared Map request timed out."))
        } catch (_: SocketTimeoutException) {
            throw SharedMapException(SharedMapError.Network("The Shared Map request timed out."))
        } catch (_: IOException) {
            throw SharedMapException(SharedMapError.Network())
        } catch (_: IllegalArgumentException) {
            throw SharedMapException(SharedMapError.InvalidConfiguration("The Shared Map request is invalid."))
        } catch (_: IllegalStateException) {
            throw SharedMapException(SharedMapError.InvalidConfiguration("The Shared Map credential is unavailable."))
        }
        if (response.status.value in 200..299) return response
        throw SharedMapException(response.toDomainError())
    }

    private suspend fun mutationRequest(block: suspend () -> HttpResponse): HttpResponse {
        var lastNetworkFailure: SharedMapException? = null
        repeat(2) {
            try {
                return request(block)
            } catch (error: SharedMapException) {
                if (error.error !is SharedMapError.Network) throw error
                lastNetworkFailure = error
            }
        }
        throw checkNotNull(lastNetworkFailure)
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = try {
        body()
    } catch (_: CancellationException) {
        throw CancellationException()
    } catch (_: SerializationException) {
        throw SharedMapException(
            SharedMapError.InvalidResponse(
                "The Shared Map server returned an invalid response.",
                headers[REQUEST_ID_HEADER],
            ),
        )
    } catch (_: IllegalArgumentException) {
        throw SharedMapException(
            SharedMapError.InvalidResponse(
                "The Shared Map server returned an invalid response.",
                headers[REQUEST_ID_HEADER],
            ),
        )
    } catch (_: Exception) {
        throw SharedMapException(
            SharedMapError.InvalidResponse(
                "The Shared Map server returned an invalid response.",
                headers[REQUEST_ID_HEADER],
            ),
        )
    }

    private suspend fun HttpResponse.toDomainError(): SharedMapError {
        val responseRequestId = headers[REQUEST_ID_HEADER]
        val retryAfter = headers[HttpHeaders.RetryAfter]?.toLongOrNull()
        val error = runCatching { PROTOCOL_JSON.decodeFromString<ApiErrorDto>(bodyAsText()) }.getOrNull()
        val requestId = error?.requestId ?: responseRequestId
        val safeMessage = error?.message?.takeIf(String::isNotBlank) ?: "The Shared Map request failed."
        return when (error?.code) {
            "MARKER_ALREADY_EXISTS" -> SharedMapError.MarkerAlreadyExists(safeMessage, requestId)
            "MARKER_VERSION_CONFLICT" -> SharedMapError.MarkerVersionConflict(
                safeMessage,
                requestId,
                error.details?.get("currentMarker")?.let { markerJson ->
                    runCatching { PROTOCOL_JSON.decodeFromJsonElement<SharedMarkerDto>(markerJson).toDomain() }.getOrNull()
                },
            )
            "MEMBER_VERSION_CONFLICT" -> SharedMapError.MemberVersionConflict(safeMessage, requestId)
            "LAST_ADMIN_REQUIRED" -> SharedMapError.LastAdminRequired(safeMessage, requestId)
            "INVALID_ARGUMENT", "PAYLOAD_TOO_LARGE" -> SharedMapError.InvalidArgument(safeMessage, requestId)
            "IDEMPOTENCY_RESPONSE_NOT_REPLAYABLE" ->
                SharedMapError.IdempotencyResponseNotReplayable(safeMessage, requestId)
            else -> when (status) {
            HttpStatusCode.Unauthorized -> SharedMapError.Authentication(safeMessage, requestId)
            HttpStatusCode.Forbidden -> SharedMapError.Forbidden(safeMessage, requestId)
            HttpStatusCode.NotFound -> SharedMapError.NotFound(safeMessage, requestId)
            HttpStatusCode.TooManyRequests -> SharedMapError.RateLimited(safeMessage, requestId, retryAfter)
            HttpStatusCode.UpgradeRequired -> SharedMapError.Protocol(safeMessage, requestId)
            else -> when {
                status.value >= 500 -> SharedMapError.Server(safeMessage, requestId)
                else -> SharedMapError.InvalidResponse(safeMessage, requestId)
            }
            }
        }
    }

    private fun HttpRequestBuilder.commonHeaders() {
        accept(ContentType.Application.Json)
        header(REQUEST_ID_HEADER, UUID.randomUUID().toString())
    }

    private fun HttpRequestBuilder.authenticatedMutationHeaders(token: SecretValue, idempotencyKey: UUID) {
        commonHeaders()
        contentType(ContentType.Application.Json)
        token.useString { bearerAuth(it) }
        header(IDEMPOTENCY_KEY_HEADER, idempotencyKey.toString())
    }

    private fun canonicalUuid(value: String): UUID = UUID.fromString(value).also {
        require(it.toString() == value) { "ID must be canonical" }
    }

    companion object {
        val PROTOCOL_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
            encodeDefaults = true
        }

        fun defaultHttpClient(
            connectTimeoutMillis: Long = 5_000,
            requestTimeoutMillis: Long = 10_000,
        ): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) { json(PROTOCOL_JSON) }
            install(HttpTimeout) {
                this.connectTimeoutMillis = connectTimeoutMillis
                this.requestTimeoutMillis = requestTimeoutMillis
                socketTimeoutMillis = requestTimeoutMillis
            }
        }
    }
}

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
