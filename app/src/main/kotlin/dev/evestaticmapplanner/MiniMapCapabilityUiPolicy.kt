package dev.evestaticmapplanner

internal data class MiniMapCapabilityUiDecision(
    val showMiniMapWindow: Boolean,
    val showSettingsWindow: Boolean,
    val disableMiniMap: Boolean,
    val closeSettingsWindow: Boolean,
)

/** Keeps every Mini-map surface subordinate to a live typed Character Tracking provider. */
internal fun miniMapCapabilityUiDecision(
    characterTrackingAvailable: Boolean,
    miniMapEnabled: Boolean,
    settingsWindowOpen: Boolean,
): MiniMapCapabilityUiDecision = if (characterTrackingAvailable) {
    MiniMapCapabilityUiDecision(
        showMiniMapWindow = miniMapEnabled,
        showSettingsWindow = settingsWindowOpen,
        disableMiniMap = false,
        closeSettingsWindow = false,
    )
} else {
    MiniMapCapabilityUiDecision(
        showMiniMapWindow = false,
        showSettingsWindow = false,
        disableMiniMap = miniMapEnabled,
        closeSettingsWindow = settingsWindowOpen,
    )
}
