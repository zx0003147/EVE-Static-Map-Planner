package dev.evestaticmapplanner.core.map

import kotlin.math.max

/** Deterministic Weiszfeld geometric median for finite 3D map coordinates. */
object GeometricMedian3D {
    fun calculate(points: Collection<MapPoint3>): MapPoint3 {
        require(points.isNotEmpty()) { "A 3D geometric median requires at least one point" }
        val ordered = points.sortedWith(compareBy<MapPoint3> { it.x }.thenBy { it.y }.thenBy { it.z })
        if (ordered.size == 1) return ordered.single()
        if (ordered.size == 2) return (ordered[0] + ordered[1]) * 0.5
        var estimate = centroid(ordered)
        val scale = ordered.maxOf { (it - estimate).length() }.coerceAtLeast(1.0)
        val convergence = max(MINIMUM_CONVERGENCE_DISTANCE, scale * RELATIVE_CONVERGENCE_DISTANCE)
        val coincidence = max(MINIMUM_COINCIDENCE_DISTANCE, scale * RELATIVE_COINCIDENCE_DISTANCE)

        repeat(MAX_ITERATIONS) {
            var unitSum = MapPoint3.Zero
            var inverseDistanceSum = 0.0
            var coincidentCount = 0
            ordered.forEach { point ->
                val delta = point - estimate
                val distance = delta.length()
                if (distance <= coincidence) {
                    coincidentCount++
                } else {
                    unitSum += delta / distance
                    inverseDistanceSum += 1.0 / distance
                }
            }
            if (inverseDistanceSum == 0.0) return estimate
            val target = estimate + unitSum / inverseDistanceSum
            val next = if (coincidentCount == 0) {
                target
            } else {
                val residual = unitSum.length()
                if (residual <= coincidentCount.toDouble()) return estimate
                val retainedWeight = coincidentCount / residual
                estimate * retainedWeight + target * (1.0 - retainedWeight)
            }
            if ((next - estimate).length() <= convergence) return next
            estimate = next
        }
        return estimate
    }

    private fun centroid(points: List<MapPoint3>): MapPoint3 {
        val origin = points.first()
        var offsets = MapPoint3.Zero
        points.forEach { offsets += it - origin }
        return origin + offsets / points.size.toDouble()
    }
}

private const val RELATIVE_CONVERGENCE_DISTANCE = 1e-10
private const val MINIMUM_CONVERGENCE_DISTANCE = 1e-12
private const val RELATIVE_COINCIDENCE_DISTANCE = 1e-12
private const val MINIMUM_COINCIDENCE_DISTANCE = 1e-14
private const val MAX_ITERATIONS = 256
