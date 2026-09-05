package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.preferences.MapDisplayPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SemanticZoomPolicyTest {
    @Test
    fun `real 3D uses independent scale thresholds`() {
        val preferences = MapDisplayPreferences(
            constellationZoomThreshold = 20.0,
            systemZoomThreshold = 30.0,
            real3DConstellationScaleThreshold = 1.5,
            real3DSystemScaleThreshold = 4.0,
        )

        assertEquals(SemanticLabelMode.REGION_ONLY, SemanticZoomPolicy.initialReal3DMode(1.49, preferences))
        assertEquals(SemanticLabelMode.CONSTELLATION, SemanticZoomPolicy.initialReal3DMode(1.5, preferences))
        assertEquals(SemanticLabelMode.SYSTEM, SemanticZoomPolicy.initialReal3DMode(4.0, preferences))
        assertEquals(SemanticLabelMode.REGION_ONLY, SemanticZoomPolicy.initialMode(10.0, preferences))
    }

    @Test
    fun `custom thresholds control semantic entry modes`() {
        val preferences = MapDisplayPreferences(
            constellationZoomThreshold = 3.5,
            systemZoomThreshold = 8.0,
        )

        assertEquals(SemanticLabelMode.REGION_ONLY, SemanticZoomPolicy.initialMode(3.49, preferences))
        assertEquals(SemanticLabelMode.CONSTELLATION, SemanticZoomPolicy.initialMode(3.50, preferences))
        assertEquals(SemanticLabelMode.CONSTELLATION, SemanticZoomPolicy.initialMode(7.99, preferences))
        assertEquals(SemanticLabelMode.SYSTEM, SemanticZoomPolicy.initialMode(8.00, preferences))
    }

    @Test
    fun `thresholds must be positive and ordered`() {
        assertFailsWith<IllegalArgumentException> {
            MapDisplayPreferences(constellationZoomThreshold = 0.0, systemZoomThreshold = 6.0)
        }
        assertFailsWith<IllegalArgumentException> {
            MapDisplayPreferences(constellationZoomThreshold = 6.0, systemZoomThreshold = 6.0)
        }
        assertFailsWith<IllegalArgumentException> {
            MapDisplayPreferences(constellationZoomThreshold = 7.0, systemZoomThreshold = 6.0)
        }
    }

    @Test
    fun `automatic hysteresis derives return boundaries from user thresholds`() {
        val preferences = MapDisplayPreferences(
            constellationZoomThreshold = 4.0,
            systemZoomThreshold = 10.0,
        )
        val constellationReturn = SemanticZoomPolicy.constellationReturnZoom(preferences)
        val systemReturn = SemanticZoomPolicy.systemReturnZoom(preferences)

        assertEquals(3.32, constellationReturn, absoluteTolerance = 0.000_001)
        assertEquals(8.3, systemReturn, absoluteTolerance = 0.000_001)
        assertEquals(
            SemanticLabelMode.CONSTELLATION,
            SemanticZoomPolicy.transition(SemanticLabelMode.CONSTELLATION, constellationReturn + 0.01, preferences),
        )
        assertEquals(
            SemanticLabelMode.REGION_ONLY,
            SemanticZoomPolicy.transition(SemanticLabelMode.CONSTELLATION, constellationReturn, preferences),
        )
        assertEquals(
            SemanticLabelMode.SYSTEM,
            SemanticZoomPolicy.transition(SemanticLabelMode.SYSTEM, systemReturn + 0.01, preferences),
        )
        assertEquals(
            SemanticLabelMode.CONSTELLATION,
            SemanticZoomPolicy.transition(SemanticLabelMode.SYSTEM, systemReturn, preferences),
        )
    }

    @Test
    fun `close thresholds preserve an ordered return band`() {
        val preferences = MapDisplayPreferences(
            constellationZoomThreshold = 5.9,
            systemZoomThreshold = 6.0,
        )

        assertEquals(5.9, SemanticZoomPolicy.systemReturnZoom(preferences))
        assertEquals(
            SemanticLabelMode.CONSTELLATION,
            SemanticZoomPolicy.transition(SemanticLabelMode.SYSTEM, 5.9, preferences),
        )
    }

    @Test
    fun `large zoom changes may cross more than one band`() {
        val preferences = MapDisplayPreferences(
            constellationZoomThreshold = 4.0,
            systemZoomThreshold = 10.0,
        )
        assertEquals(
            SemanticLabelMode.SYSTEM,
            SemanticZoomPolicy.transition(SemanticLabelMode.REGION_ONLY, 10.0, preferences),
        )
        assertEquals(
            SemanticLabelMode.REGION_ONLY,
            SemanticZoomPolicy.transition(SemanticLabelMode.SYSTEM, 3.0, preferences),
        )
    }

    @Test
    fun `region role remains active in every semantic mode`() {
        assertEquals(RegionLabelRole.PRIMARY, SemanticLabelMode.REGION_ONLY.regionLabelRole)
        assertEquals(RegionLabelRole.BACKGROUND, SemanticLabelMode.CONSTELLATION.regionLabelRole)
        assertEquals(RegionLabelRole.BACKGROUND, SemanticLabelMode.SYSTEM.regionLabelRole)
    }
}
