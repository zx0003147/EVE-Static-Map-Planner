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
import dev.evestaticmapplanner.feature.api.OverlayState
import org.jetbrains.skia.Image

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
        val images = marker.images.mapNotNull { image ->
            runCatching { Image.makeFromEncoded(image.content).toComposeImageBitmap() }.getOrNull()
        }
        if (images.isEmpty()) return@mapNotNull null
        PresentedOverlaySystemMarker(
            entry.systemId,
            Offset(node.x, node.y - PIN_CENTER_OFFSET_PX),
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
    dx * dx + dy * dy <= PIN_HIT_RADIUS_PX * PIN_HIT_RADIUS_PX
}

internal fun DrawScope.drawOverlaySystemMarkers(
    markers: List<PresentedOverlaySystemMarker>,
    textMeasurer: TextMeasurer,
) {
    markers.forEach { marker -> drawOverlaySystemMarker(marker, textMeasurer) }
}

private fun DrawScope.drawOverlaySystemMarker(marker: PresentedOverlaySystemMarker, textMeasurer: TextMeasurer) {
    val radius = PIN_RADIUS_PX
    val shadowCenter = marker.center + Offset(0f, 3f)
    val pinPath = Path().apply {
        moveTo(marker.center.x - 8f, marker.center.y + radius - 3f)
        lineTo(marker.node.x, marker.node.y)
        lineTo(marker.center.x + 8f, marker.center.y + radius - 3f)
        close()
    }
    drawPath(pinPath, Color(0x73000000))
    drawCircle(Color(0x66000000), radius + 4f, shadowCenter)
    drawPath(pinPath, Color(0xFF24364B))
    drawCircle(Color(0xFF24364B), radius + 3f, marker.center)

    val count = marker.images.size
    marker.images.forEachIndexed { index, image ->
        val sweep = 360f / count
        val sector = Path().apply {
            moveTo(marker.center.x, marker.center.y)
            arcTo(
                Rect(marker.center.x - radius, marker.center.y - radius, marker.center.x + radius, marker.center.y + radius),
                -90f + sweep * index,
                sweep,
                false,
            )
            close()
        }
        clipPath(sector) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset((marker.center.x - radius).toInt(), (marker.center.y - radius).toInt()),
                dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                filterQuality = FilterQuality.High,
            )
        }
    }
    drawCircle(Color(0xFFE6F4FF), radius + 1f, marker.center, style = Stroke(2.5f))
    drawCircle(Color(0x66FFFFFF), radius - 4f, marker.center + Offset(-3f, -4f), style = Stroke(1.5f))

    if (marker.overflowCount > 0) {
        val badgeCenter = marker.center + Offset(radius - 2f, -radius + 3f)
        drawCircle(Color(0xFF0B1724), 10f, badgeCenter)
        drawCircle(Color.White, 10f, badgeCenter, style = Stroke(1f))
        val text = textMeasurer.measure("+${marker.overflowCount}", TextStyle(Color.White, 9.sp))
        drawText(text, topLeft = badgeCenter - Offset(text.size.width / 2f, text.size.height / 2f))
    }
}

private fun dev.evestaticmapplanner.core.map.MapPoint.toOffset() = Offset(x.toFloat(), y.toFloat())

private const val PIN_RADIUS_PX = 22f
private const val PIN_HIT_RADIUS_PX = 27f
private const val PIN_CENTER_OFFSET_PX = 34f
