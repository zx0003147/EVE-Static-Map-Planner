package dev.evestaticmapplanner.jump

import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.model.SolarSystem

data class JumpOverlayUiState(
    val isLoading: Boolean = true,
    val isCalculating: Boolean = false,
    val error: String? = null,
    val originQuery: String = "",
    val originResults: List<SolarSystem> = emptyList(),
    val selectedOrigin: SolarSystem? = null,
    val manualRangeText: String = "7",
    val overlays: List<JumpRangeOverlay> = emptyList(),
    val coverageCounts: Map<Int, Int> = emptyMap(),
    val intersectionOverlayIds: Set<String> = emptySet(),
    val intersectionSystemIds: Set<Int> = emptySet(),
) {
    fun coveringOverlays(systemId: Int): List<JumpRangeOverlay> =
        overlays.filter { it.enabled && systemId in it.reachableSystemIds }
}
