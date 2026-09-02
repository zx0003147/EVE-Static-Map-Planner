package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.RealXzProjection
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.preferences.MarkerPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

class MarkerMapPresentationTest {
    private val system = SolarSystem(
        id = 1,
        constellationId = 10,
        regionId = 100,
        name = "Jita",
        securityStatus = 0.9,
        securityClass = null,
        position = UniversePosition(0.0, 0.0, 0.0),
        schematicPosition = SchematicPosition(15.0, 20.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )
    private val scene = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(system),
            connections = emptyList(),
            regions = listOf(Region(100, "The Forge", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(10, 100, "Kimotoro", UniversePosition(0.0, 0.0, 0.0), null)),
        ),
        OfficialPosition2DProjection,
    )
    private val transform = MapTransform(MapViewport(scene.nodes.single().position, 2.0), MapSize(800.0, 600.0))

    @Test
    fun `visible marker projects with fixed screen offset and keeps marker style identity`() {
        val marker = Marker.temporary(1, MarkerDraft.create(name = "Trade", color = MarkerColor.BLUE))
        val systemCenter = transform.worldToScreen(scene.nodes.single().position)

        val presented = MarkerMapPresentationBuilder.build(
            scene,
            transform,
            visibleSystemIds = listOf(1),
            markersBySystemId = mapOf(1 to marker),
            preferences = MarkerPreferences.Defaults,
            semanticMode = SemanticLabelMode.SYSTEM,
            offsetPx = 10.0,
        ).single()

        assertEquals(systemCenter.x - 10.0, presented.screenCenter.x)
        assertEquals(systemCenter.y - 10.0, presented.screenCenter.y)
        assertEquals(marker, presented.marker)
        assertEquals("Trade", presented.visibleName)
        assertEquals(MarkerVisualStyle.OUTLINE_DIAMOND, presented.visualStyle)
    }

    @Test
    fun `saved marker resolves to an outer ring centered on its system`() {
        val marker = Marker.saved(1, MarkerDraft.create(), Instant.EPOCH, Instant.EPOCH)
        val systemCenter = transform.worldToScreen(scene.nodes.single().position)

        val presented = MarkerMapPresentationBuilder.build(
            scene, transform, listOf(1), mapOf(1 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.SYSTEM, 10.0,
        ).single()

        assertEquals(MarkerVisualStyle.OUTER_RING, presented.visualStyle)
        assertEquals(systemCenter, presented.screenCenter)
        assertTrue(presented.children.isEmpty())
    }

    @Test
    fun `system name keeps priority and colliding Local saved label moves below the ring`() {
        val marker = Marker.saved(
            1,
            MarkerDraft.create(name = "Local Test", color = MarkerColor.BLUE),
            Instant.EPOCH,
            Instant.EPOCH,
        )
        val presented = MarkerMapPresentationBuilder.build(
            scene, transform, listOf(1), mapOf(1 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.SYSTEM, 10.0,
            systemNameVisibleIds = setOf(1),
        ).single()
        val systemLabelSize = MapSize(44.0, 14.0)
        val layout = markerLabelLayout(presented, systemLabelSize)

        assertTrue(presented.systemNameVisible)
        assertTrue(layout.avoidedSystemName)
        assertFalse(layout.bounds.intersects(systemNameLabelBounds(presented.screenCenter, systemLabelSize)))
        assertTrue(layout.topLeft.y > presented.screenCenter.y)
    }

    @Test
    fun `Local saved label keeps its existing right position when measured bounds do not collide`() {
        val marker = Marker.saved(
            1,
            MarkerDraft.create(name = "Local Test", color = MarkerColor.BLUE),
            Instant.EPOCH,
            Instant.EPOCH,
        )
        val presented = MarkerMapPresentationBuilder.build(
            scene, transform, listOf(1), mapOf(1 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.SYSTEM, 10.0,
            systemNameVisibleIds = setOf(1),
        ).single()
        val layout = markerLabelLayout(presented, systemLabelSize = MapSize(4.0, 14.0))

        assertFalse(layout.avoidedSystemName)
        assertEquals(presented.screenCenter.x + 17.0, layout.topLeft.x)
        assertEquals(presented.screenCenter.y - 7.0, layout.topLeft.y)
    }

    @Test
    fun `Local saved label remains clear of system name across zoom changes`() {
        val marker = Marker.saved(
            1,
            MarkerDraft.create(name = "Local Test", color = MarkerColor.BLUE),
            Instant.EPOCH,
            Instant.EPOCH,
        )

        listOf(0.75, 2.0, 8.0).forEach { zoom ->
            val zoomedTransform = MapTransform(MapViewport(MapPoint(0.0, 0.0), zoom), MapSize(800.0, 600.0))
            val presented = MarkerMapPresentationBuilder.build(
                scene, zoomedTransform, listOf(1), mapOf(1 to marker), MarkerPreferences.Defaults,
                SemanticLabelMode.SYSTEM, 10.0,
                systemNameVisibleIds = setOf(1),
            ).single()
            val systemLabelSize = MapSize(44.0, 14.0)
            val layout = markerLabelLayout(presented, systemLabelSize)

            assertTrue(layout.avoidedSystemName, "Expected collision avoidance at zoom $zoom")
            assertFalse(
                layout.bounds.intersects(systemNameLabelBounds(presented.screenCenter, systemLabelSize)),
                "Local label overlaps the system name at zoom $zoom",
            )
        }
    }

    @Test
    fun `system name minimally clears a badge only when its measured bounds cross the label row`() {
        val center = MapPoint(100.0, 100.0)
        val labelSize = MapSize(44.0, 14.0)
        val intersectingBadge = ScreenBounds(112.0, 96.0, 122.0, 106.0)
        val lowerBadge = ScreenBounds(112.0, 110.0, 122.0, 120.0)

        val avoided = systemNameLabelLayout(
            center = center,
            labelSize = labelSize,
            existingOffsetPx = SYSTEM_LABEL_OFFSET_PX,
            visualObstacles = SystemNameVisualObstacles(screenBounds = listOf(intersectingBadge)),
            safetyGapPx = 4.0,
        )
        val unchanged = systemNameLabelLayout(
            center = center,
            labelSize = labelSize,
            existingOffsetPx = SYSTEM_LABEL_OFFSET_PX,
            visualObstacles = SystemNameVisualObstacles(screenBounds = listOf(lowerBadge)),
            safetyGapPx = 4.0,
        )

        assertEquals(126.0, avoided.topLeft.x)
        assertFalse(avoided.bounds.intersects(intersectingBadge))
        assertEquals(105.0, unchanged.topLeft.x)
    }

    @Test
    fun `expanded saved marker preserves repository child order in deterministic clockwise radial layout`() {
        val marker = Marker.saved(1, MarkerDraft.create(), Instant.EPOCH, Instant.EPOCH)
        val children = listOf(
            SavedMarkerChild.create("later", 1, SavedMarkerChildType.of("danger"), 8),
            SavedMarkerChild.create("first", 1, SavedMarkerChildType.of("staging"), 2),
            SavedMarkerChild.create("last", 1, SavedMarkerChildType.of("keepstar"), 20),
        )
        val center = transform.worldToScreen(scene.nodes.single().position)

        val presented = MarkerMapPresentationBuilder.build(
            scene, transform, listOf(1), mapOf(1 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.SYSTEM, 10.0,
            childrenByParentSystemId = mapOf(1 to children),
            expandedSystemIds = setOf(1),
            childOrbitRadiusPx = 40.0,
        ).single()

        assertEquals(children, presented.children.map { it.child })
        assertEquals(center.x, presented.children.first().screenCenter.x, absoluteTolerance = 0.0001)
        assertEquals(center.y - 40.0, presented.children.first().screenCenter.y, absoluteTolerance = 0.0001)
        assertEquals("Keepstar", presented.children.last().visual.label)
    }

    @Test
    fun `expansion truth table and spoke hit region keep child hover usable`() {
        assertTrue(!isSavedMarkerExpanded(false, false))
        assertTrue(isSavedMarkerExpanded(true, false))
        assertTrue(isSavedMarkerExpanded(false, true))
        assertTrue(isSavedMarkerExpanded(true, true))
        assertTrue(isSavedMarkerExpanded(false, false, isInteractionActive = true))

        val marker = Marker.saved(1, MarkerDraft.create(), Instant.EPOCH, Instant.EPOCH)
        val child = SavedMarkerChild.create("child", 1, SavedMarkerChildType.of("staging"), 0)
        val presented = MarkerMapPresentationBuilder.build(
            scene, transform, listOf(1), mapOf(1 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.SYSTEM, 10.0,
            childrenByParentSystemId = mapOf(1 to listOf(child)), expandedSystemIds = setOf(1), childOrbitRadiusPx = 40.0,
        ).single()
        val center = presented.screenCenter
        val childCenter = presented.children.single().screenCenter

        assertEquals(1, hitTestSavedMarkerInteraction(listOf(presented), MapPoint(center.x, center.y - 20.0), 12.0, 7.0)?.parentSystemId)
        assertEquals(child, hitTestSavedMarkerInteraction(listOf(presented), childCenter, 12.0, 7.0)?.child?.child)
        assertNull(hitTestSavedMarkerInteraction(listOf(presented), MapPoint(center.x + 25.0, center.y + 25.0), 12.0, 7.0))
    }

    @Test
    fun `official omitted marker has no fake coordinate while real projection displays it`() {
        val remote = system.copy(
            id = 2,
            name = "Remote",
            position = UniversePosition(8e18, 0.0, -1e19),
            schematicPosition = null,
        )
        val data = StaticMapData(
            systems = listOf(system, remote),
            connections = emptyList(),
            regions = listOf(Region(100, "The Forge", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(10, 100, "Kimotoro", UniversePosition(0.0, 0.0, 0.0), null)),
        )
        val official = MapSceneBuilder().build(data, OfficialPosition2DProjection)
        val real = MapSceneBuilder().build(data, RealXzProjection)
        val marker = Marker.temporary(2)
        val officialTransform = MapTransform(MapViewport(official.defaultFitBounds.center, 1.0), MapSize(800.0, 600.0))
        val realTransform = MapTransform(MapViewport(real.nodesById.getValue(2).position, 1.0), MapSize(800.0, 600.0))

        val omitted = MarkerMapPresentationBuilder.build(
            official, officialTransform, listOf(2), mapOf(2 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.SYSTEM, 10.0,
        )
        val visible = MarkerMapPresentationBuilder.build(
            real, realTransform, listOf(2), mapOf(2 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.SYSTEM, 10.0,
        )

        assertTrue(2 in official.omittedSystemIds)
        assertTrue(omitted.isEmpty())
        assertEquals(2, visible.single().marker.systemId)
    }

    @Test
    fun `marker visibility names and viewport culling are independent preferences`() {
        val marker = Marker.temporary(1, MarkerDraft.create(name = "Trade"))
        val hidden = MarkerMapPresentationBuilder.build(
            scene, transform, listOf(1), mapOf(1 to marker), MarkerPreferences(showMarkers = false),
            SemanticLabelMode.SYSTEM, 10.0,
        )
        val culled = MarkerMapPresentationBuilder.build(
            scene, transform, emptyList(), mapOf(1 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.SYSTEM, 10.0,
        )
        val constellationMode = MarkerMapPresentationBuilder.build(
            scene, transform, listOf(1), mapOf(1 to marker), MarkerPreferences.Defaults,
            SemanticLabelMode.CONSTELLATION, 10.0,
        ).single()

        assertTrue(hidden.isEmpty())
        assertTrue(culled.isEmpty())
        assertNull(constellationMode.visibleName)
    }

    private fun markerLabelLayout(
        marker: PresentedMapMarker,
        systemLabelSize: MapSize,
    ) = markerNameLabelLayout(
        marker = marker,
        markerLabelSize = MapSize(62.0, 14.0),
        systemLabelBounds = systemNameLabelBounds(marker.screenCenter, systemLabelSize),
        markerRadiusPx = 13.0,
        rightGapPx = 4.0,
        belowRingGapPx = 10.0,
        collisionPaddingPx = 2.0,
    )
}
