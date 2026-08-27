package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.math.hypot

internal sealed interface MapConnectionGeometry {
    val start: MapPoint
    val end: MapPoint
}

internal data class StraightMapConnectionGeometry(
    override val start: MapPoint,
    override val end: MapPoint,
) : MapConnectionGeometry

internal data class QuadraticMapConnectionGeometry(
    override val start: MapPoint,
    val control: MapPoint,
    override val end: MapPoint,
    val controlOffset: Double,
) : MapConnectionGeometry

internal fun stargateConnectionGeometry(
    start: MapPoint,
    end: MapPoint,
): StraightMapConnectionGeometry = StraightMapConnectionGeometry(start, end)

internal fun activeRouteConnectionGeometry(
    firstSystemId: Int,
    secondSystemId: Int,
    edgeType: RouteEdgeType,
    start: MapPoint,
    end: MapPoint,
): MapConnectionGeometry = if (edgeType == RouteEdgeType.ANSIBLEX) {
    ansiblexConnectionGeometry(firstSystemId, secondSystemId, start, end)
} else {
    StraightMapConnectionGeometry(start, end)
}

internal fun ansiblexConnectionGeometry(
    firstSystemId: Int,
    secondSystemId: Int,
    first: MapPoint,
    second: MapPoint,
): QuadraticMapConnectionGeometry {
    require(firstSystemId != secondSystemId) { "Ansiblex curve endpoints must be different systems" }

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
    val midpoint = MapPoint(
        x = (first.x + second.x) / 2.0,
        y = (first.y + second.y) / 2.0,
    )
    if (distance <= ANSIBLEX_CURVE_ZERO_DISTANCE_EPSILON_PX) {
        return QuadraticMapConnectionGeometry(first, midpoint, second, controlOffset = 0.0)
    }

    val controlOffset = (distance * ANSIBLEX_CURVE_FACTOR)
        .coerceIn(ANSIBLEX_CURVE_MIN_OFFSET_PX, ANSIBLEX_CURVE_MAX_OFFSET_PX)
    val bendSign = if ((((lowerSystemId.toLong() * 31L) xor higherSystemId.toLong()) and 1L) == 0L) {
        1.0
    } else {
        -1.0
    }
    val normalX = -deltaY / distance
    val normalY = deltaX / distance
    val control = MapPoint(
        x = midpoint.x + normalX * controlOffset * bendSign,
        y = midpoint.y + normalY * controlOffset * bendSign,
    )
    return QuadraticMapConnectionGeometry(first, control, second, controlOffset)
}

internal const val ANSIBLEX_CURVE_FACTOR = 0.22
internal const val ANSIBLEX_CURVE_MIN_OFFSET_PX = 12.0
internal const val ANSIBLEX_CURVE_MAX_OFFSET_PX = 64.0
private const val ANSIBLEX_CURVE_ZERO_DISTANCE_EPSILON_PX = 1e-6
