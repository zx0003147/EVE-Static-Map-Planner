package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.core.sovereignty.SovereigntySnapshot
import dev.evestaticmapplanner.core.sovereignty.SovereigntyStatus
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind
import dev.evestaticmapplanner.core.sovereignty.SystemOwnership
import dev.evestaticmapplanner.feature.api.OverlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SovereigntyMapPresentationTest {
    @Test
    fun `2D territory is built from Core sovereignty without a Feature Overlay provider`() {
        val sovereignty = SovereigntyMapPresentationBuilder.build(snapshot("Alliance", SovereigntyFreshness.AVAILABLE))

        val presentation = FeatureOverlayPresentationBuilder.build(
            state = OverlayState(emptyList()),
            scene = scene(),
            sovereignty = sovereignty,
        )

        assertEquals(setOf(1, 2), presentation.territories.flatMap { it.systemIds }.toSet())
        assertTrue(presentation.territories.all { it.ownerLabel == "Alliance" })
        assertEquals("Sovereignty", presentation.legendSections.single().title)
    }

    @Test
    fun `stable Alliance ID keeps map identity color and emblem when display name changes`() {
        val old = SovereigntyMapPresentationBuilder.build(snapshot("Old Name", SovereigntyFreshness.AVAILABLE))
        val renamed = SovereigntyMapPresentationBuilder.build(snapshot("New Name", SovereigntyFreshness.AVAILABLE))

        assertEquals(old.entries.map { it.ownerKey }, renamed.entries.map { it.ownerKey })
        assertEquals(old.entries.map { it.color }, renamed.entries.map { it.color })
        assertEquals(old.entries.map { it.emblemReference }, renamed.entries.map { it.emblemReference })
        assertEquals(Color(0xCCF2C94C), old.entries.first().color)
    }

    @Test
    fun `generated Alliance color preserves the former Pack algorithm`() {
        val presentation = SovereigntyMapPresentationBuilder.build(
            snapshot("Generated Alliance", SovereigntyFreshness.AVAILABLE, allianceId = 99_100_001L),
        )

        assertEquals(Color(0xCCC762EA), presentation.entries.first().color)
    }

    @Test
    fun `stale keeps last-good map entries while unavailable hides sovereignty`() {
        val stale = SovereigntyMapPresentationBuilder.build(snapshot("Alliance", SovereigntyFreshness.STALE))
        val unavailable = SovereigntyMapPresentationBuilder.build(SovereigntySnapshot.unavailable("Pack removed"))

        assertEquals(setOf(1, 2), stale.entries.map { it.systemId }.toSet())
        assertEquals(SovereigntyFreshness.STALE, stale.freshness)
        assertTrue(unavailable.entries.isEmpty())
        assertEquals(SovereigntyFreshness.UNAVAILABLE, unavailable.freshness)
    }

    private fun snapshot(
        name: String,
        freshness: SovereigntyFreshness,
        allianceId: Long = 1_354_830_081L,
    ): SovereigntySnapshot {
        val observedAt = 1_700_000_000_000L
        val ownership = (1..2).associateWith { systemId ->
            SystemOwnership(
                systemId = systemId,
                ownerKind = SystemOwnerKind.ALLIANCE,
                allianceId = allianceId,
                allianceName = name,
                sovereigntyStatus = SovereigntyStatus.CLAIMED,
                observedAtEpochMillis = observedAt,
                source = "Sovereignty Pack",
                freshness = freshness,
            )
        }
        return SovereigntySnapshot(
            systemsById = ownership,
            observedAtEpochMillis = observedAt,
            source = "Sovereignty Pack",
            freshness = freshness,
        )
    }

    private fun scene() = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(system(1, 0.0), system(2, 4.0)),
            connections = emptyList(),
        ),
        OfficialPosition2DProjection,
    )

    private fun system(id: Int, x: Double) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 1,
        name = "S$id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(x * UNIT, 0.0, 0.0),
        schematicPosition = SchematicPosition(x * UNIT, 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private companion object {
        const val UNIT = 1_000_000_000_000_000.0
    }
}
