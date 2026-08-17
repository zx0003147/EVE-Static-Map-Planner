package dev.evestaticmapplanner.core.jump

import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.math.sqrt

object UniverseDistanceCalculator {
    const val METERS_PER_EVE_LIGHT_YEAR: Double = 9_460_000_000_000_000.0

    fun distanceMeters(first: UniversePosition, second: UniversePosition): Double {
        val dx = first.x - second.x
        val dy = first.y - second.y
        val dz = first.z - second.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun distanceLy(first: UniversePosition, second: UniversePosition): Double =
        distanceMeters(first, second) / METERS_PER_EVE_LIGHT_YEAR

    fun isWithinRange(
        first: UniversePosition,
        second: UniversePosition,
        maxRangeLy: Double,
    ): Boolean {
        require(maxRangeLy.isFinite() && maxRangeLy >= 0.0) { "Jump range must be finite and non-negative" }
        return distanceMeters(first, second) <= maxRangeLy * METERS_PER_EVE_LIGHT_YEAR
    }
}
