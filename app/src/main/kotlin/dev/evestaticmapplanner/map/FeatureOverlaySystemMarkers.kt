package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayImage
import dev.evestaticmapplanner.feature.api.OverlayState
import org.jetbrains.skia.Image
import kotlin.math.min

internal data class PresentedOverlaySystemMarker(
    val systemId: Int,
    val center: Offset,
    val node: Offset,
    val images: List<ImageBitmap>,
    val overflowCount: Int,
    val tooltipLines: List<String>,
)

internal fun presentOverlaySystemMarkers(
    state: OverlayState,
    scene: ProjectedMapScene,
    transform: MapTransform,
): List<PresentedOverlaySystemMarker> = state.layers.flatMap { layer ->
    layer.entries.mapNotNull { entry ->
        val marker = entry.systemMarker ?: return@mapNotNull null
        if (entry.visibility != OverlayEntryVisibility.VISIBLE) return@mapNotNull null
        val node = scene.nodesById[entry.systemId]?.position?.let(transform::worldToScreen)?.toOffset()
            ?: return@mapNotNull null
        val images = decodeOverlaySystemMarkerImages(marker.images)
        if (images.isEmpty()) return@mapNotNull null
        PresentedOverlaySystemMarker(
            entry.systemId,
            Offset(node.x, node.y - OverlaySystemMarkerVisuals.CENTER_TO_NODE_PX),
            node,
            images,
            marker.overflowCount,
            marker.tooltipLines,
        )
    }
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
    val portraitRect = overlaySystemMarkerPortraitRect(marker)
    val shadowCenter = marker.center + Offset(0f, OverlaySystemMarkerVisuals.SHADOW_OFFSET_PX)
    val pinPath = Path().apply {
        moveTo(marker.center.x - 4f, marker.center.y + radius)
        lineTo(marker.node.x, marker.node.y)
        lineTo(marker.center.x + 4f, marker.center.y + radius)
        close()
    }
    drawPath(pinPath, Color(0x73000000))
    drawCircle(Color(0x66000000), radius + OverlaySystemMarkerVisuals.SHADOW_RADIUS_EXTRA_PX, shadowCenter)
    drawPath(pinPath, Color(0xFF24364B))
    drawCircle(Color(0xFF24364B), radius + OverlaySystemMarkerVisuals.OUTER_BORDER_WIDTH_PX, marker.center)

    val count = marker.images.size
    marker.images.forEachIndexed { index, image ->
        val clip = if (count == 1) {
            Path().apply { addOval(portraitRect) }
        } else {
            val sweep = 360f / count
            Path().apply {
                moveTo(marker.center.x, marker.center.y)
                arcTo(portraitRect, -90f + sweep * index, sweep, false)
                close()
            }
        }
        val crop = centeredSquareCrop(image)
        clipPath(clip) {
            drawImage(
                image = image,
                srcOffset = crop.first,
                srcSize = crop.second,
                dstOffset = IntOffset(portraitRect.left.toInt(), portraitRect.top.toInt()),
                dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                filterQuality = FilterQuality.High,
            )
        }
    }
    drawCircle(
        Color(0xFFE6F4FF),
        radius + OverlaySystemMarkerVisuals.OUTER_BORDER_WIDTH_PX / 2f,
        marker.center,
        style = Stroke(OverlaySystemMarkerVisuals.OUTER_BORDER_WIDTH_PX),
    )
    drawCircle(
        Color(0x66FFFFFF),
        radius - 3f,
        marker.center + Offset(-1.5f, -2f),
        style = Stroke(OverlaySystemMarkerVisuals.HIGHLIGHT_WIDTH_PX),
    )

    if (marker.overflowCount > 0) {
        val badgeCenter = marker.center + Offset(radius - 1f, -radius + 2f)
        drawCircle(Color(0xFF0B1724), 6f, badgeCenter)
        drawCircle(Color.White, 6f, badgeCenter, style = Stroke(1f))
        val text = textMeasurer.measure("+${marker.overflowCount}", TextStyle(Color.White, 7.sp))
        drawText(text, topLeft = badgeCenter - Offset(text.size.width / 2f, text.size.height / 2f))
    }
}

internal fun overlaySystemMarkerPortraitRect(marker: PresentedOverlaySystemMarker): Rect {
    val radius = OverlaySystemMarkerVisuals.PORTRAIT_RADIUS_PX
    return Rect(
        marker.center.x - radius,
        marker.center.y - radius,
        marker.center.x + radius,
        marker.center.y + radius,
    )
}

internal fun centeredSquareCrop(image: ImageBitmap): Pair<IntOffset, IntSize> {
    val side = min(image.width, image.height)
    return IntOffset((image.width - side) / 2, (image.height - side) / 2) to IntSize(side, side)
}

private fun dev.evestaticmapplanner.core.map.MapPoint.toOffset() = Offset(x.toFloat(), y.toFloat())

internal object OverlaySystemMarkerVisuals {
    const val PORTRAIT_DIAMETER_PX = 20f
    const val PORTRAIT_RADIUS_PX = PORTRAIT_DIAMETER_PX / 2f
    const val OUTER_BORDER_WIDTH_PX = 1.5f
    const val HIGHLIGHT_WIDTH_PX = 1f
    const val SHADOW_RADIUS_EXTRA_PX = 2f
    const val SHADOW_OFFSET_PX = 2f
    const val CENTER_TO_NODE_PX = 15f
    const val PIN_TIP_HEIGHT_PX = CENTER_TO_NODE_PX - PORTRAIT_RADIUS_PX
    const val TOTAL_HEIGHT_PX = CENTER_TO_NODE_PX + PORTRAIT_RADIUS_PX + OUTER_BORDER_WIDTH_PX
    const val HIT_RADIUS_PX = 12f
}
