package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.AddMissionMarkerCommand
import dev.evestaticmapplanner.control.AnyRouteDto
import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.CalculateCapitalRouteRequest
import dev.evestaticmapplanner.control.CalculateNormalRouteRequest
import dev.evestaticmapplanner.control.CapitalRouteDto
import dev.evestaticmapplanner.control.ClearMissionCommand
import dev.evestaticmapplanner.control.ClearMissionJumpRangesCommand
import dev.evestaticmapplanner.control.ClearMissionMarkersCommand
import dev.evestaticmapplanner.control.ClearMissionRoutesCommand
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CreateSavedMarkerCommand
import dev.evestaticmapplanner.control.CreateSavedMarkerReceipt
import dev.evestaticmapplanner.control.CreateWormholeCommand
import dev.evestaticmapplanner.control.CreateWormholeReceipt
import dev.evestaticmapplanner.control.CreateViewCommand
import dev.evestaticmapplanner.control.DeleteViewCommand
import dev.evestaticmapplanner.control.FitMissionCommand
import dev.evestaticmapplanner.control.FocusSystemCommand
import dev.evestaticmapplanner.control.GetActiveMissionsRequest
import dev.evestaticmapplanner.control.GetCurrentViewRequest
import dev.evestaticmapplanner.control.GetMissionRequest
import dev.evestaticmapplanner.control.ListEveNavigationTargetsRequest
import dev.evestaticmapplanner.control.EveNavigationTargetDto
import dev.evestaticmapplanner.control.SendMissionNavigationReceipt
import dev.evestaticmapplanner.control.SendMissionNavigationToEveCommand
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.GetSystemMarkersRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.ListViewsRequest
import dev.evestaticmapplanner.control.ListWormholesRequest
import dev.evestaticmapplanner.control.MissionJumpRangeReceipt
import dev.evestaticmapplanner.control.MissionMarkerReceipt
import dev.evestaticmapplanner.control.MissionMutationReceipt
import dev.evestaticmapplanner.control.MissionRouteReceipt
import dev.evestaticmapplanner.control.MissionSummaryDto
import dev.evestaticmapplanner.control.NormalRouteDto
import dev.evestaticmapplanner.control.PlanningViewDto
import dev.evestaticmapplanner.control.RemoveJumpRangeCommand
import dev.evestaticmapplanner.control.RemoveMissionMarkerCommand
import dev.evestaticmapplanner.control.RemoveMissionRouteCommand
import dev.evestaticmapplanner.control.RenameViewCommand
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.ShowCapitalRouteCommand
import dev.evestaticmapplanner.control.ShowJumpRangeCommand
import dev.evestaticmapplanner.control.ShowNormalRouteCommand
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemMarkersDto
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.WormholeConnectionDto
import dev.evestaticmapplanner.control.SwitchViewCommand
import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionJumpRangeId
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.control.mission.MissionRouteId
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class OperationResponse(
    val json: JsonObject,
    val httpStatus: Int,
    val requestId: String?,
    val missionId: String?,
    val resultCode: String,
)

internal class WireRequestFailure(
    val httpStatus: Int,
    val code: String,
    val safeMessage: String,
) : RuntimeException()

internal class LocalControlJsonCodec {
    private val json = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
        ignoreUnknownKeys = false
    }

    fun parseRequest(bytes: ByteArray): JsonObject = try {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        json.parseToJsonElement(text) as? JsonObject ?: invalid()
    } catch (failure: WireRequestFailure) {
        throw failure
    } catch (_: Exception) {
        invalid()
    }

    fun encode(element: JsonElement): ByteArray = element.toString().toByteArray(Charsets.UTF_8)

    suspend fun execute(
        operation: LocalControlOperation,
        request: JsonObject,
        service: MapControlService,
        session: LocalControlSessionMetadata,
    ): OperationResponse = when (operation) {
        LocalControlOperation.HANDSHAKE -> handshake(request, session)
        LocalControlOperation.SEARCH_SYSTEM -> {
            request.requireFields(setOf("requestId", "query", "limit"), setOf("requestId", "query"))
            controlResponse(
                service.searchSystems(
                    SearchSystemsRequest(
                        request.requestId(),
                        request.string("query"),
                        request.optionalInt("limit") ?: dev.evestaticmapplanner.control.ControlLimits.MAX_SEARCH_RESULTS,
                    ),
                ),
                valueEncoder = { list -> JsonArray(list.map(::systemSummaryJson)) },
            )
        }
        LocalControlOperation.SYSTEM_INFO -> {
            request.requireFields(setOf("requestId", "systemId"))
            controlResponse(service.getSystemInfo(GetSystemInfoRequest(request.requestId(), request.int("systemId"))), ::systemInfoJson)
        }
        LocalControlOperation.SYSTEM_MARKERS -> {
            request.requireFields(setOf("requestId", "systemId"))
            controlResponse(
                service.getSystemMarkers(GetSystemMarkersRequest(request.requestId(), request.int("systemId"))),
                ::systemMarkersJson,
            )
        }
        LocalControlOperation.NORMAL_ROUTE -> {
            request.requireFields(
                setOf(
                    "requestId", "startSystemId", "waypointSystemIds", "destinationSystemId", "useAnsiblex",
                    "useWormholes",
                ),
                setOf("requestId", "startSystemId", "useAnsiblex"),
            )
            controlResponse(
                service.calculateNormalRoute(
                    CalculateNormalRouteRequest(
                        request.requestId(),
                        request.int("startSystemId"),
                        request.optionalInt("destinationSystemId"),
                        request.boolean("useAnsiblex"),
                        request.optionalBoolean("useWormholes") ?: false,
                        request.optionalIntArray("waypointSystemIds"),
                    ),
                ),
                ::normalRouteJson,
            )
        }
        LocalControlOperation.LIST_WORMHOLES -> {
            request.requireFields(setOf("requestId"))
            controlResponse(
                service.listWormholes(ListWormholesRequest(request.requestId())),
                valueEncoder = { list -> JsonArray(list.map(::wormholeConnectionJson)) },
            )
        }
        LocalControlOperation.CAPITAL_ROUTE -> {
            request.requireFields(
                setOf("requestId", "startSystemId", "waypointSystemIds", "destinationSystemId", "effectiveRangeLy"),
                setOf("requestId", "startSystemId", "effectiveRangeLy"),
            )
            controlResponse(
                service.calculateCapitalRoute(
                    CalculateCapitalRouteRequest(
                        request.requestId(),
                        request.int("startSystemId"),
                        request.optionalInt("destinationSystemId"),
                        request.double("effectiveRangeLy"),
                        request.optionalIntArray("waypointSystemIds"),
                    ),
                ),
                ::capitalRouteJson,
            )
        }
        LocalControlOperation.LIST_VIEWS -> {
            request.requireFields(setOf("requestId"))
            controlResponse(
                service.listViews(ListViewsRequest(request.requestId())),
                valueEncoder = { list -> JsonArray(list.map(::planningViewJson)) },
            )
        }
        LocalControlOperation.CURRENT_VIEW -> {
            request.requireFields(setOf("requestId"))
            controlResponse(service.getCurrentView(GetCurrentViewRequest(request.requestId())), ::planningViewJson)
        }
        LocalControlOperation.ACTIVE_MISSIONS -> {
            request.requireFields(setOf("requestId", "viewId"), setOf("requestId"))
            controlResponse(
                service.getActiveMissions(GetActiveMissionsRequest(request.requestId(), request.optionalString("viewId"))),
                valueEncoder = { list -> JsonArray(list.map(::missionSummaryJson)) },
            )
        }
        LocalControlOperation.MISSION -> {
            request.requireFields(setOf("requestId", "missionId"))
            controlResponse(
                service.getMission(GetMissionRequest(request.requestId(), request.missionId())),
                ::missionJson,
                request.missionIdValue(),
            )
        }
        LocalControlOperation.BEGIN_MISSION -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "title", "viewId"), setOf("requestId", "idempotencyKey", "title"))
            controlResponse(
                service.beginMission(BeginMissionCommand(request.requestId(), request.idempotencyKey(), request.string("title"), request.optionalString("viewId"))),
                ::missionSummaryJson,
            )
        }
        LocalControlOperation.CREATE_VIEW -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "label"), setOf("requestId", "idempotencyKey"))
            controlResponse(
                service.createView(CreateViewCommand(request.requestId(), request.idempotencyKey(), request.optionalString("label"))),
                ::planningViewJson,
            )
        }
        LocalControlOperation.RENAME_VIEW -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "viewId", "label"))
            controlResponse(
                service.renameView(RenameViewCommand(request.requestId(), request.idempotencyKey(), request.string("viewId"), request.string("label"))),
                ::planningViewJson,
            )
        }
        LocalControlOperation.SWITCH_VIEW -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "viewId"))
            controlResponse(
                service.switchView(SwitchViewCommand(request.requestId(), request.idempotencyKey(), request.string("viewId"))),
                ::planningViewJson,
            )
        }
        LocalControlOperation.DELETE_VIEW -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "viewId"))
            controlResponse(
                service.deleteView(DeleteViewCommand(request.requestId(), request.idempotencyKey(), request.string("viewId"))),
                ::planningViewJson,
            )
        }
        LocalControlOperation.CREATE_SAVED_MARKER -> {
            request.requireFields(
                setOf("requestId", "idempotencyKey", "systemId", "name", "notes", "color", "tags"),
                setOf("requestId", "idempotencyKey", "systemId", "color"),
            )
            controlResponse(
                service.createSavedMarker(
                    CreateSavedMarkerCommand(
                        requestId = request.requestId(),
                        idempotencyKey = request.idempotencyKey(),
                        systemId = request.int("systemId"),
                        name = request.optionalString("name"),
                        notes = request.optionalString("notes"),
                        color = request.enum("color", MarkerColor::valueOf),
                        tags = request.optionalSavedMarkerTags("tags"),
                    ),
                ),
                ::createSavedMarkerReceiptJson,
            )
        }
        LocalControlOperation.FOCUS_SYSTEM -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "systemId"))
            controlResponse(
                service.focusSystem(FocusSystemCommand(request.requestId(), request.idempotencyKey(), request.int("systemId"))),
                ::systemSummaryJson,
            )
        }
        LocalControlOperation.EVE_NAVIGATION_TARGETS -> {
            request.requireFields(setOf("requestId"))
            controlResponse(
                service.listEveNavigationTargets(ListEveNavigationTargetsRequest(request.requestId())),
                valueEncoder = { targets -> JsonArray(targets.map(::eveNavigationTargetJson)) },
            )
        }
        LocalControlOperation.CREATE_WORMHOLE -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "fromSystemId", "toSystemId"))
            controlResponse(
                service.createWormhole(
                    CreateWormholeCommand(
                        request.requestId(),
                        request.idempotencyKey(),
                        request.int("fromSystemId"),
                        request.int("toSystemId"),
                    ),
                ),
                ::createWormholeReceiptJson,
            )
        }
        LocalControlOperation.SHOW_NORMAL_ROUTE -> {
            request.requireFields(
                setOf(
                    "requestId", "idempotencyKey", "missionId", "startSystemId", "destinationSystemId",
                    "waypointSystemIds", "useAnsiblex", "useWormholes",
                ),
                setOf("requestId", "idempotencyKey", "missionId", "startSystemId", "useAnsiblex"),
            )
            controlResponse(
                service.showNormalRoute(
                    ShowNormalRouteCommand(
                        request.requestId(), request.idempotencyKey(), request.missionId(),
                        request.int("startSystemId"), request.optionalInt("destinationSystemId"), request.boolean("useAnsiblex"),
                        request.optionalBoolean("useWormholes") ?: false,
                        request.optionalIntArray("waypointSystemIds"),
                    ),
                ),
                ::missionRouteReceiptJson,
                request.missionIdValue(),
            )
        }
        LocalControlOperation.SHOW_CAPITAL_ROUTE -> {
            request.requireFields(
                setOf(
                    "requestId", "idempotencyKey", "missionId", "startSystemId", "waypointSystemIds",
                    "destinationSystemId", "effectiveRangeLy",
                ),
                setOf("requestId", "idempotencyKey", "missionId", "startSystemId", "effectiveRangeLy"),
            )
            controlResponse(
                service.showCapitalRoute(
                    ShowCapitalRouteCommand(
                        request.requestId(), request.idempotencyKey(), request.missionId(),
                        request.int("startSystemId"), request.optionalInt("destinationSystemId"),
                        request.double("effectiveRangeLy"), request.optionalIntArray("waypointSystemIds"),
                    ),
                ),
                ::missionRouteReceiptJson,
                request.missionIdValue(),
            )
        }
        LocalControlOperation.REMOVE_MISSION_ROUTE -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "missionId", "routeId"))
            controlResponse(
                service.removeMissionRoute(
                    RemoveMissionRouteCommand(
                        request.requestId(), request.idempotencyKey(), request.missionId(),
                        MissionRouteId(request.opaqueId("routeId")),
                    ),
                ),
                ::missionMutationReceiptJson,
                request.missionIdValue(),
            )
        }
        LocalControlOperation.CLEAR_MISSION_ROUTES -> mutationWithMission(request) { requestId, key, missionId ->
            service.clearMissionRoutes(ClearMissionRoutesCommand(requestId, key, missionId))
        }
        LocalControlOperation.SHOW_JUMP_RANGE -> {
            request.requireFields(
                setOf("requestId", "idempotencyKey", "missionId", "originSystemId", "effectiveRangeLy", "label"),
                setOf("requestId", "idempotencyKey", "missionId", "originSystemId", "effectiveRangeLy"),
            )
            controlResponse(
                service.showJumpRange(
                    ShowJumpRangeCommand(
                        request.requestId(), request.idempotencyKey(), request.missionId(),
                        request.int("originSystemId"), request.double("effectiveRangeLy"), request.optionalString("label"),
                    ),
                ),
                ::missionJumpRangeReceiptJson,
                request.missionIdValue(),
            )
        }
        LocalControlOperation.REMOVE_JUMP_RANGE -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "missionId", "jumpRangeId"))
            controlResponse(
                service.removeJumpRange(
                    RemoveJumpRangeCommand(
                        request.requestId(), request.idempotencyKey(), request.missionId(),
                        MissionJumpRangeId(request.opaqueId("jumpRangeId")),
                    ),
                ),
                ::missionMutationReceiptJson,
                request.missionIdValue(),
            )
        }
        LocalControlOperation.CLEAR_MISSION_JUMP_RANGES -> mutationWithMission(request) { requestId, key, missionId ->
            service.clearMissionJumpRanges(ClearMissionJumpRangesCommand(requestId, key, missionId))
        }
        LocalControlOperation.ADD_MISSION_MARKER -> {
            request.requireFields(
                setOf("requestId", "idempotencyKey", "missionId", "systemId", "role", "label", "notes", "colorOverride"),
                setOf("requestId", "idempotencyKey", "missionId", "systemId", "role"),
            )
            controlResponse(
                service.addMissionMarker(
                    AddMissionMarkerCommand(
                        request.requestId(), request.idempotencyKey(), request.missionId(), request.int("systemId"),
                        request.enum("role", MissionMarkerRole::valueOf), request.optionalString("label"),
                        request.optionalString("notes"), request.optionalEnum("colorOverride", MarkerColor::valueOf),
                    ),
                ),
                ::missionMarkerReceiptJson,
                request.missionIdValue(),
            )
        }
        LocalControlOperation.REMOVE_MISSION_MARKER -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "missionId", "markerId"))
            controlResponse(
                service.removeMissionMarker(
                    RemoveMissionMarkerCommand(
                        request.requestId(), request.idempotencyKey(), request.missionId(),
                        MissionMarkerId(request.opaqueId("markerId")),
                    ),
                ),
                ::missionMutationReceiptJson,
                request.missionIdValue(),
            )
        }
        LocalControlOperation.CLEAR_MISSION_MARKERS -> mutationWithMission(request) { requestId, key, missionId ->
            service.clearMissionMarkers(ClearMissionMarkersCommand(requestId, key, missionId))
        }
        LocalControlOperation.FIT_MISSION -> mutationWithMission(request) { requestId, key, missionId ->
            service.fitMission(FitMissionCommand(requestId, key, missionId))
        }
        LocalControlOperation.CLEAR_MISSION -> mutationWithMission(request) { requestId, key, missionId ->
            service.clearMission(ClearMissionCommand(requestId, key, missionId))
        }
        LocalControlOperation.SEND_MISSION_NAVIGATION_TO_EVE -> {
            request.requireFields(setOf("requestId", "idempotencyKey", "missionId", "routeId", "characterId"))
            controlResponse(
                service.sendMissionNavigationToEve(
                    SendMissionNavigationToEveCommand(
                        request.requestId(),
                        request.idempotencyKey(),
                        request.missionId(),
                        MissionRouteId(request.opaqueId("routeId")),
                        request.opaqueId("characterId"),
                    ),
                ),
                ::sendMissionNavigationReceiptJson,
                request.missionIdValue(),
            )
        }
    }

    private fun handshake(request: JsonObject, session: LocalControlSessionMetadata): OperationResponse {
        request.requireFields(setOf("requestId"))
        val requestId = request.requestId()
        val value = buildJsonObject {
            put("protocolVersion", session.protocolVersion)
            put("instanceId", session.instanceId)
            put("appVersion", session.appVersion)
            put("controlApiVersion", session.controlApiVersion)
            put("capabilities", buildJsonObject {
                put("queries", buildJsonArray {
                    LocalControlOperation.entries.filter { !it.mutation && it.serviceMethod != null }.forEach { add(JsonPrimitive(it.path)) }
                })
                put("commands", buildJsonArray {
                    LocalControlOperation.entries.filter(LocalControlOperation::mutation).forEach { add(JsonPrimitive(it.path)) }
                })
            })
        }
        return successResponse(requestId, value)
    }

    private suspend fun mutationWithMission(
        request: JsonObject,
        call: suspend (String, String, MissionId) -> ControlResult<MissionMutationReceipt>,
    ): OperationResponse {
        request.requireFields(setOf("requestId", "idempotencyKey", "missionId"))
        return controlResponse(
            call(request.requestId(), request.idempotencyKey(), request.missionId()),
            ::missionMutationReceiptJson,
            request.missionIdValue(),
        )
    }

    private fun <T> controlResponse(
        result: ControlResult<T>,
        valueEncoder: (T) -> JsonElement,
        missionId: String? = null,
    ): OperationResponse = when (result) {
        is ControlResult.Success -> successResponse(
            result.requestId,
            valueEncoder(result.value),
            result.missionRevision,
            missionId,
        )
        is ControlResult.Failure -> {
            val status = httpStatus(result.error.code)
            OperationResponse(
                errorJson(result.requestId, result.error.code.name, safeMessage(result.error.code)),
                status,
                safeIdentifierOrNull(result.requestId),
                missionId,
                result.error.code.name,
            )
        }
    }

    private fun successResponse(
        requestId: String,
        value: JsonElement,
        missionRevision: Long? = null,
        missionId: String? = null,
    ) = OperationResponse(
        buildJsonObject {
            put("requestId", safeIdentifierOrNull(requestId).orEmpty())
            put("ok", true)
            if (missionRevision != null) put("missionRevision", missionRevision)
            put("value", value)
        },
        200,
        safeIdentifierOrNull(requestId),
        missionId,
        "SUCCESS",
    )

    companion object {
        fun errorJson(requestId: String?, code: String, message: String): JsonObject = buildJsonObject {
            put("requestId", requestId.orEmpty())
            put("ok", false)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }

        fun safeIdentifierOrNull(value: String?): String? = value?.takeIf { IDENTIFIER_PATTERN.matches(it) }

        private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")
    }
}

private fun JsonObject.requireFields(allowed: Set<String>, required: Set<String> = allowed) {
    if (keys.any { it !in allowed } || required.any { it !in this || this[it] is JsonNull }) invalid()
}

private fun JsonObject.requestId(): String = opaqueId("requestId")
private fun JsonObject.idempotencyKey(): String = opaqueId("idempotencyKey")
private fun JsonObject.missionId(): MissionId = MissionId(missionIdValue())
private fun JsonObject.missionIdValue(): String = opaqueId("missionId")

private fun JsonObject.opaqueId(name: String): String {
    val value = string(name)
    if (LocalControlJsonCodec.safeIdentifierOrNull(value) == null) invalid()
    return value
}

private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.content
    ?: invalid()

private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content ?: invalid()
    else -> invalid()
}

private fun JsonObject.int(name: String): Int = (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
    ?: invalid()

private fun JsonObject.optionalInt(name: String): Int? = when (val value = this[name]) {
    null -> null
    is JsonPrimitive -> value.takeUnless(JsonPrimitive::isString)?.intOrNull ?: invalid()
    else -> invalid()
}

private fun JsonObject.double(name: String): Double =
    (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull?.takeIf(Double::isFinite)
        ?: invalid()

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: invalid()

private fun JsonObject.optionalBoolean(name: String): Boolean? = when (val value = this[name]) {
    null -> null
    is JsonPrimitive -> value.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: invalid()
    else -> invalid()
}

private fun JsonObject.optionalIntArray(name: String): List<Int> = when (val value = this[name]) {
    null -> emptyList()
    is JsonArray -> value.map { item ->
        (item as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull ?: invalid()
    }
    else -> invalid()
}

private fun <T> JsonObject.enum(name: String, parse: (String) -> T): T =
    runCatching { parse(string(name)) }.getOrElse { invalid() }

private fun <T> JsonObject.optionalEnum(name: String, parse: (String) -> T): T? =
    optionalString(name)?.let { runCatching { parse(it) }.getOrElse { invalid() } }

private fun JsonObject.optionalSavedMarkerTags(name: String): List<SavedMarkerChildType> = when (val value = this[name]) {
    null -> emptyList()
    is JsonArray -> value.map { item ->
        val key = (item as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: invalid()
        SAVED_MARKER_TAGS[key] ?: invalid()
    }
    else -> invalid()
}

private val SAVED_MARKER_TAGS = LocalControlProtocol.SAVED_MARKER_TAGS.zip(SavedMarkerChildType.supportedTypes).toMap()

private fun invalid(): Nothing = throw WireRequestFailure(400, "INVALID_ARGUMENT", "The request is invalid")

private fun httpStatus(code: ControlErrorCode): Int = when (code) {
    ControlErrorCode.INVALID_ARGUMENT, ControlErrorCode.INVALID_MARKER_DATA,
    ControlErrorCode.AMBIGUOUS_SYSTEM -> 400
    ControlErrorCode.CAPABILITY_DENIED -> 403
    ControlErrorCode.NOT_FOUND, ControlErrorCode.SYSTEM_NOT_FOUND, ControlErrorCode.OBJECT_NOT_FOUND,
    ControlErrorCode.MISSION_NOT_FOUND,
    ControlErrorCode.ROUTE_NOT_FOUND -> 404
    ControlErrorCode.MARKER_ALREADY_EXISTS, ControlErrorCode.MISSION_LIMIT_EXCEEDED,
    ControlErrorCode.IDEMPOTENCY_CONFLICT -> 409
    ControlErrorCode.RATE_LIMITED -> 429
    ControlErrorCode.APP_NOT_READY, ControlErrorCode.DATABASE_UNAVAILABLE -> 503
    ControlErrorCode.TIMEOUT -> 504
    ControlErrorCode.INTERNAL_ERROR -> 500
}

private fun safeMessage(code: ControlErrorCode): String = when (code) {
    ControlErrorCode.INVALID_ARGUMENT -> "The request is invalid"
    ControlErrorCode.INVALID_MARKER_DATA -> "The saved marker data is invalid"
    ControlErrorCode.AMBIGUOUS_SYSTEM -> "The solar system reference is ambiguous"
    ControlErrorCode.CAPABILITY_DENIED -> "The operation is not allowed"
    ControlErrorCode.NOT_FOUND, ControlErrorCode.SYSTEM_NOT_FOUND, ControlErrorCode.OBJECT_NOT_FOUND,
    ControlErrorCode.MISSION_NOT_FOUND ->
        "The requested object was not found"
    ControlErrorCode.MARKER_ALREADY_EXISTS -> "A saved marker already exists for this solar system"
    ControlErrorCode.ROUTE_NOT_FOUND -> "No route was found"
    ControlErrorCode.MISSION_LIMIT_EXCEEDED -> "A Mission resource limit was reached"
    ControlErrorCode.RATE_LIMITED -> "The request rate limit was exceeded"
    ControlErrorCode.IDEMPOTENCY_CONFLICT -> "The idempotency key conflicts with an earlier request"
    ControlErrorCode.APP_NOT_READY -> "The application is not ready"
    ControlErrorCode.DATABASE_UNAVAILABLE -> "Required data is unavailable"
    ControlErrorCode.TIMEOUT -> "The operation timed out"
    ControlErrorCode.INTERNAL_ERROR -> "The control operation failed"
}

private fun systemSummaryJson(value: SystemSummaryDto) = buildJsonObject {
    put("systemId", value.systemId)
    put("name", value.name)
    put("regionId", value.regionId)
    put("constellationId", value.constellationId)
    put("securityStatus", value.securityStatus)
}

private fun systemInfoJson(value: SystemInfoDto) = buildJsonObject {
    put("system", systemSummaryJson(value.system))
    put("regionName", value.regionName)
    put("constellationName", value.constellationName)
    put("x", value.x)
    put("y", value.y)
    put("z", value.z)
    put("stargateCount", value.stargateCount)
}

private fun systemMarkersJson(value: SystemMarkersDto) = buildJsonObject {
    put("systemId", value.systemId)
    put("savedMarker", value.savedMarker?.let(::savedMarkerJson) ?: JsonNull)
    put("missionMarkers", buildJsonArray {
        value.missionMarkers.forEach { marker ->
            add(buildJsonObject {
                put("missionId", marker.missionId.value)
                put("markerId", marker.markerId.value)
                put("systemId", marker.systemId)
                put("role", marker.role.name)
                put("label", marker.label?.let(::JsonPrimitive) ?: JsonNull)
                put("notes", marker.notes?.let(::JsonPrimitive) ?: JsonNull)
                put("color", marker.color.name)
            })
        }
    })
}

private fun savedMarkerJson(value: dev.evestaticmapplanner.control.SavedMarkerSummaryDto) = buildJsonObject {
    put("systemId", value.systemId)
    put("name", value.name?.let(::JsonPrimitive) ?: JsonNull)
    put("color", value.color.name)
    put("notes", value.notes?.let(::JsonPrimitive) ?: JsonNull)
    put("children", buildJsonArray {
        value.children.forEach { child ->
            add(buildJsonObject {
                put("id", child.id)
                put("type", child.type)
                put("orderIndex", child.orderIndex)
            })
        }
    })
    put("createdBy", value.createdBy.name)
}

private fun createSavedMarkerReceiptJson(value: CreateSavedMarkerReceipt) = buildJsonObject {
    put("marker", savedMarkerJson(value.marker))
}

private fun normalRouteJson(value: NormalRouteDto) = buildJsonObject {
    put("startSystemId", value.startSystemId)
    put("destinationSystemId", value.destinationSystemId)
    put("systemIds", value.systemIds.toJsonArray())
    put("totalJumps", value.totalJumps)
    put("stargateJumps", value.stargateJumps)
    put("ansiblexJumps", value.ansiblexJumps)
    put("wormholeJumps", value.wormholeJumps)
    put("waypointSystemIds", value.waypointSystemIds.toJsonArray())
    put("explicitDestinationSystemId", value.explicitDestinationSystemId?.let(::JsonPrimitive) ?: JsonNull)
}

private fun wormholeConnectionJson(value: WormholeConnectionDto) = buildJsonObject {
    put("connectionId", value.connectionId)
    put("firstSystemId", value.firstSystemId)
    put("firstSystemName", value.firstSystemName?.let(::JsonPrimitive) ?: JsonNull)
    put("secondSystemId", value.secondSystemId)
    put("secondSystemName", value.secondSystemName?.let(::JsonPrimitive) ?: JsonNull)
}

private fun createWormholeReceiptJson(value: CreateWormholeReceipt) = buildJsonObject {
    put("connection", wormholeConnectionJson(value.connection))
    put("created", value.created)
    put("status", value.status)
}

private fun capitalRouteJson(value: CapitalRouteDto) = buildJsonObject {
    put("startSystemId", value.startSystemId)
    put("destinationSystemId", value.destinationSystemId)
    put("effectiveRangeLy", value.effectiveRangeLy)
    put("systemIds", value.systemIds.toJsonArray())
    put("legs", buildJsonArray {
        value.legs.forEach { leg ->
            add(buildJsonObject {
                put("fromSystemId", leg.fromSystemId)
                put("toSystemId", leg.toSystemId)
                put("distanceLy", leg.distanceLy)
            })
        }
    })
    put("totalJumps", value.totalJumps)
    put("totalDistanceLy", value.totalDistanceLy)
    put("waypointSystemIds", value.waypointSystemIds.toJsonArray())
    put("explicitDestinationSystemId", value.explicitDestinationSystemId?.let(::JsonPrimitive) ?: JsonNull)
}

private fun missionSummaryJson(value: MissionSummaryDto) = buildJsonObject {
    put("missionId", value.missionId.value)
    put("title", value.title)
    put("createdAtEpochMillis", value.createdAtEpochMillis)
    put("revision", value.revision)
    put("routeCount", value.routeCount)
    put("jumpRangeCount", value.jumpRangeCount)
    put("markerCount", value.markerCount)
    put("referencedSystemCount", value.referencedSystemCount)
    put("viewId", value.viewId)
}

private fun planningViewJson(value: PlanningViewDto) = buildJsonObject {
    put("viewId", value.viewId)
    put("label", value.label)
    put("current", value.current)
}

private fun eveNavigationTargetJson(value: EveNavigationTargetDto) = buildJsonObject {
    put("characterId", value.characterId)
    put("label", value.label)
    put("description", value.description?.let(::JsonPrimitive) ?: JsonNull)
    put("available", value.available)
}

private fun sendMissionNavigationReceiptJson(value: SendMissionNavigationReceipt) = buildJsonObject {
    put("missionId", value.missionId.value)
    put("routeId", value.routeId.value)
    put("characterId", value.characterId)
    put("targetSystemIds", value.targetSystemIds.toJsonArray())
    put("status", value.status.name)
    put("message", value.message?.let(::JsonPrimitive) ?: JsonNull)
}

private fun missionJson(value: Mission) = buildJsonObject {
    put("missionId", value.missionId.value)
    put("title", value.title)
    put("createdAtEpochMillis", value.createdAt.toEpochMilli())
    put("revision", value.revision)
    put("viewId", value.viewId)
    put("routes", buildJsonArray { value.routes.forEach { add(missionRouteJson(it)) } })
    put("jumpRanges", buildJsonArray {
        value.jumpRanges.forEach { range ->
            add(buildJsonObject {
                put("missionId", range.missionId.value)
                put("jumpRangeId", range.jumpRangeId.value)
                put("originSystemId", range.originSystemId)
                put("profile", buildJsonObject {
                    put("id", range.profile.id)
                    put("displayName", range.profile.displayName)
                    put("maxRangeLy", range.profile.maxRangeLy)
                })
                put("reachableSystemIds", range.reachableSystemIds.sorted().toJsonArray())
                put("label", range.label?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    })
    put("markers", buildJsonArray {
        value.markers.forEach { marker ->
            add(buildJsonObject {
                put("missionId", marker.missionId.value)
                put("markerId", marker.markerId.value)
                put("systemId", marker.systemId)
                put("role", marker.role.name)
                put("label", marker.label?.let(::JsonPrimitive) ?: JsonNull)
                put("notes", marker.notes?.let(::JsonPrimitive) ?: JsonNull)
                put("color", marker.color.name)
            })
        }
    })
    put("referencedSystemIds", value.referencedSystemIds.sorted().toJsonArray())
}

private fun missionRouteJson(value: MissionRoute): JsonObject = when (value) {
    is MissionRoute.Normal -> buildJsonObject {
        put("missionId", value.missionId.value)
        put("routeId", value.routeId.value)
        put("type", "NORMAL")
        put("route", normalRouteJson(value.route.toControlDto(value.navigationIntent)))
    }
    is MissionRoute.Capital -> buildJsonObject {
        put("missionId", value.missionId.value)
        put("routeId", value.routeId.value)
        put("type", "CAPITAL")
        put("route", capitalRouteJson(value.route.toControlDto(value.navigationIntent)))
    }
}

private fun missionRouteReceiptJson(value: MissionRouteReceipt) = buildJsonObject {
    put("missionId", value.missionId.value)
    put("routeId", value.routeId.value)
    when (val route = value.route) {
        is AnyRouteDto.Normal -> {
            put("type", "NORMAL")
            put("route", normalRouteJson(route.value))
        }
        is AnyRouteDto.Capital -> {
            put("type", "CAPITAL")
            put("route", capitalRouteJson(route.value))
        }
    }
}

private fun missionJumpRangeReceiptJson(value: MissionJumpRangeReceipt) = buildJsonObject {
    put("missionId", value.missionId.value)
    put("jumpRangeId", value.jumpRangeId.value)
    put("originSystemId", value.originSystemId)
    put("effectiveRangeLy", value.effectiveRangeLy)
    put("reachableSystemCount", value.reachableSystemCount)
}

private fun missionMarkerReceiptJson(value: MissionMarkerReceipt) = buildJsonObject {
    put("missionId", value.missionId.value)
    put("markerId", value.markerId.value)
    put("systemId", value.systemId)
    put("role", value.role.name)
}

private fun missionMutationReceiptJson(value: MissionMutationReceipt) = buildJsonObject {
    put("missionId", value.missionId.value)
}

private fun dev.evestaticmapplanner.core.route.RouteResult.toControlDto(
    intent: dev.evestaticmapplanner.core.route.NavigationIntent? = null,
) = NormalRouteDto(
    startSystemId,
    destinationSystemId,
    systems,
    totalJumps,
    stargateJumps,
    ansiblexJumps,
    wormholeJumps,
    intent?.waypointSystemIds.orEmpty(),
    if (intent == null) destinationSystemId else intent.destinationSystemId,
)

private fun dev.evestaticmapplanner.core.route.CapitalRouteResult.toControlDto(
    intent: dev.evestaticmapplanner.core.route.NavigationIntent? = null,
) = CapitalRouteDto(
    startSystemId,
    destinationSystemId,
    profile.maxRangeLy,
    systems,
    legs.map { dev.evestaticmapplanner.control.CapitalRouteLegDto(it.fromSystemId, it.toSystemId, it.distanceLy) },
    totalJumps,
    totalDistanceLy,
    intent?.waypointSystemIds.orEmpty(),
    if (intent == null) destinationSystemId else intent.destinationSystemId,
)

private fun Iterable<Int>.toJsonArray() = JsonArray(map(::JsonPrimitive))
