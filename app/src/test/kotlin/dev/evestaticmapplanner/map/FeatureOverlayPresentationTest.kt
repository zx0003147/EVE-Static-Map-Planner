package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayEntryVisibility
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayState
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureOverlayPresentationTest {
    @Test
    fun `presentation includes visible mapped entries and assigns rings without renderer callbacks`() {
        val provider = OverlayProviderDescriptor("test.provider", "Test Provider")
        val state = OverlayState(listOf(
            OverlayLayerState(provider, OverlayLayer("first", "First", priority = 0), listOf(
                OverlayEntry("first", SYSTEM_ID),
                OverlayEntry("first", 30_999_999),
            )),
            OverlayLayerState(provider, OverlayLayer("second", "Second", priority = 10), listOf(
                OverlayEntry("second", SYSTEM_ID),
                OverlayEntry("second", 30_000_002, visibility = OverlayEntryVisibility.HIDDEN),
            )),
        ))

        val presentation = FeatureOverlayPresentationBuilder.build(state, scene())

        assertEquals(
            listOf(
                PresentedFeatureOverlayEntry(SYSTEM_ID, 0),
                PresentedFeatureOverlayEntry(SYSTEM_ID, 1),
            ),
            presentation.entries,
        )
    }

    @Test
    fun `presentation converts generic ring metadata and builds reusable legend`() {
        val provider = OverlayProviderDescriptor("test.provider", "Test Provider")
        val state = OverlayState(listOf(
            OverlayLayerState(provider, OverlayLayer("sovereignty", "Sovereignty"), listOf(
                OverlayEntry(
                    "sovereignty",
                    SYSTEM_ID,
                    title = "Goonswarm Federation",
                    value = "ring-color:#B3F2C94C",
                ),
                OverlayEntry(
                    "sovereignty",
                    30_000_002,
                    title = "Fraternity",
                    value = "ring-color:#B34D9DE0",
                ),
            )),
        ))

        val presentation = FeatureOverlayPresentationBuilder.build(state, scene())

        assertEquals(Color(0xB3F2C94C), presentation.entries.first { it.systemId == SYSTEM_ID }.color)
        assertEquals(
            FeatureOverlayLegendSection(
                "Sovereignty",
                listOf(
                    FeatureOverlayLegendEntry("Fraternity", Color(0xB34D9DE0)),
                    FeatureOverlayLegendEntry("Goonswarm Federation", Color(0xB3F2C94C)),
                ),
            ),
            presentation.legendSections.single(),
        )
    }

    private fun scene() = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(system(SYSTEM_ID), system(30_000_002)),
            connections = emptyList(),
            regions = listOf(Region(100, "Region", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(10, 100, "Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
        ),
        OfficialPosition2DProjection,
    )

    private fun system(id: Int) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 100,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(id.toDouble(), 0.0, 0.0),
        schematicPosition = SchematicPosition(id.toDouble(), 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private companion object {
        const val SYSTEM_ID = 30_000_001
    }
}
