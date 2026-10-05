package dev.evestaticmapplanner.core.forceprojection

import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals

class ForceProjectionZoneResolverTest {
    @Test
    fun `zone boundaries are closed on their upper limit without floating gaps`() {
        val epsilon = Math.ulp(20.0)
        val cases = listOf(
            0.0 to ForceProjectionZone.ZONE_1,
            5.0 to ForceProjectionZone.ZONE_1,
            (5.0 + epsilon) to ForceProjectionZone.ZONE_2,
            10.0 to ForceProjectionZone.ZONE_2,
            (10.0 + epsilon) to ForceProjectionZone.ZONE_3,
            15.0 to ForceProjectionZone.ZONE_3,
            (15.0 + epsilon) to ForceProjectionZone.ZONE_4,
            20.0 to ForceProjectionZone.ZONE_4,
            (20.0 + epsilon) to ForceProjectionZone.ZONE_5,
        )

        cases.forEach { (distance, expected) ->
            assertEquals(expected, ForceProjectionZone.fromDistanceLy(distance), "distance=$distance")
        }
    }

    @Test
    fun `resolver integrates real universe XYZ distance with zone classification`() {
        val capital = system(30_000_001, 0.0, 0.0, 0.0)
        val target = system(30_000_002, 6.0, 8.0, 0.0)
        val resolver = ForceProjectionZoneResolver(listOf(capital, target))

        val result = resolver.resolve(99, capital.id, target.id)

        assertEquals(10.0, result.distanceLy, 1e-12)
        assertEquals(ForceProjectionZone.ZONE_2, result.zone)
        assertEquals(capital.id, result.capitalSystemId)
    }

    private fun system(id: Int, xLy: Double, yLy: Double, zLy: Double) = SolarSystem(
        id = id,
        constellationId = 20_000_001,
        regionId = 10_000_001,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(
            xLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
            yLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
            zLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
        ),
        schematicPosition = SchematicPosition(0.0, 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )
}
