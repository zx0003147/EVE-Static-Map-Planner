package dev.evestaticmapplanner.jump

import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.jump.JumpRangeColor
import dev.evestaticmapplanner.core.jump.JumpRangePreset
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.localization.UiMessage

data class JumpOverlayUiState(
    val isLoading: Boolean = true,
    val isCalculating: Boolean = false,
    val error: UiMessage? = null,
    val originQuery: String = "",
    val originResults: List<SolarSystem> = emptyList(),
    val selectedOrigin: SolarSystem? = null,
    val selectedPreset: JumpRangePreset = JumpRangePreset.DREADNOUGHT_CARRIER_FAX,
    val manualRangeText: String = "7",
    val selectedColor: JumpRangeColor = JumpRangeColor.BLUE,
    val overlays: List<JumpRangeOverlay> = emptyList(),
    val coverageCounts: Map<Int, Int> = emptyMap(),
    val intersectionOverlayIds: Set<String> = emptySet(),
    val intersectionSystemIds: Set<Int> = emptySet(),
) {
    fun coveringOverlays(systemId: Int): List<JumpRangeOverlay> =
        overlays.filter { it.enabled && systemId in it.reachableSystemIds }
}
