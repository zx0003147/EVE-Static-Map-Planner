package dev.evestaticmapplanner.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One clock drives every visible navigation route. The state is read from the route Canvas draw
 * lambdas, so frames update only the crawling phase and never rebuild route data or the map scene.
 */
@Composable
internal fun rememberNavigationRouteAnimationPhasePx(isRunning: Boolean): State<Float> {
    val phasePx = remember { mutableStateOf(0f) }
    LaunchedEffect(isRunning) {
        if (!isRunning) {
            phasePx.value = 0f
            return@LaunchedEffect
        }
        val startedAtNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameTimeNanos ->
                phasePx.value = navigationRouteAnimationPhasePx(startedAtNanos, frameTimeNanos)
            }
        }
    }
    return phasePx
}

/** EVE Spai advances its route phase by 28 screen pixels per second. */
internal fun navigationRouteAnimationPhasePx(startedAtNanos: Long, frameTimeNanos: Long): Float {
    if (frameTimeNanos <= startedAtNanos) return 0f
    val elapsedNanos = frameTimeNanos - startedAtNanos
    val travelledPx = elapsedNanos.toDouble() * NAVIGATION_ROUTE_FLOW_SPEED_PX_PER_SECOND / 1_000_000_000.0
    return (travelledPx % NAVIGATION_ROUTE_FLOW_PERIOD_PX).toFloat()
}

/**
 * Carries one dash phase through all legs of a route. A leg starts at [routePhasePx] minus the
 * complete screen-space distance already walked, matching Spai's `phase - walked` convention.
 */
internal class NavigationRoutePhaseCursor(private val routePhasePx: Float) {
    init {
        require(routePhasePx.isFinite()) { "Navigation route phase must be finite" }
    }

    var walkedDistancePx: Float = 0f
        private set

    val currentPhasePx: Float
        get() = routePhasePx - walkedDistancePx

    fun advance(distancePx: Float) {
        require(distancePx.isFinite() && distancePx >= 0f) {
            "Navigation route distance must be finite and non-negative"
        }
        walkedDistancePx += distancePx
    }
}

internal data class NavigationRouteDashSpan(val startPx: Float, val endPx: Float)

/** Pure counterpart of Spai's `dashed_flow`, also used by geometry tests. */
internal fun navigationRouteDashSpans(lengthPx: Float, phasePx: Float): List<NavigationRouteDashSpan> {
    val result = mutableListOf<NavigationRouteDashSpan>()
    forEachNavigationRouteDashSpan(lengthPx, phasePx) { startPx, endPx ->
        result += NavigationRouteDashSpan(startPx, endPx)
    }
    return result
}

private inline fun forEachNavigationRouteDashSpan(
    lengthPx: Float,
    phasePx: Float,
    draw: (startPx: Float, endPx: Float) -> Unit,
) {
    require(lengthPx.isFinite() && lengthPx >= 0f) {
        "Navigation route segment length must be finite and non-negative"
    }
    require(phasePx.isFinite()) { "Navigation route phase must be finite" }
    if (lengthPx < 1f) return

    var distancePx = (phasePx % NAVIGATION_ROUTE_FLOW_PERIOD_PX) - NAVIGATION_ROUTE_FLOW_PERIOD_PX
    while (distancePx < lengthPx) {
        val startPx = max(distancePx, 0f)
        val endPx = min(distancePx + NAVIGATION_ROUTE_FLOW_DASH_PX, lengthPx)
        if (endPx > startPx) draw(startPx, endPx)
        distancePx += NAVIGATION_ROUTE_FLOW_PERIOD_PX
    }
}

/**
 * Draws the route itself as forward-crawling dashes. There is deliberately no static route base or
 * moving highlight layer. Quadratic Ansiblex geometry is sampled into the same 14 sections as Spai;
 * every section receives `phase - walked`, so a dash never restarts at a sampling boundary.
 *
 * @return sampled screen-space length, for carrying phase into the route's next leg.
 */
internal fun DrawScope.drawNavigationRouteGeometry(
    geometry: MapConnectionGeometry,
    startColor: Color,
    endColor: Color = startColor,
    phasePx: Float,
): Float {
    val totalLengthPx = navigationRouteGeometryLength(geometry)
    if (totalLengthPx < 1f) return totalLengthPx

    var walkedPx = 0f
    forEachNavigationRouteLinearSegment(geometry) { start, end ->
        val lengthPx = mapPointDistance(start, end)
        if (lengthPx >= 1f) {
            val colorPosition = ((walkedPx + lengthPx * 0.5f) / totalLengthPx).coerceIn(0f, 1f)
            drawNavigationRouteLinearSegment(
                start = start,
                end = end,
                color = lerp(startColor, endColor, colorPosition),
                phasePx = phasePx - walkedPx,
            )
        }
        walkedPx += lengthPx
    }
    return totalLengthPx
}

internal fun navigationRouteGeometryLength(geometry: MapConnectionGeometry): Float {
    var totalLengthPx = 0f
    forEachNavigationRouteLinearSegment(geometry) { start, end ->
        totalLengthPx += mapPointDistance(start, end)
    }
    return totalLengthPx
}

internal inline fun forEachNavigationRouteLinearSegment(
    geometry: MapConnectionGeometry,
    visit: (start: MapPoint, end: MapPoint) -> Unit,
) {
    when (geometry) {
        is StraightMapConnectionGeometry -> visit(geometry.start, geometry.end)
        is QuadraticMapConnectionGeometry -> {
            var previous = geometry.start
            for (sampleIndex in 1..NAVIGATION_ROUTE_CURVE_SEGMENTS) {
                val t = sampleIndex.toDouble() / NAVIGATION_ROUTE_CURVE_SEGMENTS
                val point = quadraticMapPoint(geometry, t)
                visit(previous, point)
                previous = point
            }
        }
    }
}

internal fun quadraticMapPoint(geometry: QuadraticMapConnectionGeometry, t: Double): MapPoint {
    val inverse = 1.0 - t
    return MapPoint(
        x = inverse * inverse * geometry.start.x +
            2.0 * inverse * t * geometry.control.x +
            t * t * geometry.end.x,
        y = inverse * inverse * geometry.start.y +
            2.0 * inverse * t * geometry.control.y +
            t * t * geometry.end.y,
    )
}

private fun DrawScope.drawNavigationRouteLinearSegment(
    start: MapPoint,
    end: MapPoint,
    color: Color,
    phasePx: Float,
) {
    val deltaX = (end.x - start.x).toFloat()
    val deltaY = (end.y - start.y).toFloat()
    val lengthPx = hypot(deltaX, deltaY)
    if (lengthPx < 1f) return
    val unitX = deltaX / lengthPx
    val unitY = deltaY / lengthPx
    forEachNavigationRouteDashSpan(lengthPx, phasePx) { dashStartPx, dashEndPx ->
        drawLine(
            color = color,
            start = Offset(
                x = start.x.toFloat() + unitX * dashStartPx,
                y = start.y.toFloat() + unitY * dashStartPx,
            ),
            end = Offset(
                x = start.x.toFloat() + unitX * dashEndPx,
                y = start.y.toFloat() + unitY * dashEndPx,
            ),
            strokeWidth = NAVIGATION_ROUTE_FLOW_STROKE_WIDTH_PX,
        )
    }
}

private fun mapPointDistance(start: MapPoint, end: MapPoint): Float =
    hypot((end.x - start.x).toFloat(), (end.y - start.y).toFloat())

internal const val NAVIGATION_ROUTE_FLOW_DASH_PX = 6f
internal const val NAVIGATION_ROUTE_FLOW_PERIOD_PX = 12f
internal const val NAVIGATION_ROUTE_FLOW_STROKE_WIDTH_PX = 2f
internal const val NAVIGATION_ROUTE_FLOW_SPEED_PX_PER_SECOND = 28f
internal const val NAVIGATION_ROUTE_CURVE_SEGMENTS = 14
