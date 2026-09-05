package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.Real3DCanonicalProjection
import dev.evestaticmapplanner.core.map.Real3DStaticGeometry
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Real3DFeatureOverlayTest {
    @Test
    fun `feature entries retain owner and use true XYZ anchor`() {
        val geometry = Real3DStaticGeometry.from(
            MapSceneBuilder().build(
                StaticMapData(
                    systems = listOf(system(1, 0.0), system(2, 8e15)),
                    connections = listOf(StargateConnection(1, 2)),
                    regions = listOf(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
                    constellations = listOf(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
                ),
                Real3DCanonicalProjection,
            ),
        )
        val state = OverlayState(
            listOf(
                OverlayLayerState(
                    OverlayProviderDescriptor("sovereignty.pack.overlay", "Sovereignty"),
                    OverlayLayer("sovereignty", "Sovereignty"),
                    listOf(
                        OverlayEntry("sovereignty", 1, "Alliance", value = style),
                        OverlayEntry("sovereignty", 2, "Alliance", value = style),
                    ),
                ),
            ),
        )

        val presentation = Real3DFeatureOverlayPresentationBuilder.build(state, geometry)

        assertEquals(2, presentation.entries.size)
        assertEquals(1, presentation.emblems.size)
        assertEquals(4.0, presentation.emblems.single().anchor.y, 1e-10)
        assertEquals("Sovereignty", presentation.legendSections.single().title)
        assertEquals(setOf(1, 2), presentation.sovereigntyColorsBySystemId.keys)
        assertEquals(Color(0xFF336699), presentation.sovereigntyColorsBySystemId.getValue(1))
        assertTrue(presentation.decorativeEntriesBySystemId.isEmpty())
        assertTrue(presentation.linkColorsBySystemPair.isEmpty())
    }

    private fun system(id: Int, y: Double) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 1,
        name = "S$id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(0.0, y, 0.0),
        schematicPosition = SchematicPosition(0.0, 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private companion object {
        const val style = "presentation-color:#FF336699;owner-key:alliance;" +
            "presentation-emblem-key:a;presentation-emblem-url:https://example.test/a.png"
    }
}
