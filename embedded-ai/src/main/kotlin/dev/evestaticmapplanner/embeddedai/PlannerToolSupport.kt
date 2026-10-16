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
    resultSucceeded: (T) -> Boolean = { true },
    mapErrorCode: (ControlError) -> String = { it.code.name },
): String {
    val callId = plannerToolStarted(toolName, diagnostics)
    val result = try {
        query()
    } catch (cancelled: CancellationException) {
        plannerToolCancelled(callId, diagnostics, "Cancelled with the AI request.")
        throw cancelled
    } catch (failure: Throwable) {
        plannerToolFailed(callId, diagnostics, safeToolFailureMessage(failure))
        throw failure
    }
    return when (result) {
        is ControlResult.Success -> {
            val serialized = try {
                serialize(result.value).boundedToolResult()
            } catch (cancelled: CancellationException) {
                plannerToolCancelled(callId, diagnostics, "Cancelled with the AI request.")
                throw cancelled
            } catch (failure: Throwable) {
                plannerToolFailed(callId, diagnostics, safeToolFailureMessage(failure))
                throw failure
            }
            if (resultSucceeded(result.value)) {
                plannerToolSucceeded(callId, diagnostics)
            } else {
                plannerToolFailed(callId, diagnostics, "The Planner reported that the action was not completed.")
            }
            serialized
        }
        is ControlResult.Failure -> {
            val safeError = "${mapErrorCode(result.error)}: ${result.error.message}"
            plannerToolFailed(callId, diagnostics, safeError)
            throw EmbeddedAiToolException(safeError)
        }
    }
}

internal suspend fun <T> requirePlannerValue(
    query: suspend () -> ControlResult<T>,
    mapErrorCode: (ControlError) -> String = { it.code.name },
): T = when (val result = query()) {
    is ControlResult.Success -> result.value
    is ControlResult.Failure -> throw EmbeddedAiToolException(
        "${mapErrorCode(result.error)}: ${result.error.message}",
    )
}

internal fun String.boundedToolResult(): String {
    if (length > MAX_TOOL_RESULT_CHARS) {
        throw EmbeddedAiToolException(
            "RESULT_TOO_LARGE: Planner returned more data than Aura can safely process",
        )
    }
    return this
}

enum class EmbeddedAiToolErrorCode {
    MISSION_NOT_FOUND,
    SYSTEM_NOT_FOUND,
    INVALID_RANGE,
    ROUTE_UNREACHABLE,
    INVALID_MARKER_ROLE,
    MAP_OPERATION_FAILED,
    MISSION_OPERATION_FAILED,
    RESULT_TOO_LARGE,
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
internal fun toolCancellationDiagnostic() = "Tool result: cancelled"

private fun safeToolFailureMessage(failure: Throwable): String = when (failure) {
    is EmbeddedAiToolException -> failure.message ?: "The Planner tool failed."
    else -> "The Planner tool could not complete."
}

internal class EmbeddedAiToolException(message: String) : RuntimeException(message)

internal const val MAX_TOOL_RESULT_CHARS = 65_536
