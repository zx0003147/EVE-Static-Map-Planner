package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.preferences.MiniMapInteractionMode
import dev.evestaticmapplanner.preferences.MiniMapPreferences
import dev.evestaticmapplanner.preferences.MiniMapWindowStyle

internal data class MiniMapWindowBehavior(
    val hudPresentation: Boolean,
    val interactionMode: MiniMapInteractionMode,
    val clickThrough: Boolean,
    val focusable: Boolean,
)

internal fun resolveMiniMapWindowBehavior(
    preferences: MiniMapPreferences,
    runtimeState: MiniMapHudRuntimeState,
): MiniMapWindowBehavior {
    val interactionMode = if (runtimeState.canLock) {
        preferences.interactionMode
    } else {
        MiniMapInteractionMode.INTERACTIVE
    }
    val locked = interactionMode == MiniMapInteractionMode.HUD_LOCKED
    return MiniMapWindowBehavior(
        hudPresentation = preferences.windowStyle == MiniMapWindowStyle.HUD,
        interactionMode = interactionMode,
        clickThrough = locked,
        focusable = !locked,
    )
}

internal fun MiniMapPreferences.afterNativeHudFailure(): MiniMapPreferences = copy(
    windowStyle = MiniMapWindowStyle.STANDARD,
    interactionMode = MiniMapInteractionMode.INTERACTIVE,
)
