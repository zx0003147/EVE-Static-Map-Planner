package dev.evestaticmapplanner.control.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

enum class LocalControlClientErrorCode {
    APP_DISCONNECTED,
    SESSION_CHANGED,
    NOT_FOUND,
    OBJECT_NOT_FOUND,
    AMBIGUOUS_SYSTEM,
    INVALID_ARGUMENT,
    INVALID_MARKER_DATA,
    CAPABILITY_DENIED,
    MARKER_ALREADY_EXISTS,
    SYSTEM_NOT_FOUND,
    MISSION_NOT_FOUND,
    MISSION_LIMIT_EXCEEDED,
    ROUTE_NOT_FOUND,
    APP_NOT_READY,
    DATABASE_UNAVAILABLE,
    RATE_LIMITED,
    IDEMPOTENCY_CONFLICT,
    TIMEOUT,
    INTERNAL_ERROR,
}

data class LocalControlClientError(
    val code: LocalControlClientErrorCode,
    val message: String,
)

sealed interface LocalControlClientResult {
    data class Success(
        val value: JsonElement,
        val missionRevision: Long?,
    ) : LocalControlClientResult

    data class Failure(val error: LocalControlClientError) : LocalControlClientResult
}

class LocalControlClient internal constructor(
    private val discoveryReader: SecureLocalControlDiscoveryReader,
    private val httpClient: HttpClient,
    private val newOpaqueId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val json = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
        ignoreUnknownKeys = false
    }
    private var cachedConnection: VerifiedConnection? = null
    private var closed = false

    constructor() : this(
        SecureLocalControlDiscoveryReader(productionDiscoveryRoot()),
        newDirectLocalControlHttpClient(),
    )

    suspend fun searchSystem(query: String): LocalControlClientResult = query(LocalControlOperation.SEARCH_SYSTEM) { requestId ->
        buildJsonObject {
            put("requestId", requestId)
            put("query", query)
        }
    }

    suspend fun getSystemInfo(systemId: Int): LocalControlClientResult = query(LocalControlOperation.SYSTEM_INFO) { requestId ->
        buildJsonObject {
            put("requestId", requestId)
            put("systemId", systemId)
        }
    }

    suspend fun getSystemMarkers(systemId: Int): LocalControlClientResult =
        query(LocalControlOperation.SYSTEM_MARKERS) { requestId ->
            buildJsonObject {
                put("requestId", requestId)
                put("systemId", systemId)
            }
        }

    suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): LocalControlClientResult = query(LocalControlOperation.NORMAL_ROUTE) { requestId ->
        buildJsonObject {
            put("requestId", requestId)
            put("startSystemId", startSystemId)
            put("destinationSystemId", destinationSystemId)
            put("useAnsiblex", useAnsiblex)
        }
    }

    suspend fun calculateCapitalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): LocalControlClientResult = query(LocalControlOperation.CAPITAL_ROUTE) { requestId ->
        buildJsonObject {
            put("requestId", requestId)
            put("startSystemId", startSystemId)
            put("destinationSystemId", destinationSystemId)
            put("effectiveRangeLy", effectiveRangeLy)
        }
    }

    suspend fun getActiveMissions(): LocalControlClientResult = query(LocalControlOperation.ACTIVE_MISSIONS) { requestId ->
        buildJsonObject { put("requestId", requestId) }
    }

    suspend fun getMission(missionId: String): LocalControlClientResult = query(LocalControlOperation.MISSION) { requestId ->
        buildJsonObject {
            put("requestId", requestId)
            put("missionId", missionId)
        }
    }

    suspend fun beginMission(title: String): LocalControlClientResult = mutation(LocalControlOperation.BEGIN_MISSION) { ids ->
        ids.body { put("title", title) }
    }

    suspend fun createSavedMarker(
        systemId: Int,
        name: String?,
        notes: String?,
        color: String,
        tags: List<String> = emptyList(),
    ): LocalControlClientResult = mutation(LocalControlOperation.CREATE_SAVED_MARKER) { ids ->
        ids.body {
            put("systemId", systemId)
            if (name != null) put("name", name)
            if (notes != null) put("notes", notes)
            put("color", color)
            put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
        }
    }

    suspend fun focusSystem(systemId: Int): LocalControlClientResult = mutation(LocalControlOperation.FOCUS_SYSTEM) { ids ->
        ids.body { put("systemId", systemId) }
    }

    suspend fun showNormalRoute(
        missionId: String,
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): LocalControlClientResult = mutation(LocalControlOperation.SHOW_NORMAL_ROUTE) { ids ->
        ids.body {
            put("missionId", missionId)
            put("startSystemId", startSystemId)
            put("destinationSystemId", destinationSystemId)
            put("useAnsiblex", useAnsiblex)
        }
    }

    suspend fun showCapitalRoute(
        missionId: String,
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): LocalControlClientResult = mutation(LocalControlOperation.SHOW_CAPITAL_ROUTE) { ids ->
        ids.body {
            put("missionId", missionId)
            put("startSystemId", startSystemId)
            put("destinationSystemId", destinationSystemId)
            put("effectiveRangeLy", effectiveRangeLy)
        }
    }

    suspend fun removeMissionRoute(missionId: String, routeId: String): LocalControlClientResult =
        mutation(LocalControlOperation.REMOVE_MISSION_ROUTE) { ids ->
            ids.body {
                put("missionId", missionId)
                put("routeId", routeId)
            }
        }

    suspend fun clearMissionRoutes(missionId: String): LocalControlClientResult =
        mutationWithMission(LocalControlOperation.CLEAR_MISSION_ROUTES, missionId)

    suspend fun showJumpRange(
        missionId: String,
        originSystemId: Int,
        effectiveRangeLy: Double,
        label: String?,
    ): LocalControlClientResult = mutation(LocalControlOperation.SHOW_JUMP_RANGE) { ids ->
        ids.body {
            put("missionId", missionId)
            put("originSystemId", originSystemId)
            put("effectiveRangeLy", effectiveRangeLy)
            if (label != null) put("label", label)
        }
    }

    suspend fun removeJumpRange(missionId: String, jumpRangeId: String): LocalControlClientResult =
        mutation(LocalControlOperation.REMOVE_JUMP_RANGE) { ids ->
            ids.body {
                put("missionId", missionId)
                put("jumpRangeId", jumpRangeId)
            }
        }

    suspend fun clearMissionJumpRanges(missionId: String): LocalControlClientResult =
        mutationWithMission(LocalControlOperation.CLEAR_MISSION_JUMP_RANGES, missionId)

    suspend fun addMissionMarker(
        missionId: String,
        systemId: Int,
        role: String,
        label: String?,
        notes: String?,
        colorOverride: String?,
    ): LocalControlClientResult = mutation(LocalControlOperation.ADD_MISSION_MARKER) { ids ->
        ids.body {
            put("missionId", missionId)
            put("systemId", systemId)
            put("role", role)
            if (label != null) put("label", label)
            if (notes != null) put("notes", notes)
            if (colorOverride != null) put("colorOverride", colorOverride)
        }
    }

    suspend fun removeMissionMarker(missionId: String, markerId: String): LocalControlClientResult =
        mutation(LocalControlOperation.REMOVE_MISSION_MARKER) { ids ->
            ids.body {
                put("missionId", missionId)
                put("markerId", markerId)
            }
        }

    suspend fun clearMissionMarkers(missionId: String): LocalControlClientResult =
        mutationWithMission(LocalControlOperation.CLEAR_MISSION_MARKERS, missionId)

    suspend fun fitMission(missionId: String): LocalControlClientResult =
        mutationWithMission(LocalControlOperation.FIT_MISSION, missionId)

    suspend fun clearMission(missionId: String): LocalControlClientResult =
        mutationWithMission(LocalControlOperation.CLEAR_MISSION, missionId)

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            cachedConnection?.snapshot?.credentials?.invalidate()
            cachedConnection = null
        }
        runCatching { (httpClient as? AutoCloseable)?.close() }
    }

    private suspend fun mutationWithMission(operation: LocalControlOperation, missionId: String): LocalControlClientResult =
        mutation(operation) { ids -> ids.body { put("missionId", missionId) } }

    private suspend fun query(
        operation: LocalControlOperation,
        body: (String) -> JsonObject,
    ): LocalControlClientResult = withContext(Dispatchers.IO) {
        val requestId = newOpaqueId()
        val connection = connection() ?: return@withContext disconnected()
        executeKnown(connection, operation, body(requestId), requestId)
    }

    private suspend fun mutation(
        operation: LocalControlOperation,
        body: (InvocationIds) -> JsonObject,
    ): LocalControlClientResult = withContext(Dispatchers.IO) {
        check(operation.mutation)
        val ids = InvocationIds(newOpaqueId(), newOpaqueId())
        val requestBody = body(ids)
        val firstConnection = connection() ?: return@withContext disconnected()
        val first = executeKnown(firstConnection, operation, requestBody, ids.requestId)
        if (!first.isUncertain()) return@withContext first

        val current = readSnapshot() ?: return@withContext disconnected()
        if (current.instanceId != firstConnection.snapshot.instanceId) {
            current.credentials.invalidate()
            invalidateCachedConnection()
            return@withContext sessionChanged()
        }
        current.credentials.invalidate()
        executeKnown(firstConnection, operation, requestBody, ids.requestId)
    }

    private fun InvocationIds.body(additional: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("requestId", requestId)
            put("idempotencyKey", idempotencyKey)
            additional()
        }

    private fun connection(): VerifiedConnection? {
        val snapshot = readSnapshot() ?: return null
        synchronized(lifecycleLock) {
            if (closed) {
                snapshot.credentials.invalidate()
                return null
            }
            val cached = cachedConnection
            if (cached != null && cached.matches(snapshot)) {
                snapshot.credentials.invalidate()
                return cached
            }
            cached?.snapshot?.credentials?.invalidate()
            cachedConnection = null
        }
        val candidate = VerifiedConnection(snapshot)
        val handshakeId = newOpaqueId()
        val handshake = try {
            executeHttp(
                candidate,
                LocalControlOperation.HANDSHAKE,
                buildJsonObject { put("requestId", handshakeId) },
                handshakeId,
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
        if (handshake !is LocalControlClientResult.Success || !validHandshake(handshake.value, snapshot)) {
            snapshot.credentials.invalidate()
            return null
        }
        synchronized(lifecycleLock) {
            if (closed) {
                snapshot.credentials.invalidate()
                return null
            }
            cachedConnection = candidate
        }
        return candidate
    }

    private fun executeKnown(
        connection: VerifiedConnection,
        operation: LocalControlOperation,
        body: JsonObject,
        requestId: String,
    ): LocalControlClientResult = try {
        executeHttp(connection, operation, body, requestId)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: IOException) {
        uncertain()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        uncertain()
    } catch (_: Exception) {
        internalError()
    }

    private fun executeHttp(
        connection: VerifiedConnection,
        operation: LocalControlOperation,
        body: JsonObject,
        requestId: String,
    ): LocalControlClientResult {
        val uri = URI("http", null, "127.0.0.1", connection.snapshot.port, operation.path, null, null)
        val request = HttpRequest.newBuilder(uri)
            .timeout(clientTimeout(operation))
            .header("Authorization", connection.snapshot.credentials.authorizationHeaderValue())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toString().toByteArray(Charsets.UTF_8)))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            val bytes = readBoundedResponse(input, response.headers().firstValueAsLong("Content-Length").orElse(-1L))
            if (response.statusCode() in 300..399) return internalError()
            val contentType = response.headers().allValues("Content-Type")
            if (contentType.size != 1 || !contentType.single().substringBefore(';').trim().equals("application/json", true)) {
                return internalError()
            }
            return parseOperationResponse(response.statusCode(), bytes, requestId)
        }
    }

    private fun readBoundedResponse(input: java.io.InputStream, declaredLength: Long): ByteArray {
        if (declaredLength > LocalControlProtocol.RESPONSE_BODY_LIMIT_BYTES) throw IOException("Response too large")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > LocalControlProtocol.RESPONSE_BODY_LIMIT_BYTES) throw IOException("Response too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseOperationResponse(status: Int, bytes: ByteArray, expectedRequestId: String): LocalControlClientResult {
        val root = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return internalError()
        val ok = (root["ok"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
            ?: return internalError()
        val requestId = (root["requestId"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: return internalError()
        if (requestId != expectedRequestId) return internalError()
        return if (ok) {
            if (status != 200 || root.keys.any { it !in SUCCESS_FIELDS } || "value" !in root) return internalError()
            val revision = when (val raw = root["missionRevision"]) {
                null -> null
                is JsonPrimitive -> raw.takeUnless(JsonPrimitive::isString)?.longOrNull ?: return internalError()
                else -> return internalError()
            }
            LocalControlClientResult.Success(root.getValue("value"), revision)
        } else {
            if (status !in 400..599 || root.keys != ERROR_FIELDS) return internalError()
            val error = root["error"] as? JsonObject ?: return internalError()
            if (error.keys != ERROR_VALUE_FIELDS) return internalError()
            val rawCode = (error["code"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: return internalError()
            failureForWireCode(rawCode)
        }
    }

    private fun validHandshake(value: JsonElement, snapshot: LocalControlDiscoverySnapshot): Boolean {
        val objectValue = value as? JsonObject ?: return false
        if (objectValue.keys != HANDSHAKE_FIELDS) return false
        val protocol = (objectValue["protocolVersion"] as? JsonPrimitive)?.intOrNull ?: return false
        val controlApi = (objectValue["controlApiVersion"] as? JsonPrimitive)?.intOrNull ?: return false
        val instance = (objectValue["instanceId"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return false
        return protocol == LocalControlProtocol.PROTOCOL_VERSION &&
            protocol == snapshot.protocolVersion &&
            controlApi == LocalControlProtocol.CONTROL_API_VERSION &&
            controlApi == snapshot.controlApiVersion &&
            instance == snapshot.instanceId
    }

    private fun readSnapshot(): LocalControlDiscoverySnapshot? = try {
        discoveryReader.read()
    } catch (_: Exception) {
        null
    }

    private fun invalidateCachedConnection() = synchronized(lifecycleLock) {
        cachedConnection?.snapshot?.credentials?.invalidate()
        cachedConnection = null
    }

    private data class InvocationIds(val requestId: String, val idempotencyKey: String)

    private data class VerifiedConnection(val snapshot: LocalControlDiscoverySnapshot) {
        fun matches(other: LocalControlDiscoverySnapshot): Boolean =
            snapshot.instanceId == other.instanceId &&
                snapshot.port == other.port &&
                snapshot.protocolVersion == other.protocolVersion &&
                snapshot.controlApiVersion == other.controlApiVersion
    }

    private companion object {
        val SUCCESS_FIELDS = setOf("requestId", "ok", "missionRevision", "value")
        val ERROR_FIELDS = setOf("requestId", "ok", "error")
        val ERROR_VALUE_FIELDS = setOf("code", "message")
        val HANDSHAKE_FIELDS = setOf("protocolVersion", "instanceId", "appVersion", "controlApiVersion", "capabilities")

        fun productionDiscoveryRoot(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            return localAppData.resolve("EVE Static Map Planner").resolve("control")
        }

        fun clientTimeout(operation: LocalControlOperation): Duration = when (operation) {
            LocalControlOperation.CAPITAL_ROUTE, LocalControlOperation.SHOW_CAPITAL_ROUTE -> Duration.ofSeconds(32)
            LocalControlOperation.NORMAL_ROUTE, LocalControlOperation.SHOW_NORMAL_ROUTE,
            LocalControlOperation.SHOW_JUMP_RANGE -> Duration.ofSeconds(17)
            else -> Duration.ofSeconds(5)
        }
    }
}

internal object DirectProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

internal fun newDirectLocalControlHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .followRedirects(HttpClient.Redirect.NEVER)
    .proxy(DirectProxySelector)
    .build()

private fun LocalControlClientResult.isUncertain(): Boolean =
    this is LocalControlClientResult.Failure && error.code == LocalControlClientErrorCode.TIMEOUT

private fun failureForWireCode(code: String): LocalControlClientResult = when (code) {
    "NOT_FOUND" -> failure(LocalControlClientErrorCode.NOT_FOUND)
    "OBJECT_NOT_FOUND" -> failure(LocalControlClientErrorCode.OBJECT_NOT_FOUND)
    "AMBIGUOUS_SYSTEM" -> failure(LocalControlClientErrorCode.AMBIGUOUS_SYSTEM)
    "INVALID_ARGUMENT" -> failure(LocalControlClientErrorCode.INVALID_ARGUMENT)
    "INVALID_MARKER_DATA" -> failure(LocalControlClientErrorCode.INVALID_MARKER_DATA)
    "CAPABILITY_DENIED" -> failure(LocalControlClientErrorCode.CAPABILITY_DENIED)
    "MARKER_ALREADY_EXISTS" -> failure(LocalControlClientErrorCode.MARKER_ALREADY_EXISTS)
    "SYSTEM_NOT_FOUND" -> failure(LocalControlClientErrorCode.SYSTEM_NOT_FOUND)
    "MISSION_NOT_FOUND" -> failure(LocalControlClientErrorCode.MISSION_NOT_FOUND)
    "MISSION_LIMIT_EXCEEDED" -> failure(LocalControlClientErrorCode.MISSION_LIMIT_EXCEEDED)
    "ROUTE_NOT_FOUND" -> failure(LocalControlClientErrorCode.ROUTE_NOT_FOUND)
    "APP_NOT_READY" -> failure(LocalControlClientErrorCode.APP_NOT_READY)
    "DATABASE_UNAVAILABLE" -> failure(LocalControlClientErrorCode.DATABASE_UNAVAILABLE)
    "RATE_LIMITED", "APP_BUSY" -> failure(LocalControlClientErrorCode.RATE_LIMITED)
    "IDEMPOTENCY_CONFLICT" -> failure(LocalControlClientErrorCode.IDEMPOTENCY_CONFLICT)
    "TIMEOUT" -> failure(LocalControlClientErrorCode.TIMEOUT)
    "UNAUTHORIZED" -> disconnected()
    else -> internalError()
}

private fun failure(code: LocalControlClientErrorCode): LocalControlClientResult.Failure =
    LocalControlClientResult.Failure(LocalControlClientError(code, safeMessage(code)))

private fun disconnected(): LocalControlClientResult.Failure = failure(LocalControlClientErrorCode.APP_DISCONNECTED)
private fun sessionChanged(): LocalControlClientResult.Failure = failure(LocalControlClientErrorCode.SESSION_CHANGED)
private fun uncertain(): LocalControlClientResult.Failure = failure(LocalControlClientErrorCode.TIMEOUT)
private fun internalError(): LocalControlClientResult.Failure = failure(LocalControlClientErrorCode.INTERNAL_ERROR)

private fun safeMessage(code: LocalControlClientErrorCode): String = when (code) {
    LocalControlClientErrorCode.APP_DISCONNECTED ->
        "EVE Static Map Planner is unavailable or AI Map Control is disabled."
    LocalControlClientErrorCode.SESSION_CHANGED -> "The AI Map Control session changed before the operation could be confirmed."
    LocalControlClientErrorCode.NOT_FOUND, LocalControlClientErrorCode.OBJECT_NOT_FOUND,
    LocalControlClientErrorCode.MISSION_NOT_FOUND -> "The requested object was not found."
    LocalControlClientErrorCode.AMBIGUOUS_SYSTEM -> "The solar system reference is ambiguous."
    LocalControlClientErrorCode.INVALID_ARGUMENT -> "The request is invalid."
    LocalControlClientErrorCode.INVALID_MARKER_DATA -> "The saved marker data is invalid."
    LocalControlClientErrorCode.CAPABILITY_DENIED -> "The operation is not allowed."
    LocalControlClientErrorCode.MARKER_ALREADY_EXISTS -> "A saved marker already exists for this solar system."
    LocalControlClientErrorCode.SYSTEM_NOT_FOUND -> "The solar system was not found."
    LocalControlClientErrorCode.MISSION_LIMIT_EXCEEDED -> "A Mission resource limit was reached."
    LocalControlClientErrorCode.ROUTE_NOT_FOUND -> "No route was found."
    LocalControlClientErrorCode.APP_NOT_READY -> "The map is not ready."
    LocalControlClientErrorCode.DATABASE_UNAVAILABLE -> "Required map data is unavailable."
    LocalControlClientErrorCode.RATE_LIMITED -> "AI Map Control is busy or rate limited."
    LocalControlClientErrorCode.IDEMPOTENCY_CONFLICT -> "The operation conflicted with an earlier request."
    LocalControlClientErrorCode.TIMEOUT -> "The operation timed out."
    LocalControlClientErrorCode.INTERNAL_ERROR -> "The AI Map Control operation failed."
}
