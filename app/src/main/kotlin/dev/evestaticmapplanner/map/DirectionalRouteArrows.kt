package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class DirectionalArrowheadGeometry(
    val tip: MapPoint,
    val firstTail: MapPoint,
    val secondTail: MapPoint,
    val unitTangent: MapPoint,
    val lengthPx: Double,
    val halfWidthPx: Double,
)

internal data class DirectionalChevronSize(
    val lengthPx: Double,
    val halfWidthPx: Double,
)

/**
 * Builds a bounded set of screen-space chevrons which follow the actual rendered route geometry.
 * The geometry's start/end order is the route's from/to order and is therefore the only source of
 * arrow direction.
 */
internal fun buildDirectionalArrowheads(
    geometry: MapConnectionGeometry,
    routeStrokeWidth: Float = ROUTE_ARROW_REFERENCE_STROKE_WIDTH_PX,
    scale: Double = 1.0,
    minimumReadableLengthPx: Double = ROUTE_ARROW_MIN_LENGTH_PX * scale,
): List<DirectionalArrowheadGeometry> {
    require(scale.isFinite() && scale > 0.0) { "Route arrow scale must be finite and positive" }
    val size = resolveDirectionalChevronSize(routeStrokeWidth, scale, minimumReadableLengthPx)

    val approximateLength = geometry.approximateLength()
    if (!approximateLength.isFinite() || approximateLength < ROUTE_ARROW_MIN_SEGMENT_LENGTH_PX * scale) {
        return emptyList()
    }

    val arrowCount = (approximateLength / (ROUTE_ARROW_TARGET_SPACING_PX * scale))
        .roundToInt()
        .coerceIn(1, ROUTE_ARROW_MAX_COUNT)
    return List(arrowCount) { index ->
        val amount = (index + 1.0) / (arrowCount + 1.0)
        geometry.arrowheadAt(
            amount = amount,
            arrowLength = size.lengthPx,
            arrowHalfWidth = size.halfWidthPx,
        )
    }.filterNotNull()
}

internal fun resolveDirectionalChevronSize(
    routeStrokeWidth: Float,
    scale: Double = 1.0,
    minimumReadableLengthPx: Double = ROUTE_ARROW_MIN_LENGTH_PX * scale,
): DirectionalChevronSize {
    require(routeStrokeWidth.isFinite() && routeStrokeWidth > 0f) {
        "Route stroke width must be finite and positive"
    }
    require(scale.isFinite() && scale > 0.0) { "Route arrow scale must be finite and positive" }
    require(minimumReadableLengthPx.isFinite() && minimumReadableLengthPx > 0.0) {
        "Minimum readable route arrow length must be finite and positive"
    }

    val mainMapLength = (
        ROUTE_ARROW_LENGTH_BASE_PX + routeStrokeWidth.toDouble() * ROUTE_ARROW_LENGTH_PER_STROKE_PX
    ).coerceIn(ROUTE_ARROW_MIN_LENGTH_PX, ROUTE_ARROW_MAX_LENGTH_PX)
    val maximumScaledLength = maxOf(ROUTE_ARROW_MAX_LENGTH_PX * scale, minimumReadableLengthPx)
    val length = (mainMapLength * scale).coerceIn(minimumReadableLengthPx, maximumScaledLength)
    return DirectionalChevronSize(
        lengthPx = length,
        halfWidthPx = length * ROUTE_ARROW_HALF_WIDTH_RATIO,
    )
}

internal fun DrawScope.drawDirectionalRouteArrows(
    geometry: MapConnectionGeometry,
    color: Color,
    strokeWidth: Float,
    scale: Double = 1.0,
    sizeReferenceStrokeWidth: Float = strokeWidth,
    minimumReadableLengthPx: Double = ROUTE_ARROW_MIN_LENGTH_PX * scale,
) {
    require(strokeWidth.isFinite() && strokeWidth > 0f) { "Chevron stroke width must be finite and positive" }
    val arrows = buildDirectionalArrowheads(
        geometry = geometry,
        routeStrokeWidth = sizeReferenceStrokeWidth,
        scale = scale,
        minimumReadableLengthPx = minimumReadableLengthPx,
    )
    if (arrows.isEmpty()) return

    val haloStrokeWidth = directionalChevronHaloStrokeWidth(strokeWidth, scale)
    arrows.forEach { arrow ->
        drawDirectionalChevron(arrow, ROUTE_ARROW_HALO_COLOR, haloStrokeWidth)
    }
    arrows.forEach { arrow ->
        drawDirectionalChevron(arrow, color, strokeWidth)
    }
}

internal fun directionalChevronHaloStrokeWidth(strokeWidth: Float, scale: Double = 1.0): Float {
    require(strokeWidth.isFinite() && strokeWidth > 0f) { "Chevron stroke width must be finite and positive" }
    require(scale.isFinite() && scale > 0.0) { "Route arrow scale must be finite and positive" }
    return strokeWidth + (ROUTE_ARROW_HALO_EXTRA_WIDTH_PX * scale).toFloat()
}

private fun DrawScope.drawDirectionalChevron(
    geometry: DirectionalArrowheadGeometry,
    color: Color,
    strokeWidth: Float,
) {
    drawLine(
        color = color,
        start = geometry.firstTail.toOffset(),
        end = geometry.tip.toOffset(),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = geometry.secondTail.toOffset(),
        end = geometry.tip.toOffset(),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

private fun MapConnectionGeometry.approximateLength(): Double = when (this) {
    is StraightMapConnectionGeometry -> distance(start, end)
    is QuadraticMapConnectionGeometry -> {
        val chordLength = distance(start, end)
        val controlNetLength = distance(start, control) + distance(control, end)
        (chordLength + controlNetLength) / 2.0
    }
}

private fun MapConnectionGeometry.arrowheadAt(
    amount: Double,
    arrowLength: Double,
    arrowHalfWidth: Double,
): DirectionalArrowheadGeometry? {
    val point: MapPoint
    val tangent: MapPoint
    when (this) {
        is StraightMapConnectionGeometry -> {
            point = interpolate(start, end, amount)
            tangent = MapPoint(end.x - start.x, end.y - start.y)
        }
        is QuadraticMapConnectionGeometry -> {
            val inverse = 1.0 - amount
            point = MapPoint(
                x = inverse * inverse * start.x + 2.0 * inverse * amount * control.x + amount * amount * end.x,
                y = inverse * inverse * start.y + 2.0 * inverse * amount * control.y + amount * amount * end.y,
            )
            tangent = MapPoint(
                x = 2.0 * inverse * (control.x - start.x) + 2.0 * amount * (end.x - control.x),
                y = 2.0 * inverse * (control.y - start.y) + 2.0 * amount * (end.y - control.y),
            )
        }
    }

    val tangentLength = hypot(tangent.x, tangent.y)
    if (!tangentLength.isFinite() || tangentLength <= ROUTE_ARROW_ZERO_LENGTH_EPSILON_PX) return null
    val unitX = tangent.x / tangentLength
    val unitY = tangent.y / tangentLength
    val tailCenter = MapPoint(point.x - unitX * arrowLength, point.y - unitY * arrowLength)
    val perpendicularX = -unitY * arrowHalfWidth
    val perpendicularY = unitX * arrowHalfWidth
    return DirectionalArrowheadGeometry(
        tip = point,
        firstTail = MapPoint(tailCenter.x + perpendicularX, tailCenter.y + perpendicularY),
        secondTail = MapPoint(tailCenter.x - perpendicularX, tailCenter.y - perpendicularY),
        unitTangent = MapPoint(unitX, unitY),
        lengthPx = arrowLength,
        halfWidthPx = arrowHalfWidth,
    )
}

private fun interpolate(first: MapPoint, second: MapPoint, amount: Double) = MapPoint(
    x = first.x + (second.x - first.x) * amount,
    y = first.y + (second.y - first.y) * amount,
)

private fun distance(first: MapPoint, second: MapPoint): Double = hypot(second.x - first.x, second.y - first.y)

private fun MapPoint.toOffset() = Offset(x.toFloat(), y.toFloat())

internal const val ROUTE_ARROW_MIN_SEGMENT_LENGTH_PX = 48.0
internal const val ROUTE_ARROW_TARGET_SPACING_PX = 90.0
internal const val ROUTE_ARROW_MAX_COUNT = 3
internal const val ROUTE_ARROW_MIN_LENGTH_PX = 10.0
internal const val ROUTE_ARROW_MAX_LENGTH_PX = 13.0
internal const val MINI_MAP_ROUTE_ARROW_MIN_LENGTH_PX = 8.5
internal const val MINI_MAP_ROUTE_ARROW_SCALE = 0.82
internal val ROUTE_ARROW_HALO_COLOR = Color(0xC709121D)
private const val ROUTE_ARROW_REFERENCE_STROKE_WIDTH_PX = 3f
private const val ROUTE_ARROW_LENGTH_BASE_PX = 3.25
private const val ROUTE_ARROW_LENGTH_PER_STROKE_PX = 2.25
private const val ROUTE_ARROW_HALF_WIDTH_RATIO = 0.52
private const val ROUTE_ARROW_HALO_EXTRA_WIDTH_PX = 1.8
private const val ROUTE_ARROW_ZERO_LENGTH_EPSILON_PX = 1e-6
