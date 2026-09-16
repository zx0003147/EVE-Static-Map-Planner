package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.control.ControlResult
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal fun embeddedAiRequestId(): String = "embedded-ai-${UUID.randomUUID()}"

internal suspend fun <T> executePlannerQuery(
    toolName: String,
    diagnostics: (String) -> Unit,
    query: suspend () -> ControlResult<T>,
    serialize: (T) -> String,
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
            throw EmbeddedAiToolException("${result.error.code}: ${result.error.message}")
        }
    }
}

internal fun toolCallDiagnostic(toolName: String) = "Tool call: $toolName"
internal fun toolSuccessDiagnostic() = "Tool result: success"
internal fun toolFailureDiagnostic() = "Tool result: failure"

internal class EmbeddedAiToolException(message: String) : RuntimeException(message)
