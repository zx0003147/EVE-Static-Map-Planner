package dev.evestaticmapplanner.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class WebTransientNotificationKind {
    INFORMATION,
    ERROR,
}

internal enum class WebTransientNotificationPhase {
    VISIBLE,
    FADING,
    HIDDEN,
}

internal data class WebTransientNotificationView(
    val text: String?,
    val kind: WebTransientNotificationKind,
    val phase: WebTransientNotificationPhase,
)

internal class WebTransientNotificationController(
    private val scope: CoroutineScope,
    private val onViewChanged: (WebTransientNotificationView) -> Unit,
    private val informationVisibleMillis: Long = INFORMATION_VISIBLE_MILLIS,
    private val errorVisibleMillis: Long = ERROR_VISIBLE_MILLIS,
    private val fadeOutMillis: Long = FADE_OUT_MILLIS,
) {
    private var lastPlannerRevision = 0L
    private var generation = 0L
    private var hideJob: Job? = null

    fun accept(state: WebPlannerState) {
        if (state.notificationRevision == 0L || state.notificationRevision == lastPlannerRevision) return
        lastPlannerRevision = state.notificationRevision
        when {
            state.error != null -> show(state.error, WebTransientNotificationKind.ERROR)
            state.message != null -> show(state.message, WebTransientNotificationKind.INFORMATION)
        }
    }

    fun showError(message: String) {
        show(message, WebTransientNotificationKind.ERROR)
    }

    fun close() {
        generation += 1
        hideJob?.cancel()
        hideJob = null
    }

    private fun show(message: String, kind: WebTransientNotificationKind) {
        if (message.isBlank()) return
        val currentGeneration = ++generation
        hideJob?.cancel()
        onViewChanged(WebTransientNotificationView(message, kind, WebTransientNotificationPhase.VISIBLE))
        hideJob = scope.launch {
            delay(if (kind == WebTransientNotificationKind.ERROR) errorVisibleMillis else informationVisibleMillis)
            if (currentGeneration != generation) return@launch
            onViewChanged(WebTransientNotificationView(message, kind, WebTransientNotificationPhase.FADING))
            delay(fadeOutMillis)
            if (currentGeneration != generation) return@launch
            onViewChanged(WebTransientNotificationView(null, kind, WebTransientNotificationPhase.HIDDEN))
            hideJob = null
        }
    }
}

internal const val INFORMATION_VISIBLE_MILLIS = 3_000L
internal const val ERROR_VISIBLE_MILLIS = 6_000L
internal const val FADE_OUT_MILLIS = 200L
