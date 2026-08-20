package dev.evestaticmapplanner.core.map

import kotlin.math.hypot
import kotlin.math.max

/** Computes a deterministic geometric median for finite projected map points. */
object GeometricMedian {
    fun calculate(points: Collection<MapPoint>): MapPoint {
        require(points.isNotEmpty()) { "A geometric median requires at least one point" }
        val ordered = points.sortedWith(compareBy<MapPoint> { it.x }.thenBy { it.y })
        if (ordered.size == 1) return ordered.single()
        if (ordered.size == 2) return midpoint(ordered[0], ordered[1])

        val bounds = MapBounds.fromPoints(ordered)
        val scale = max(max(bounds.width, bounds.height), MINIMUM_SCALE)
        val convergenceThreshold = max(MINIMUM_CONVERGENCE_DISTANCE, scale * RELATIVE_CONVERGENCE_DISTANCE)
        val coincidenceThreshold = max(MINIMUM_COINCIDENCE_DISTANCE, scale * RELATIVE_COINCIDENCE_DISTANCE)
        var estimate = centroid(ordered)

        repeat(MAX_ITERATIONS) {
            var unitSumX = 0.0
            var unitSumY = 0.0
            var inverseDistanceSum = 0.0
            var coincidentCount = 0

            ordered.forEach { point ->
                val deltaX = point.x - estimate.x
                val deltaY = point.y - estimate.y
                val distance = hypot(deltaX, deltaY)
                if (distance <= coincidenceThreshold) {
                    coincidentCount++
                } else {
                    unitSumX += deltaX / distance
                    unitSumY += deltaY / distance
                    inverseDistanceSum += 1.0 / distance
                }
            }

            if (inverseDistanceSum == 0.0) return estimate
            val target = MapPoint(
                x = estimate.x + unitSumX / inverseDistanceSum,
                y = estimate.y + unitSumY / inverseDistanceSum,
            )
            val next = if (coincidentCount == 0) {
                target
            } else {
                val residual = hypot(unitSumX, unitSumY)
                if (residual <= coincidentCount.toDouble()) return estimate
                val retainedEstimateWeight = coincidentCount / residual
                MapPoint(
                    x = estimate.x * retainedEstimateWeight + target.x * (1.0 - retainedEstimateWeight),
                    y = estimate.y * retainedEstimateWeight + target.y * (1.0 - retainedEstimateWeight),
                )
            }
            if (hypot(next.x - estimate.x, next.y - estimate.y) <= convergenceThreshold) return next
            estimate = next
        }
        return estimate
    }

    private fun centroid(points: List<MapPoint>): MapPoint {
        val origin = points.first()
        var offsetX = 0.0
        var offsetY = 0.0
        points.forEach { point ->
            offsetX += point.x - origin.x
            offsetY += point.y - origin.y
        }
        return MapPoint(
            x = origin.x + offsetX / points.size,
            y = origin.y + offsetY / points.size,
        )
    }

    private fun midpoint(first: MapPoint, second: MapPoint): MapPoint = MapPoint(
        x = first.x + (second.x - first.x) / 2.0,
        y = first.y + (second.y - first.y) / 2.0,
    )
}

// Relative thresholds scale convergence to each projected group while the minima cover coincident map points.
private const val RELATIVE_CONVERGENCE_DISTANCE = 1e-10
private const val MINIMUM_CONVERGENCE_DISTANCE = 1e-12
private const val RELATIVE_COINCIDENCE_DISTANCE = 1e-12
private const val MINIMUM_COINCIDENCE_DISTANCE = 1e-14
private const val MINIMUM_SCALE = 1.0
private const val MAX_ITERATIONS = 256
