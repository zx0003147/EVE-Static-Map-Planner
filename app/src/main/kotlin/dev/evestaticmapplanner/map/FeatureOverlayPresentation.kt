package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.core.map.ProjectedMapScene

data class PresentedFeatureOverlayEntry(
    val systemId: Int,
    val ringIndex: Int,
)

data class FeatureOverlayPresentation(
    val entries: List<PresentedFeatureOverlayEntry>,
)

object FeatureOverlayPresentationBuilder {
    fun build(state: OverlayState, scene: ProjectedMapScene): FeatureOverlayPresentation {
        val ringCounts = mutableMapOf<Int, Int>()
        val entries = state.layers.flatMap { layerState ->
            layerState.entries.asSequence()
                .filter { it.visibility == OverlayEntryVisibility.VISIBLE }
                .filter { scene.nodesById.containsKey(it.systemId) }
                .sortedBy { it.systemId }
                .map { entry ->
                    val ringIndex = ringCounts.getOrDefault(entry.systemId, 0)
                    ringCounts[entry.systemId] = ringIndex + 1
                    PresentedFeatureOverlayEntry(entry.systemId, ringIndex)
                }
                .toList()
        }
        return FeatureOverlayPresentation(entries)
    }
}
