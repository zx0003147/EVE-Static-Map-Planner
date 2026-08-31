package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.math.hypot

internal enum class WormholeVisualDetail {
    SIMPLE,
    REDUCED,
    FULL,
}

internal data class WormholeChevronGeometry(
    val tip: MapPoint,
    val firstTail: MapPoint,
    val secondTail: MapPoint,
)

internal data class WormholeConnectionVisualGeometry(
    val firstSystemId: Int,
    val secondSystemId: Int,
    val firstEndpoint: MapPoint,
    val secondEndpoint: MapPoint,
    val lineStart: MapPoint,
    val lineEnd: MapPoint,
    val center: MapPoint,
    val firstHalfChevrons: List<WormholeChevronGeometry>,
    val secondHalfChevrons: List<WormholeChevronGeometry>,
    val detail: WormholeVisualDetail,
)

/** Pure screen-space geometry. Canonical system ordering makes redraws and reversed inputs identical. */
internal fun wormholeConnectionVisualGeometry(
    firstSystemId: Int,
    secondSystemId: Int,
    first: MapPoint,
    second: MapPoint,
): WormholeConnectionVisualGeometry {
    require(firstSystemId != secondSystemId) { "Wormhole visual endpoints must be different systems" }
    val canonicalFirst: MapPoint
    val canonicalSecond: MapPoint
    val lowerSystemId: Int
    val higherSystemId: Int
    if (firstSystemId < secondSystemId) {
        canonicalFirst = first
        canonicalSecond = second
        lowerSystemId = firstSystemId
        higherSystemId = secondSystemId
    } else {
        canonicalFirst = second
        canonicalSecond = first
        lowerSystemId = secondSystemId
        higherSystemId = firstSystemId
    }

    val deltaX = canonicalSecond.x - canonicalFirst.x
    val deltaY = canonicalSecond.y - canonicalFirst.y
    val distance = hypot(deltaX, deltaY)
    val center = midpoint(canonicalFirst, canonicalSecond)
    if (distance <= WORMHOLE_ZERO_DISTANCE_EPSILON_PX) {
        return WormholeConnectionVisualGeometry(
            lowerSystemId,
            higherSystemId,
            canonicalFirst,
            canonicalSecond,
            center,
            center,
            center,
            emptyList(),
            emptyList(),
            WormholeVisualDetail.SIMPLE,
        )
    }

    val unit = MapPoint(deltaX / distance, deltaY / distance)
    val normal = MapPoint(-unit.y, unit.x)
    val endpointInset = minOf(WORMHOLE_LINE_ENDPOINT_INSET_PX, distance / 3.0)
    val lineStart = canonicalFirst + unit * endpointInset
    val lineEnd = canonicalSecond - unit * endpointInset
    val usableLength = (distance - endpointInset * 2.0).coerceAtLeast(0.0)
    val chevronCount = when {
        usableLength >= WORMHOLE_FULL_PATTERN_MIN_LENGTH_PX -> 3
        usableLength >= WORMHOLE_REDUCED_PATTERN_MIN_LENGTH_PX -> 1
        else -> 0
    }
    val detail = when (chevronCount) {
        0 -> WormholeVisualDetail.SIMPLE
        1 -> WormholeVisualDetail.REDUCED
        else -> WormholeVisualDetail.FULL
    }
    val firstHalf = (0 until chevronCount).map { index ->
        val tip = center - unit * (
            WORMHOLE_CHEVRON_CENTER_GAP_PX +
                (chevronCount - 1 - index) * WORMHOLE_CHEVRON_SPACING_PX
            )
        chevron(tip, unit, normal)
    }
    val secondHalf = (0 until chevronCount).map { index ->
        val tip = center + unit * (
            WORMHOLE_CHEVRON_CENTER_GAP_PX + index * WORMHOLE_CHEVRON_SPACING_PX
            )
        chevron(tip, unit * -1.0, normal)
    }
    return WormholeConnectionVisualGeometry(
        lowerSystemId,
        higherSystemId,
        canonicalFirst,
        canonicalSecond,
        lineStart,
        lineEnd,
        center,
        firstHalf,
        secondHalf,
        detail,
    )
}

private fun chevron(
    tip: MapPoint,
    direction: MapPoint,
    normal: MapPoint,
): WormholeChevronGeometry {
    val tailCenter = tip - direction * WORMHOLE_CHEVRON_LENGTH_PX
    return WormholeChevronGeometry(
        tip = tip,
        firstTail = tailCenter + normal * WORMHOLE_CHEVRON_HALF_WIDTH_PX,
        secondTail = tailCenter - normal * WORMHOLE_CHEVRON_HALF_WIDTH_PX,
    )
}

private fun midpoint(first: MapPoint, second: MapPoint) = MapPoint(
    x = (first.x + second.x) / 2.0,
    y = (first.y + second.y) / 2.0,
)

internal const val WORMHOLE_ENDPOINT_OUTER_RADIUS_PX = 4.5f
internal const val WORMHOLE_ENDPOINT_INNER_RADIUS_PX = 2.25f
internal const val WORMHOLE_ENDPOINT_STROKE_WIDTH_PX = 1.1f
internal const val WORMHOLE_CENTER_MARKER_RADIUS_PX = 1.25f
internal const val WORMHOLE_LINE_ENDPOINT_INSET_PX = 6.0
internal const val WORMHOLE_REDUCED_PATTERN_MIN_LENGTH_PX = 42.0
internal const val WORMHOLE_FULL_PATTERN_MIN_LENGTH_PX = 100.0
internal const val WORMHOLE_CHEVRON_CENTER_GAP_PX = 11.0
internal const val WORMHOLE_CHEVRON_SPACING_PX = 11.0
internal const val WORMHOLE_CHEVRON_LENGTH_PX = 4.5
internal const val WORMHOLE_CHEVRON_HALF_WIDTH_PX = 2.75
private const val WORMHOLE_ZERO_DISTANCE_EPSILON_PX = 1e-6
