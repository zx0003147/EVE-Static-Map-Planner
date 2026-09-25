package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import dev.evestaticmapplanner.charactertracking.CharacterMapPresentation
import dev.evestaticmapplanner.charactertracking.CharacterSystemMarker
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.localization.MapStrings
import dev.evestaticmapplanner.ui.drawCharacterPortraitDisc

internal data class PresentedCharacterSystemMarker(
    val marker: CharacterSystemMarker,
    val center: Offset,
    val node: Offset,
    val tooltipLines: List<String>,
)

internal fun presentCharacterSystemMarkers(
    presentation: CharacterMapPresentation,
    scene: ProjectedMapScene,
    transform: MapTransform,
    strings: MapStrings,
): List<PresentedCharacterSystemMarker> = positionCharacterSystemMarkers(
    presentation = presentation,
    knownSystemIds = scene.nodesById.keys,
    systemName = { systemId -> scene.nodesById[systemId]?.system?.name ?: strings.fallbackSystem(systemId) },
    screenPosition = { systemId -> scene.nodesById[systemId]?.position?.let(transform::worldToScreen) },
    strings = strings,
)

internal fun positionCharacterSystemMarkers(
    presentation: CharacterMapPresentation,
    knownSystemIds: Set<Int>,
    systemName: (Int) -> String,
    screenPosition: (Int) -> MapPoint?,
    strings: MapStrings,
): List<PresentedCharacterSystemMarker> = presentation.markers.mapNotNull { marker ->
    if (marker.systemId !in knownSystemIds) return@mapNotNull null
    val node = screenPosition(marker.systemId)?.toOffset() ?: return@mapNotNull null
    PresentedCharacterSystemMarker(
        marker = marker,
        center = Offset(node.x, node.y - OverlaySystemMarkerVisuals.CENTER_TO_NODE_PX),
        node = node,
        tooltipLines = characterMarkerTooltipLines(marker, systemName(marker.systemId), strings),
    )
}

internal fun hitTestCharacterSystemMarker(
    markers: List<PresentedCharacterSystemMarker>,
    point: MapPoint,
): PresentedCharacterSystemMarker? = markers.asReversed().firstOrNull { marker ->
    val dx = marker.center.x - point.x
    val dy = marker.center.y - point.y
    dx * dx + dy * dy <= OverlaySystemMarkerVisuals.HIT_RADIUS_PX * OverlaySystemMarkerVisuals.HIT_RADIUS_PX
}

internal fun characterMarkerTooltipLines(
    marker: CharacterSystemMarker,
    systemName: String,
    strings: MapStrings,
): List<String> = buildList {
    add(systemName)
    marker.characters.take(MAX_TOOLTIP_CHARACTERS).forEach { character ->
        val badges = buildList {
            if (character.isCurrentIdentity) add(strings.currentIdentity)
            if (character.isForegroundCharacter) add(strings.foregroundCharacter)
        }
        add(
            buildString {
                append(character.characterName)
                append(" · ")
                append(strings.characterLocationStatus(character.locationStatus))
                if (badges.isNotEmpty()) append(" · ${badges.joinToString(" · ")}")
            },
        )
    }
    val omitted = marker.characters.size - MAX_TOOLTIP_CHARACTERS
    if (omitted > 0) add(strings.moreTrackedCharacters(omitted))
}

internal fun DrawScope.drawCharacterSystemMarkers(
    markers: List<PresentedCharacterSystemMarker>,
    textMeasurer: TextMeasurer,
) {
    markers.forEach { marker -> drawCharacterSystemMarker(marker, textMeasurer) }
}

private fun DrawScope.drawCharacterSystemMarker(
    presented: PresentedCharacterSystemMarker,
    textMeasurer: TextMeasurer,
) {
    val marker = presented.marker
    val radius = OverlaySystemMarkerVisuals.PORTRAIT_RADIUS_PX
    val geometry = systemPortraitMarkerGeometry(presented.center, presented.node)
    val alpha = when {
        marker.hasDegradedLocation -> DEGRADED_ALPHA
        marker.allLocationsStale -> STALE_ALPHA
        marker.hasStaleLocation -> MIXED_STALE_ALPHA
        else -> 1f
    }
    val baseColor = when {
        marker.hasDegradedLocation -> DEGRADED_COLOR
        marker.hasStaleLocation -> STALE_COLOR
        else -> MARKER_BASE_COLOR
    }
    val pinPath = Path().apply {
        moveTo(geometry.pinBaseLeft.x, geometry.pinBaseLeft.y)
        lineTo(geometry.pinTip.x, geometry.pinTip.y)
        lineTo(geometry.pinBaseRight.x, geometry.pinBaseRight.y)
        close()
    }
    drawPath(pinPath, Color(0x73000000))
    drawCircle(
        color = Color(0x66000000),
        radius = radius + OverlaySystemMarkerVisuals.SHADOW_RADIUS_EXTRA_PX,
        center = presented.center + Offset(0f, OverlaySystemMarkerVisuals.SHADOW_OFFSET_PX),
    )
    drawPath(pinPath, baseColor.copy(alpha = alpha))
    drawCircle(baseColor.copy(alpha = alpha), radius + OverlaySystemMarkerVisuals.OUTER_BORDER_WIDTH_PX, presented.center)
    drawCharacterPortraitDisc(
        stack = marker.portraits,
        center = presented.center,
        radius = radius,
        textMeasurer = textMeasurer,
        borderColor = baseColor,
        alpha = alpha,
    )
    if (marker.containsCurrentIdentity) {
        drawCircle(
            color = CURRENT_IDENTITY_COLOR,
            radius = radius + CURRENT_IDENTITY_RING_OFFSET_PX,
            center = presented.center,
            style = Stroke(CURRENT_IDENTITY_RING_WIDTH_PX),
        )
    }
    if (marker.containsForegroundCharacter) {
        drawCircle(
            color = FOREGROUND_CHARACTER_COLOR,
            radius = radius + FOREGROUND_RING_OFFSET_PX,
            center = presented.center,
            style = Stroke(FOREGROUND_RING_WIDTH_PX),
        )
    }
}

internal fun characterSystemMarkerBounds(marker: PresentedCharacterSystemMarker): ScreenBounds {
    val radius = OverlaySystemMarkerVisuals.PORTRAIT_RADIUS_PX + FOREGROUND_RING_OFFSET_PX + FOREGROUND_RING_WIDTH_PX
    return ScreenBounds(
        minX = (marker.center.x - radius).toDouble(),
        minY = (marker.center.y - radius).toDouble(),
        maxX = (marker.center.x + radius).toDouble(),
        maxY = marker.node.y.toDouble(),
    )
}

private fun MapPoint.toOffset() = Offset(x.toFloat(), y.toFloat())

internal const val MAX_TOOLTIP_CHARACTERS = 20
internal const val STALE_ALPHA = 0.55f
internal const val MIXED_STALE_ALPHA = 0.75f
internal const val DEGRADED_ALPHA = 0.82f
internal val MARKER_BASE_COLOR = Color(0xFF24364B)
internal val STALE_COLOR = Color(0xFF748394)
internal val DEGRADED_COLOR = Color(0xFFFFA726)
internal val CURRENT_IDENTITY_COLOR = Color(0xFF43C6E8)
internal val FOREGROUND_CHARACTER_COLOR = Color(0xFFFFD166)
internal const val CURRENT_IDENTITY_RING_OFFSET_PX = 2.5f
internal const val CURRENT_IDENTITY_RING_WIDTH_PX = 1.25f
internal const val FOREGROUND_RING_OFFSET_PX = 4.5f
internal const val FOREGROUND_RING_WIDTH_PX = 1.25f
