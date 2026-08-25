package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayState

data class PresentedFeatureOverlayEntry(
    val systemId: Int,
    val ringIndex: Int,
    val color: Color = DEFAULT_FEATURE_OVERLAY_COLOR,
)

data class FeatureOverlayLegendEntry(
    val label: String,
    val color: Color,
)

data class FeatureOverlayLegendSection(
    val title: String,
    val entries: List<FeatureOverlayLegendEntry>,
)

data class FeatureOverlayPresentation(
    val entries: List<PresentedFeatureOverlayEntry>,
    val legendSections: List<FeatureOverlayLegendSection> = emptyList(),
)

object FeatureOverlayPresentationBuilder {
    fun build(state: OverlayState, scene: ProjectedMapScene): FeatureOverlayPresentation {
        val ringCounts = mutableMapOf<Int, Int>()
        val legendSections = mutableListOf<FeatureOverlayLegendSection>()
        val entries = state.layers.flatMap { layerState ->
            val visibleEntries = layerState.entries.asSequence()
                .filter { it.visibility == OverlayEntryVisibility.VISIBLE }
                .filter { scene.nodesById.containsKey(it.systemId) }
                .sortedBy { it.systemId }
                .toList()
            val legendEntries = visibleEntries.mapNotNull { entry ->
                val color = parseRingColor(entry.value) ?: return@mapNotNull null
                entry.title?.let { FeatureOverlayLegendEntry(it, color) }
            }.distinct().sortedBy { it.label }
            if (legendEntries.isNotEmpty()) {
                legendSections += FeatureOverlayLegendSection(layerState.layer.name, legendEntries)
            }
            visibleEntries.asSequence()
                .map { entry ->
                    val ringIndex = ringCounts.getOrDefault(entry.systemId, 0)
                    ringCounts[entry.systemId] = ringIndex + 1
                    PresentedFeatureOverlayEntry(
                        systemId = entry.systemId,
                        ringIndex = ringIndex,
                        color = parseRingColor(entry.value) ?: DEFAULT_FEATURE_OVERLAY_COLOR,
                    )
                }
                .toList()
        }
        return FeatureOverlayPresentation(entries, legendSections)
    }
}

internal fun parseRingColor(value: String?): Color? {
    val match = RING_COLOR_PATTERN.matchEntire(value ?: return null) ?: return null
    return Color(match.groupValues[1].toLong(16))
}

internal val DEFAULT_FEATURE_OVERLAY_COLOR = Color(0xFF8EA8BD)
private val RING_COLOR_PATTERN = Regex("ring-color:#([0-9A-Fa-f]{8})")
