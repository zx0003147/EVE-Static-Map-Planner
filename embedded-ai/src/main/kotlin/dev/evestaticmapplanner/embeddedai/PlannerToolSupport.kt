package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.ControlError
import dev.evestaticmapplanner.control.ControlErrorCode
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal fun embeddedAiRequestId(): String = "embedded-ai-${UUID.randomUUID()}"

internal fun embeddedAiIdempotencyKey(): String = "embedded-ai-tool-${UUID.randomUUID()}"

internal suspend fun <T> executePlannerQuery(
    toolName: String,
    diagnostics: (String) -> Unit,
    query: suspend () -> ControlResult<T>,
    serialize: (T) -> String,
    mapErrorCode: (ControlError) -> String = { it.code.name },
): String {
    diagnostics(toolCallDiagnostic(toolName))
    val result = try {
        query()
    } catch (cancelled: CancellationException) {
        diagnostics(toolFailureDiagnostic())
        throw cancelled
    } catch (failure: Throwable) {
        diagnostics(toolFailureDiagnostic())
        throw failure
    }
    return when (result) {
        is ControlResult.Success -> {
            diagnostics(toolSuccessDiagnostic())
            serialize(result.value)
        }
        is ControlResult.Failure -> {
            diagnostics(toolFailureDiagnostic())
            throw EmbeddedAiToolException("${mapErrorCode(result.error)}: ${result.error.message}")
        }
    }
}

enum class EmbeddedAiToolErrorCode {
    MISSION_NOT_FOUND,
    SYSTEM_NOT_FOUND,
    INVALID_RANGE,
    ROUTE_UNREACHABLE,
    INVALID_MARKER_ROLE,
    MAP_OPERATION_FAILED,
    MISSION_OPERATION_FAILED,
}

internal fun mapViewportToolError(error: ControlError): String = when (error.code) {
    ControlErrorCode.NOT_FOUND,
    ControlErrorCode.SYSTEM_NOT_FOUND,
    -> EmbeddedAiToolErrorCode.SYSTEM_NOT_FOUND.name
    ControlErrorCode.APP_NOT_READY,
    ControlErrorCode.INTERNAL_ERROR,
    -> EmbeddedAiToolErrorCode.MAP_OPERATION_FAILED.name
    else -> error.code.name
}

internal fun mapMissionToolError(error: ControlError): String = when (error.code) {
    ControlErrorCode.INTERNAL_ERROR -> EmbeddedAiToolErrorCode.MISSION_OPERATION_FAILED.name
    else -> error.code.name
}

internal fun mapRouteDisplayToolError(error: ControlError): String = when (error.code) {
    ControlErrorCode.ROUTE_NOT_FOUND -> EmbeddedAiToolErrorCode.ROUTE_UNREACHABLE.name
    ControlErrorCode.NOT_FOUND,
    ControlErrorCode.SYSTEM_NOT_FOUND,
    -> EmbeddedAiToolErrorCode.SYSTEM_NOT_FOUND.name
    ControlErrorCode.INTERNAL_ERROR -> EmbeddedAiToolErrorCode.MISSION_OPERATION_FAILED.name
    else -> error.code.name
}

internal fun mapCapitalRouteDisplayToolError(error: ControlError): String = when {
    error.code == ControlErrorCode.INVALID_ARGUMENT && error.message.contains("range", ignoreCase = true) ->
        EmbeddedAiToolErrorCode.INVALID_RANGE.name
    else -> mapRouteDisplayToolError(error)
}

internal fun mapJumpRangeToolError(error: ControlError): String = when {
    error.code == ControlErrorCode.INVALID_ARGUMENT && error.message.contains("range", ignoreCase = true) ->
        EmbeddedAiToolErrorCode.INVALID_RANGE.name
    error.code == ControlErrorCode.NOT_FOUND || error.code == ControlErrorCode.SYSTEM_NOT_FOUND ->
        EmbeddedAiToolErrorCode.SYSTEM_NOT_FOUND.name
    error.code == ControlErrorCode.INTERNAL_ERROR -> EmbeddedAiToolErrorCode.MISSION_OPERATION_FAILED.name
    else -> error.code.name
}

internal fun mapMarkerToolError(error: ControlError): String = when {
    (error.code == ControlErrorCode.INVALID_ARGUMENT || error.code == ControlErrorCode.INVALID_MARKER_DATA) &&
        error.message.contains("role", ignoreCase = true) -> EmbeddedAiToolErrorCode.INVALID_MARKER_ROLE.name
    error.code == ControlErrorCode.NOT_FOUND || error.code == ControlErrorCode.SYSTEM_NOT_FOUND ->
        EmbeddedAiToolErrorCode.SYSTEM_NOT_FOUND.name
    error.code == ControlErrorCode.INTERNAL_ERROR -> EmbeddedAiToolErrorCode.MISSION_OPERATION_FAILED.name
    else -> error.code.name
}

internal fun toolCallDiagnostic(toolName: String) = "Tool call: $toolName"
internal fun toolSuccessDiagnostic() = "Tool result: success"
internal fun toolFailureDiagnostic() = "Tool result: failure"

internal class EmbeddedAiToolException(message: String) : RuntimeException(message)
