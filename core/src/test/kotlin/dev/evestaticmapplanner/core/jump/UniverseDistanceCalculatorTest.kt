package dev.evestaticmapplanner.core.jump

import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UniverseDistanceCalculatorTest {
    @Test
    fun `uses exact EVE light year conversion and real XYZ distance`() {
        assertEquals(9_460_000_000_000_000.0, UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR)
        val origin = UniversePosition(0.0, 0.0, 0.0)
        val target = UniversePosition(3.0e15, 4.0e15, 0.0)

        assertEquals(5.0e15, UniverseDistanceCalculator.distanceMeters(origin, target))
        assertEquals(5.0e15 / UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR, UniverseDistanceCalculator.distanceLy(origin, target))
    }

    @Test
    fun `range boundary is inclusive without epsilon`() {
        val origin = UniversePosition(0.0, 0.0, 0.0)
        val boundary = UniversePosition(5.0 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR, 0.0, 0.0)
        val outside = UniversePosition(Math.nextUp(boundary.x), 0.0, 0.0)

        assertTrue(UniverseDistanceCalculator.isWithinRange(origin, boundary, 5.0))
        assertFalse(UniverseDistanceCalculator.isWithinRange(origin, outside, 5.0))
    }
}
