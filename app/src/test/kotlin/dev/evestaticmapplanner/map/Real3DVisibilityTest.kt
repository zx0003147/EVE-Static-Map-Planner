package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.Real3DCanonicalProjection
import dev.evestaticmapplanner.core.map.Real3DStaticGeometry
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Real3DVisibilityTest {
    private val geometry = Real3DStaticGeometry.from(
        MapSceneBuilder().build(
            StaticMapData(
                systems = listOf(
                    system(1, regionId = 10, constellationId = 101),
                    system(2, regionId = 10, constellationId = 101),
                    system(3, regionId = 20, constellationId = 201),
                    system(4, regionId = 20, constellationId = 201),
                    system(5, regionId = 30, constellationId = 301),
                ),
                connections = listOf(
                    StargateConnection(1, 2),
                    StargateConnection(2, 3),
                    StargateConnection(3, 4),
                    StargateConnection(4, 5),
                ),
                regions = listOf(region(10), region(20), region(30)),
                constellations = listOf(constellation(101, 10), constellation(201, 20), constellation(301, 30)),
            ),
            Real3DCanonicalProjection,
        ),
    )

    @Test
    fun `filter hides normal stargates without a focused system`() {
        assertEquals(emptySet(), Real3DStargateVisibility.visibleConnectionKeys(geometry, null, true))
    }

    @Test
    fun `filter includes focused and directly adjacent regions without expanding a second hop`() {
        assertEquals(
            setOf(pair(1, 2), pair(2, 3), pair(3, 4)),
            Real3DStargateVisibility.visibleConnectionKeys(geometry, focusedSystemId = 1, filteringEnabled = true),
        )
    }

    @Test
    fun `disabled filter leaves the complete stargate network visible`() {
        assertNull(Real3DStargateVisibility.visibleConnectionKeys(geometry, focusedSystemId = null, filteringEnabled = false))
    }

    private fun pair(first: Int, second: Int) = real3DSystemPairKey(first, second)

    private fun system(id: Int, regionId: Int, constellationId: Int) = SolarSystem(
        id = id,
        constellationId = constellationId,
        regionId = regionId,
        name = "S$id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(id * 1e15, 0.0, 0.0),
        schematicPosition = SchematicPosition(id.toDouble(), 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private fun region(id: Int) = Region(id, "R$id", UniversePosition(0.0, 0.0, 0.0), null)

    private fun constellation(id: Int, regionId: Int) =
        Constellation(id, regionId, "C$id", UniversePosition(0.0, 0.0, 0.0), null)
}
