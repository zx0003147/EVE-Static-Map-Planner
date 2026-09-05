package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.map.GeometricMedian3D
import dev.evestaticmapplanner.core.map.MapPoint3
import dev.evestaticmapplanner.core.map.Real3DStaticGeometry
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayState
import java.util.Locale

internal data class Real3DFeatureSystemEntry(
    val systemId: Int,
    val groupKey: String,
    val color: Color,
    val title: String?,
    val isSovereignty: Boolean,
)

internal data class Real3DFeatureEmblemCandidate(
    val groupKey: String,
    val reference: PresentationEmblemReference,
    val anchor: MapPoint3,
    val systemCount: Int,
)

internal data class Real3DFeatureOverlayPresentation(
    val entries: List<Real3DFeatureSystemEntry>,
    val decorativeEntriesBySystemId: Map<Int, List<Real3DFeatureSystemEntry>>,
    val sovereigntyColorsBySystemId: Map<Int, Color>,
    val linkColorsBySystemPair: Map<Long, Color>,
    val emblems: List<Real3DFeatureEmblemCandidate>,
    val legendSections: List<FeatureOverlayLegendSection>,
) {
    companion object {
        val Empty = Real3DFeatureOverlayPresentation(
            emptyList(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyList(),
            emptyList(),
        )
    }
}

internal object Real3DFeatureOverlayPresentationBuilder {
    fun build(
        state: OverlayState,
        geometry: Real3DStaticGeometry,
    ): Real3DFeatureOverlayPresentation {
        if (state.isEmpty) return Real3DFeatureOverlayPresentation.Empty
        val entries = mutableListOf<Real3DFeatureSystemEntry>()
        val emblemMembers = linkedMapOf<Pair<String, PresentationEmblemReference>, MutableList<MapPoint3>>()
        val legendSections = mutableListOf<FeatureOverlayLegendSection>()
        state.layers.forEach { layerState ->
            val isSovereigntyLayer = layerState.provider.id == SOVEREIGNTY_OVERLAY_PROVIDER_ID &&
                layerState.layer.id == SOVEREIGNTY_OVERLAY_LAYER_ID
            val visibleEntries = layerState.entries.asSequence()
                .filter { it.visibility == OverlayEntryVisibility.VISIBLE }
                .filter { it.systemId in geometry.nodesById }
                .sortedBy { it.systemId }
                .toList()
            val legendEntries = visibleEntries.mapNotNull { entry ->
                val color = parseFeatureOverlay3DStyle(entry.value).color ?: return@mapNotNull null
                entry.title?.let { FeatureOverlayLegendEntry(it, color) }
            }.distinct().sortedBy(FeatureOverlayLegendEntry::label)
            if (legendEntries.isNotEmpty()) {
                legendSections += FeatureOverlayLegendSection(layerState.layer.name, legendEntries)
            }
            visibleEntries.forEach { entry ->
                val style = parseFeatureOverlay3DStyle(entry.value)
                if (
                    style.markerOnly || (
                        entry.systemMarker != null &&
                            style.color == null &&
                            style.ownerKey == null &&
                            style.emblemReference == null
                        )
                ) return@forEach
                val ownerKey = style.ownerKey
                    ?: entry.title?.lowercase(Locale.ROOT)
                    ?: entry.value?.lowercase(Locale.ROOT)
                    ?: "system:${entry.systemId}"
                val groupKey = "${layerState.provider.id}:${layerState.layer.id}:$ownerKey"
                entries += Real3DFeatureSystemEntry(
                    systemId = entry.systemId,
                    groupKey = groupKey,
                    color = style.color ?: DEFAULT_FEATURE_OVERLAY_COLOR,
                    title = entry.title,
                    isSovereignty = isSovereigntyLayer,
                )
                style.emblemReference?.let { reference ->
                    emblemMembers.getOrPut(groupKey to reference, ::mutableListOf) +=
                        geometry.nodesById.getValue(entry.systemId).position
                }
            }
        }
        val emblems = emblemMembers.map { (identity, positions) ->
            Real3DFeatureEmblemCandidate(
                groupKey = identity.first,
                reference = identity.second,
                anchor = GeometricMedian3D.calculate(positions),
                systemCount = positions.size,
            )
        }
        val decorativeEntriesBySystemId = entries.asSequence()
            .filterNot(Real3DFeatureSystemEntry::isSovereignty)
            .groupBy(Real3DFeatureSystemEntry::systemId)
        val sovereigntyColorsBySystemId = entries.asSequence()
            .filter(Real3DFeatureSystemEntry::isSovereignty)
            .associate { it.systemId to it.color }
        val linkColors = geometry.edges.mapNotNull { edge ->
            val firstEntries = decorativeEntriesBySystemId[edge.firstSystemId].orEmpty()
            val secondEntries = decorativeEntriesBySystemId[edge.secondSystemId].orEmpty()
            val sharedOwner = firstEntries.firstOrNull { first ->
                secondEntries.any { second -> second.groupKey == first.groupKey }
            } ?: return@mapNotNull null
            real3DSystemPairKey(edge.firstSystemId, edge.secondSystemId) to sharedOwner.color
        }.toMap()
        return Real3DFeatureOverlayPresentation(
            entries = entries,
            decorativeEntriesBySystemId = decorativeEntriesBySystemId,
            sovereigntyColorsBySystemId = sovereigntyColorsBySystemId,
            linkColorsBySystemPair = linkColors,
            emblems = emblems,
            legendSections = legendSections,
        )
    }
}

private const val SOVEREIGNTY_OVERLAY_PROVIDER_ID = "sovereignty.pack.overlay"
private const val SOVEREIGNTY_OVERLAY_LAYER_ID = "sovereignty"

internal fun real3DSystemPairKey(firstSystemId: Int, secondSystemId: Int): Long {
    val lower = minOf(firstSystemId, secondSystemId)
    val higher = maxOf(firstSystemId, secondSystemId)
    return (lower.toLong() shl 32) xor (higher.toLong() and 0xFFFF_FFFFL)
}
