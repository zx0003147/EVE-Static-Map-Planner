package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.preferences.MiniMapInteractionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val MINI_MAP_RECOVERY_HOTKEY_LABEL = "Ctrl + Shift + M"

internal enum class MiniMapRecoveryHotkeyStatus {
    NOT_STARTED,
    REGISTERED,
    FAILED,
    UNSUPPORTED,
    CLOSED,
}

internal data class MiniMapHudRuntimeState(
    val hotkeyStatus: MiniMapRecoveryHotkeyStatus = MiniMapRecoveryHotkeyStatus.NOT_STARTED,
    val diagnostic: String? = null,
) {
    val canLock: Boolean get() = hotkeyStatus == MiniMapRecoveryHotkeyStatus.REGISTERED
}

internal interface MiniMapGlobalHotkey : AutoCloseable {
    fun start(onPressed: () -> Unit, onFailure: (Throwable) -> Unit): Result<Unit>
}

internal class MiniMapHudController(
    private val globalHotkey: MiniMapGlobalHotkey?,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val mutableState = MutableStateFlow(MiniMapHudRuntimeState())

    val state: StateFlow<MiniMapHudRuntimeState> = mutableState.asStateFlow()

    fun start(onToggleRequested: () -> Unit) {
        synchronized(lifecycleLock) {
            check(mutableState.value.hotkeyStatus == MiniMapRecoveryHotkeyStatus.NOT_STARTED) {
                "Mini-map HUD controller is already started"
            }
            val hotkey = globalHotkey
            if (hotkey == null) {
                mutableState.value = MiniMapHudRuntimeState(
                    MiniMapRecoveryHotkeyStatus.UNSUPPORTED,
                    "Global recovery hotkey is unavailable on this platform; HUD Locked is disabled.",
                )
                return
            }
            hotkey.start(
                onPressed = onToggleRequested,
                onFailure = { failure -> markFailed(failure) },
            ).fold(
                onSuccess = {
                    mutableState.value = MiniMapHudRuntimeState(MiniMapRecoveryHotkeyStatus.REGISTERED)
                },
                onFailure = ::markFailed,
            )
        }
    }

    fun safeMode(requested: MiniMapInteractionMode): MiniMapInteractionMode =
        if (requested == MiniMapInteractionMode.HUD_LOCKED && !mutableState.value.canLock) {
            MiniMapInteractionMode.INTERACTIVE
        } else {
            requested
        }

    override fun close() {
        synchronized(lifecycleLock) {
            try {
                globalHotkey?.close()
            } finally {
                mutableState.value = MiniMapHudRuntimeState(MiniMapRecoveryHotkeyStatus.CLOSED)
            }
        }
    }

    private fun markFailed(failure: Throwable) {
        if (mutableState.value.hotkeyStatus == MiniMapRecoveryHotkeyStatus.CLOSED) return
        mutableState.value = MiniMapHudRuntimeState(
            MiniMapRecoveryHotkeyStatus.FAILED,
            "Could not register $MINI_MAP_RECOVERY_HOTKEY_LABEL: ${failure.message ?: failure::class.simpleName}. " +
                "HUD Locked is disabled.",
        )
    }
}
