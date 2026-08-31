package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.Locale

object LocalControlProtocol {
    const val PROTOCOL_VERSION = 1
    const val CONTROL_API_VERSION = 2
    val SAVED_MARKER_TAGS: List<String> = SavedMarkerChildType.supportedTypes.map {
        it.key.uppercase(Locale.ROOT)
    }
    const val REQUEST_BODY_LIMIT_BYTES = 64 * 1024
    const val RESPONSE_BODY_LIMIT_BYTES = 1024 * 1024
    const val HTTP_WORKER_COUNT = 4
    const val HTTP_QUEUE_CAPACITY = 32
    const val HTTP_BUSY_RESPONDER_COUNT = 1
    const val HTTP_BUSY_QUEUE_CAPACITY = 32

    val loopbackAddress: InetAddress
        get() = InetAddress.getByName("127.0.0.1")
}

data class LocalControlTimeouts(
    val query: Duration = Duration.ofSeconds(3),
    val routeOrJump: Duration = Duration.ofSeconds(15),
    val capitalRoute: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!query.isNegative && !query.isZero)
        require(!routeOrJump.isNegative && !routeOrJump.isZero)
        require(!capitalRoute.isNegative && !capitalRoute.isZero)
    }
}

enum class LocalControlServerState {
    STOPPED,
    RUNNING,
}

data class LocalControlSessionMetadata(
    val protocolVersion: Int,
    val instanceId: String,
    val appVersion: String,
    val controlApiVersion: Int,
    val boundAddress: InetAddress,
    val port: Int,
)

enum class LocalControlOperation(
    val path: String,
    val serviceMethod: String?,
    val mutation: Boolean,
    internal val timeoutKind: TimeoutKind,
) {
    HANDSHAKE("/v1/handshake", null, false, TimeoutKind.QUERY),
    SEARCH_SYSTEM("/v1/query/search-system", "searchSystems", false, TimeoutKind.QUERY),
    SYSTEM_INFO("/v1/query/system-info", "getSystemInfo", false, TimeoutKind.QUERY),
    SYSTEM_MARKERS("/v1/query/system-markers", "getSystemMarkers", false, TimeoutKind.QUERY),
    NORMAL_ROUTE("/v1/query/normal-route", "calculateNormalRoute", false, TimeoutKind.ROUTE_OR_JUMP),
    LIST_WORMHOLES("/v1/query/wormholes", "listWormholes", false, TimeoutKind.QUERY),
    CAPITAL_ROUTE("/v1/query/capital-route", "calculateCapitalRoute", false, TimeoutKind.CAPITAL_ROUTE),
    LIST_VIEWS("/v1/query/views", "listViews", false, TimeoutKind.QUERY),
    CURRENT_VIEW("/v1/query/current-view", "getCurrentView", false, TimeoutKind.QUERY),
    ACTIVE_MISSIONS("/v1/query/active-missions", "getActiveMissions", false, TimeoutKind.QUERY),
    MISSION("/v1/query/mission", "getMission", false, TimeoutKind.QUERY),
    BEGIN_MISSION("/v1/command/begin-mission", "beginMission", true, TimeoutKind.QUERY),
    CREATE_VIEW("/v1/command/create-view", "createView", true, TimeoutKind.QUERY),
    RENAME_VIEW("/v1/command/rename-view", "renameView", true, TimeoutKind.QUERY),
    SWITCH_VIEW("/v1/command/switch-view", "switchView", true, TimeoutKind.QUERY),
    DELETE_VIEW("/v1/command/delete-view", "deleteView", true, TimeoutKind.QUERY),
    CREATE_SAVED_MARKER("/v1/command/create-saved-marker", "createSavedMarker", true, TimeoutKind.QUERY),
    FOCUS_SYSTEM("/v1/command/focus-system", "focusSystem", true, TimeoutKind.QUERY),
    CREATE_WORMHOLE("/v1/command/create-wormhole", "createWormhole", true, TimeoutKind.QUERY),
    SHOW_NORMAL_ROUTE("/v1/command/show-normal-route", "showNormalRoute", true, TimeoutKind.ROUTE_OR_JUMP),
    SHOW_CAPITAL_ROUTE("/v1/command/show-capital-route", "showCapitalRoute", true, TimeoutKind.CAPITAL_ROUTE),
    REMOVE_MISSION_ROUTE("/v1/command/remove-mission-route", "removeMissionRoute", true, TimeoutKind.QUERY),
    CLEAR_MISSION_ROUTES("/v1/command/clear-mission-routes", "clearMissionRoutes", true, TimeoutKind.QUERY),
    SHOW_JUMP_RANGE("/v1/command/show-jump-range", "showJumpRange", true, TimeoutKind.ROUTE_OR_JUMP),
    REMOVE_JUMP_RANGE("/v1/command/remove-jump-range", "removeJumpRange", true, TimeoutKind.QUERY),
    CLEAR_MISSION_JUMP_RANGES(
        "/v1/command/clear-mission-jump-ranges",
        "clearMissionJumpRanges",
        true,
        TimeoutKind.QUERY,
    ),
    ADD_MISSION_MARKER("/v1/command/add-mission-marker", "addMissionMarker", true, TimeoutKind.QUERY),
    REMOVE_MISSION_MARKER("/v1/command/remove-mission-marker", "removeMissionMarker", true, TimeoutKind.QUERY),
    CLEAR_MISSION_MARKERS("/v1/command/clear-mission-markers", "clearMissionMarkers", true, TimeoutKind.QUERY),
    FIT_MISSION("/v1/command/fit-mission", "fitMission", true, TimeoutKind.QUERY),
    CLEAR_MISSION("/v1/command/clear-mission", "clearMission", true, TimeoutKind.QUERY),
    ;

    internal fun timeout(config: LocalControlTimeouts): Duration = when (timeoutKind) {
        TimeoutKind.QUERY -> config.query
        TimeoutKind.ROUTE_OR_JUMP -> config.routeOrJump
        TimeoutKind.CAPITAL_ROUTE -> config.capitalRoute
    }

    companion object {
        val byPath: Map<String, LocalControlOperation> = entries.associateBy(LocalControlOperation::path)
        val allowedPaths: Set<String> = byPath.keys
    }
}

internal enum class TimeoutKind {
    QUERY,
    ROUTE_OR_JUMP,
    CAPITAL_ROUTE,
}

data class LocalControlAuditEvent(
    val timestamp: Instant,
    val requestId: String?,
    val operation: String,
    val missionId: String?,
    val httpStatus: Int,
    val resultCode: String,
    val durationMillis: Long,
    val responseDelivered: Boolean,
)

fun interface LocalControlAuditSink {
    fun record(event: LocalControlAuditEvent)
}

internal object NoOpLocalControlAuditSink : LocalControlAuditSink {
    override fun record(event: LocalControlAuditEvent) = Unit
}
