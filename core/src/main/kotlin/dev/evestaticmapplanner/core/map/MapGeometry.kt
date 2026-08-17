package dev.evestaticmapplanner.core.map

import kotlin.math.max
import kotlin.math.min

data class MapPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Map coordinates must be finite" }
    }

    operator fun plus(other: MapPoint) = MapPoint(x + other.x, y + other.y)

    operator fun minus(other: MapPoint) = MapPoint(x - other.x, y - other.y)

    operator fun times(factor: Double) = MapPoint(x * factor, y * factor)

    fun distanceSquaredTo(other: MapPoint): Double {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }
}

data class MapSize(
    val width: Double,
    val height: Double,
) {
    init {
        require(width.isFinite() && height.isFinite() && width >= 0.0 && height >= 0.0) {
            "Map size must be finite and non-negative"
        }
    }

    val isEmpty: Boolean get() = width == 0.0 || height == 0.0
    val center: MapPoint get() = MapPoint(width / 2.0, height / 2.0)
}

data class MapBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    init {
        require(minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
            "Map bounds must be finite"
        }
        require(minX <= maxX && minY <= maxY) { "Map bounds must be ordered" }
    }

    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val center: MapPoint get() = MapPoint((minX + maxX) / 2.0, (minY + maxY) / 2.0)

    fun contains(point: MapPoint): Boolean =
        point.x in minX..maxX && point.y in minY..maxY

    fun intersects(other: MapBounds): Boolean =
        maxX >= other.minX && minX <= other.maxX && maxY >= other.minY && minY <= other.maxY

    fun expanded(amount: Double): MapBounds {
        require(amount.isFinite() && amount >= 0.0)
        return MapBounds(minX - amount, minY - amount, maxX + amount, maxY + amount)
    }

    companion object {
        fun fromPoints(points: Iterable<MapPoint>): MapBounds {
            val iterator = points.iterator()
            require(iterator.hasNext()) { "Cannot create bounds from no points" }
            val first = iterator.next()
            var minX = first.x
            var minY = first.y
            var maxX = first.x
            var maxY = first.y
            while (iterator.hasNext()) {
                val point = iterator.next()
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
            }
            return MapBounds(minX, minY, maxX, maxY)
        }

        fun between(first: MapPoint, second: MapPoint): MapBounds = MapBounds(
            minX = min(first.x, second.x),
            minY = min(first.y, second.y),
            maxX = max(first.x, second.x),
            maxY = max(first.y, second.y),
        )
    }
}
