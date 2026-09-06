package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayImage
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.ui.CharacterPortraitSegment
import dev.evestaticmapplanner.ui.CharacterPortraitStack
import dev.evestaticmapplanner.ui.centeredPortraitCrop
import dev.evestaticmapplanner.ui.drawCharacterPortraitDisc
import dev.evestaticmapplanner.ui.portraitSeparatorSegments
import org.jetbrains.skia.Image

internal data class PresentedOverlaySystemMarker(
    val systemId: Int,
    val center: Offset,
    val node: Offset,
    val images: List<ImageBitmap>,
    val overflowCount: Int,
    val tooltipLines: List<String>,
)

internal data class DecodedOverlaySystemMarker(
    val systemId: Int,
    val images: List<ImageBitmap>,
    val overflowCount: Int,
    val tooltipLines: List<String>,
)

internal data class OverlaySystemMarkerGeometry(
    val portraitRect: Rect,
    val pinBaseLeft: Offset,
    val pinBaseRight: Offset,
    val pinTip: Offset,
)

internal fun presentOverlaySystemMarkers(
    state: OverlayState,
    scene: ProjectedMapScene,
    transform: MapTransform,
): List<PresentedOverlaySystemMarker> = positionOverlaySystemMarkers(
    decodeOverlaySystemMarkers(state, scene.nodesById.keys),
    screenPosition = { systemId ->
        scene.nodesById[systemId]?.position?.let(transform::worldToScreen)
    },
)

internal fun decodeOverlaySystemMarkers(
    state: OverlayState,
    knownSystemIds: Set<Int>,
): List<DecodedOverlaySystemMarker> = state.layers.flatMap { layer ->
    layer.entries.mapNotNull { entry ->
        val marker = entry.systemMarker ?: return@mapNotNull null
        if (entry.visibility != OverlayEntryVisibility.VISIBLE) return@mapNotNull null
        if (entry.systemId !in knownSystemIds) return@mapNotNull null
        val images = decodeOverlaySystemMarkerImages(marker.images)
        if (images.isEmpty()) return@mapNotNull null
        DecodedOverlaySystemMarker(
            entry.systemId,
            images,
            marker.overflowCount,
            marker.tooltipLines,
        )
    }
}

internal fun positionOverlaySystemMarkers(
    markers: List<DecodedOverlaySystemMarker>,
    screenPosition: (Int) -> MapPoint?,
): List<PresentedOverlaySystemMarker> = markers.mapNotNull { marker ->
    val node = screenPosition(marker.systemId)?.toOffset() ?: return@mapNotNull null
    PresentedOverlaySystemMarker(
        marker.systemId,
        Offset(node.x, node.y - OverlaySystemMarkerVisuals.CENTER_TO_NODE_PX),
        node,
        marker.images,
        marker.overflowCount,
        marker.tooltipLines,
    )
}

internal fun hitTestOverlaySystemMarker(
    markers: List<PresentedOverlaySystemMarker>,
    point: MapPoint,
): PresentedOverlaySystemMarker? = markers.asReversed().firstOrNull { marker ->
    val dx = marker.center.x - point.x
    val dy = marker.center.y - point.y
    dx * dx + dy * dy <= OverlaySystemMarkerVisuals.HIT_RADIUS_PX * OverlaySystemMarkerVisuals.HIT_RADIUS_PX
}

internal fun decodeOverlaySystemMarkerImages(images: List<OverlayImage>): List<ImageBitmap> = images.mapNotNull { image ->
    runCatching { Image.makeFromEncoded(image.content).toComposeImageBitmap() }.getOrNull()
}

internal fun DrawScope.drawOverlaySystemMarkers(
    markers: List<PresentedOverlaySystemMarker>,
    textMeasurer: TextMeasurer,
) {
    markers.forEach { marker -> drawOverlaySystemMarker(marker, textMeasurer) }
}

private fun DrawScope.drawOverlaySystemMarker(marker: PresentedOverlaySystemMarker, textMeasurer: TextMeasurer) {
    val radius = OverlaySystemMarkerVisuals.PORTRAIT_RADIUS_PX
    val geometry = overlaySystemMarkerGeometry(marker)
    val shadowCenter = marker.center + Offset(0f, OverlaySystemMarkerVisuals.SHADOW_OFFSET_PX)
    val pinPath = Path().apply {
        moveTo(geometry.pinBaseLeft.x, geometry.pinBaseLeft.y)
        lineTo(geometry.pinTip.x, geometry.pinTip.y)
        lineTo(geometry.pinBaseRight.x, geometry.pinBaseRight.y)
        close()
    }
    drawPath(pinPath, Color(0x73000000))
    drawCircle(Color(0x66000000), radius + OverlaySystemMarkerVisuals.SHADOW_RADIUS_EXTRA_PX, shadowCenter)
    drawPath(pinPath, Color(0xFF24364B))
    drawCircle(Color(0xFF24364B), radius + OverlaySystemMarkerVisuals.OUTER_BORDER_WIDTH_PX, marker.center)
    drawCharacterPortraitDisc(
        stack = CharacterPortraitStack(
            segments = marker.images.map { CharacterPortraitSegment(it, "?") },
            overflowCount = marker.overflowCount,
        ),
        center = marker.center,
        radius = radius,
        textMeasurer = textMeasurer,
    )
}

internal fun overlaySystemMarkerGeometry(marker: PresentedOverlaySystemMarker): OverlaySystemMarkerGeometry {
    val radius = OverlaySystemMarkerVisuals.PORTRAIT_RADIUS_PX
    return OverlaySystemMarkerGeometry(
        portraitRect = Rect(
            marker.center.x - radius,
            marker.center.y - radius,
            marker.center.x + radius,
            marker.center.y + radius,
        ),
        pinBaseLeft = Offset(
            marker.center.x - OverlaySystemMarkerVisuals.PIN_HALF_BASE_WIDTH_PX,
            marker.center.y + radius,
        ),
        pinBaseRight = Offset(
            marker.center.x + OverlaySystemMarkerVisuals.PIN_HALF_BASE_WIDTH_PX,
            marker.center.y + radius,
        ),
        pinTip = marker.node,
    )
}

internal fun overlaySystemMarkerPortraitRect(marker: PresentedOverlaySystemMarker): Rect =
    overlaySystemMarkerGeometry(marker).portraitRect

internal fun overlaySystemMarkerSeparatorSegments(marker: PresentedOverlaySystemMarker): List<Pair<Offset, Offset>> {
    return portraitSeparatorSegments(marker.center, OverlaySystemMarkerVisuals.PORTRAIT_RADIUS_PX, marker.images.size)
}

internal fun centeredSquareCrop(image: ImageBitmap): Pair<IntOffset, IntSize> = centeredPortraitCrop(image)

private fun dev.evestaticmapplanner.core.map.MapPoint.toOffset() = Offset(x.toFloat(), y.toFloat())

internal object OverlaySystemMarkerVisuals {
    const val PORTRAIT_DIAMETER_PX = 20f
    const val PORTRAIT_RADIUS_PX = PORTRAIT_DIAMETER_PX / 2f
    const val OUTER_BORDER_WIDTH_PX = 1f
    const val HIGHLIGHT_WIDTH_PX = 1f
    const val SEPARATOR_WIDTH_PX = 1f
    val SEPARATOR_COLOR = Color(0xE6FFFFFF)
    const val SHADOW_RADIUS_EXTRA_PX = 2f
    const val SHADOW_OFFSET_PX = 2f
    const val CENTER_TO_NODE_PX = 15f
    const val PIN_TIP_HEIGHT_PX = CENTER_TO_NODE_PX - PORTRAIT_RADIUS_PX
    const val PIN_HALF_BASE_WIDTH_PX = 4f
    const val TOTAL_HEIGHT_PX = CENTER_TO_NODE_PX + PORTRAIT_RADIUS_PX + OUTER_BORDER_WIDTH_PX
    const val HIT_RADIUS_PX = 12f
}
