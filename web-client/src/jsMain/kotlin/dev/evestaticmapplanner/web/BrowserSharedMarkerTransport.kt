package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.shared.protocol.ApiErrorDto
import dev.evestaticmapplanner.shared.protocol.CreateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.ExchangeInviteRequestDto
import dev.evestaticmapplanner.shared.protocol.ExchangeInviteResponseDto
import dev.evestaticmapplanner.shared.protocol.MeResponseDto
import dev.evestaticmapplanner.shared.protocol.MetaResponseDto
import dev.evestaticmapplanner.shared.protocol.SHARED_MAP_PROTOCOL_JSON
import dev.evestaticmapplanner.shared.protocol.SharedMarkerDto
import dev.evestaticmapplanner.shared.protocol.SharedMarkerSnapshotResponseDto
import dev.evestaticmapplanner.shared.protocol.UpdateSharedMarkerRequestDto
import dev.evestaticmapplanner.shared.protocol.WorkspacesResponseDto
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement

data class BrowserHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

data class BrowserHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

fun interface BrowserHttpEngine {
    suspend fun execute(request: BrowserHttpRequest): BrowserHttpResponse
}

class FetchBrowserHttpEngine : BrowserHttpEngine {
    override suspend fun execute(request: BrowserHttpRequest): BrowserHttpResponse {
        val options = js("({})")
        options.method = request.method
        val headers = js("({})")
        request.headers.forEach { (name, value) -> headers[name] = value }
        options.headers = headers
        request.body?.let { options.body = it }
        options.credentials = "omit"
        options.cache = "no-store"
        val response = try {
            window.fetch(request.url, options).await()
        } catch (_: Throwable) {
            throw SharedMarkerTransportException(
                SharedMarkerTransportError(
                    SharedMarkerTransportErrorKind.NETWORK,
                    "The Shared Marker server could not be reached. Check HTTPS and the server CORS origin list.",
                ),
            )
        }
        val responseBody = response.text().await()
        val responseHeaders = listOf(REQUEST_ID_HEADER, "Retry-After", "Location")
            .mapNotNull { name -> response.headers.get(name)?.let { name to it } }
            .toMap()
        return BrowserHttpResponse(response.status.toInt(), responseHeaders, responseBody)
    }
}

interface SharedMarkerClient {
    suspend fun getMeta(serverOrigin: String): MetaResponseDto
    suspend fun exchangeInvite(serverOrigin: String, inviteCode: String, deviceName: String): ExchangeInviteResponseDto
    suspend fun getMe(serverOrigin: String, accessToken: String): MeResponseDto
    suspend fun getWorkspaces(serverOrigin: String, accessToken: String): WorkspacesResponseDto
    suspend fun getMarkerSnapshot(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
    ): SharedMarkerSnapshotResponseDto
    suspend fun createMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        request: CreateSharedMarkerRequestDto,
        idempotencyKey: String = browserUuid(),
    ): SharedMarkerDto
    suspend fun updateMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        markerId: String,
        request: UpdateSharedMarkerRequestDto,
        idempotencyKey: String = browserUuid(),
    ): SharedMarkerDto
    suspend fun deleteMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        markerId: String,
        expectedVersion: Long,
        idempotencyKey: String = browserUuid(),
    )
}

enum class SharedMarkerTransportErrorKind {
    NETWORK,
    AUTHENTICATION,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    PROTOCOL,
    INVALID_ARGUMENT,
    INVALID_RESPONSE,
    SERVER,
}

data class SharedMarkerTransportError(
    val kind: SharedMarkerTransportErrorKind,
    val message: String,
    val requestId: String? = null,
    val retryAfterSeconds: Long? = null,
    val currentMarker: SharedMarkerDto? = null,
)

class SharedMarkerTransportException(val error: SharedMarkerTransportError) : Exception(error.message) {
    override fun toString(): String = "SharedMarkerTransportException(${error.kind}: ${error.message})"
}

class BrowserSharedMarkerTransport(
    private val engine: BrowserHttpEngine = FetchBrowserHttpEngine(),
    private val requestIdFactory: () -> String = ::browserUuid,
) : SharedMarkerClient {
    override suspend fun getMeta(serverOrigin: String): MetaResponseDto =
        jsonRequest("GET", endpoint(serverOrigin, "/api/v1/meta"))

    override suspend fun exchangeInvite(
        serverOrigin: String,
        inviteCode: String,
        deviceName: String,
    ): ExchangeInviteResponseDto = jsonRequest(
        "POST",
        endpoint(serverOrigin, "/api/v1/auth/exchange-invite"),
        body = SHARED_MAP_PROTOCOL_JSON.encodeToString(ExchangeInviteRequestDto(inviteCode, deviceName)),
    )

    override suspend fun getMe(serverOrigin: String, accessToken: String): MeResponseDto = jsonRequest(
        "GET",
        endpoint(serverOrigin, "/api/v1/me"),
        accessToken = accessToken,
    )

    override suspend fun getWorkspaces(serverOrigin: String, accessToken: String): WorkspacesResponseDto = jsonRequest(
        "GET",
        endpoint(serverOrigin, "/api/v1/workspaces"),
        accessToken = accessToken,
    )

    override suspend fun getMarkerSnapshot(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
    ): SharedMarkerSnapshotResponseDto = jsonRequest(
        "GET",
        endpoint(serverOrigin, "/api/v1/workspaces/${canonicalUuid(workspaceId)}/markers"),
        accessToken = accessToken,
    )

    override suspend fun createMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        request: CreateSharedMarkerRequestDto,
        idempotencyKey: String,
    ): SharedMarkerDto = jsonRequest(
        "POST",
        endpoint(serverOrigin, "/api/v1/workspaces/${canonicalUuid(workspaceId)}/markers"),
        accessToken = accessToken,
        idempotencyKey = canonicalUuid(idempotencyKey),
        body = SHARED_MAP_PROTOCOL_JSON.encodeToString(request),
    )

    override suspend fun updateMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        markerId: String,
        request: UpdateSharedMarkerRequestDto,
        idempotencyKey: String,
    ): SharedMarkerDto = jsonRequest(
        "PATCH",
        endpoint(
            serverOrigin,
            "/api/v1/workspaces/${canonicalUuid(workspaceId)}/markers/${canonicalUuid(markerId)}",
        ),
        accessToken = accessToken,
        idempotencyKey = canonicalUuid(idempotencyKey),
        body = SHARED_MAP_PROTOCOL_JSON.encodeToString(request),
    )

    override suspend fun deleteMarker(
        serverOrigin: String,
        accessToken: String,
        workspaceId: String,
        markerId: String,
        expectedVersion: Long,
        idempotencyKey: String,
    ) {
        require(expectedVersion > 0) { "Expected version must be positive" }
        unitRequest(
            "DELETE",
            endpoint(
                serverOrigin,
                "/api/v1/workspaces/${canonicalUuid(workspaceId)}/markers/${canonicalUuid(markerId)}" +
                    "?expectedVersion=$expectedVersion",
            ),
            accessToken = accessToken,
            idempotencyKey = canonicalUuid(idempotencyKey),
        )
    }

    private suspend inline fun <reified T> jsonRequest(
        method: String,
        url: String,
        accessToken: String? = null,
        idempotencyKey: String? = null,
        body: String? = null,
    ): T {
        val response = execute(method, url, accessToken, idempotencyKey, body)
        if (response.status !in 200..299) throw response.toException()
        return try {
            SHARED_MAP_PROTOCOL_JSON.decodeFromString<T>(response.body)
        } catch (_: SerializationException) {
            throw invalidResponse(response)
        } catch (_: IllegalArgumentException) {
            throw invalidResponse(response)
        }
    }

    private suspend fun unitRequest(
        method: String,
        url: String,
        accessToken: String? = null,
        idempotencyKey: String? = null,
    ) {
        val response = execute(method, url, accessToken, idempotencyKey, null)
        if (response.status !in 200..299) throw response.toException()
    }

    private suspend fun execute(
        method: String,
        url: String,
        accessToken: String?,
        idempotencyKey: String?,
        body: String?,
    ): BrowserHttpResponse {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            REQUEST_ID_HEADER to canonicalUuid(requestIdFactory()),
        )
        accessToken?.let { headers["Authorization"] = "Bearer $it" }
        idempotencyKey?.let { headers[IDEMPOTENCY_KEY_HEADER] = it }
        if (body != null) headers["Content-Type"] = "application/json"
        return try {
            engine.execute(BrowserHttpRequest(method, url, headers, body))
        } catch (error: SharedMarkerTransportException) {
            throw error
        } catch (_: Throwable) {
            throw SharedMarkerTransportException(
                SharedMarkerTransportError(
                    SharedMarkerTransportErrorKind.NETWORK,
                    "The Shared Marker server could not be reached. Check HTTPS and the server CORS origin list.",
                ),
            )
        }
    }

    private fun BrowserHttpResponse.toException(): SharedMarkerTransportException {
        val apiError = runCatching { SHARED_MAP_PROTOCOL_JSON.decodeFromString<ApiErrorDto>(body) }.getOrNull()
        val requestId = apiError?.requestId ?: headers[REQUEST_ID_HEADER]
        val safeMessage = apiError?.message?.takeIf(String::isNotBlank) ?: "The Shared Marker request failed."
        val currentMarker = apiError?.details?.get("currentMarker")?.let { element ->
            runCatching { SHARED_MAP_PROTOCOL_JSON.decodeFromJsonElement<SharedMarkerDto>(element) }.getOrNull()
        }
        val kind = when (apiError?.code) {
            "MARKER_ALREADY_EXISTS", "MARKER_VERSION_CONFLICT" -> SharedMarkerTransportErrorKind.CONFLICT
            "INVALID_ARGUMENT", "PAYLOAD_TOO_LARGE" -> SharedMarkerTransportErrorKind.INVALID_ARGUMENT
            else -> when (status) {
                401 -> SharedMarkerTransportErrorKind.AUTHENTICATION
                403 -> SharedMarkerTransportErrorKind.FORBIDDEN
                404 -> SharedMarkerTransportErrorKind.NOT_FOUND
                409 -> SharedMarkerTransportErrorKind.CONFLICT
                426 -> SharedMarkerTransportErrorKind.PROTOCOL
                429 -> SharedMarkerTransportErrorKind.RATE_LIMITED
                in 500..599 -> SharedMarkerTransportErrorKind.SERVER
                else -> SharedMarkerTransportErrorKind.INVALID_RESPONSE
            }
        }
        return SharedMarkerTransportException(
            SharedMarkerTransportError(
                kind = kind,
                message = safeMessage,
                requestId = requestId,
                retryAfterSeconds = headers["Retry-After"]?.toLongOrNull(),
                currentMarker = currentMarker,
            ),
        )
    }

    private fun invalidResponse(response: BrowserHttpResponse) = SharedMarkerTransportException(
        SharedMarkerTransportError(
            SharedMarkerTransportErrorKind.INVALID_RESPONSE,
            "The Shared Marker server returned an invalid response.",
            response.headers[REQUEST_ID_HEADER],
        ),
    )
}

fun normalizeSharedServerOrigin(raw: String): String {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty() && trimmed.length <= 2_048) { "Server URL is invalid." }
    val match = ORIGIN_PATTERN.matchEntire(trimmed) ?: throw IllegalArgumentException(
        "Server URL must contain only an HTTP(S) origin.",
    )
    val scheme = match.groupValues[1].lowercase()
    val authority = match.groupValues[2]
    require('@' !in authority && authority.none(Char::isWhitespace)) { "Server URL host is invalid." }
    val host: String
    val port: Int?
    if (authority.startsWith("[")) {
        val end = authority.indexOf(']')
        require(end > 1) { "Server URL host is invalid." }
        host = authority.substring(0, end + 1).lowercase()
        val suffix = authority.substring(end + 1)
        port = if (suffix.isEmpty()) null else {
            require(suffix.startsWith(':')) { "Server URL port is invalid." }
            suffix.substring(1).toIntOrNull()
        }
    } else {
        val separator = authority.lastIndexOf(':')
        val hasPort = separator > 0 && authority.indexOf(':') == separator
        host = (if (hasPort) authority.substring(0, separator) else authority).lowercase()
        port = if (hasPort) authority.substring(separator + 1).toIntOrNull() else null
    }
    require(host.isNotBlank() && (host.startsWith('[') || HOST_PATTERN.matches(host))) { "Server URL host is invalid." }
    require(port == null || port in 1..65_535) { "Server URL port is invalid." }
    require(scheme == "https" || host == "localhost" || host == "127.0.0.1") {
        "Use HTTPS for remote Shared Marker servers; HTTP is allowed only for localhost."
    }
    val canonicalPort = when {
        port == null -> ""
        scheme == "https" && port == 443 -> ""
        scheme == "http" && port == 80 -> ""
        else -> ":$port"
    }
    return "$scheme://$host$canonicalPort"
}

private fun endpoint(origin: String, path: String): String {
    require(path.startsWith('/') && !path.startsWith("//")) { "Endpoint path must be absolute." }
    return normalizeSharedServerOrigin(origin) + path
}

internal fun canonicalUuid(value: String): String {
    require(UUID_PATTERN.matches(value)) { "ID must be a canonical UUID." }
    return value
}

internal fun browserUuid(): String {
    val crypto = window.asDynamic().crypto
    val value = crypto?.randomUUID?.call(crypto) as? String
    return value?.let(::canonicalUuid)
        ?: throw SharedMarkerTransportException(
            SharedMarkerTransportError(
                SharedMarkerTransportErrorKind.INVALID_RESPONSE,
                "This browser does not provide secure UUID generation.",
            ),
        )
}

private val ORIGIN_PATTERN = Regex("^(https?)://([^/?#]+)(/?)$", RegexOption.IGNORE_CASE)
private val HOST_PATTERN = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
