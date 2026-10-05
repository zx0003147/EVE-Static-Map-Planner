package dev.evestaticmapplanner.core.forceprojection

import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalRecord
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalSnapshot
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalStatus
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.core.sovereignty.SovereigntySnapshot
import dev.evestaticmapplanner.core.sovereignty.SovereigntyStatus
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind
import dev.evestaticmapplanner.core.sovereignty.SystemOwnership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForceProjectionZoneSnapshotTest {
    @Test
    fun `system Zone uses sovereignty owner Alliance Capital and never current player Alliance`() {
        val capitalA = system(1, 0.0)
        val capitalB = system(2, 100.0)
        val target = system(3, 8.7)
        val snapshot = ForceProjectionZoneSnapshotBuilder.build(
            listOf(capitalA, capitalB, target),
            sovereignty(target.id to 10L),
            capitals(10L to capitalA.id, 20L to capitalB.id),
        )

        val result = snapshot.get(target.id)!!
        assertEquals(10L, result.ownerAllianceId)
        assertEquals(capitalA.id, result.capitalSystemId)
        assertEquals(8.7, result.distanceLy!!, 1e-12)
        assertEquals(ForceProjectionZone.ZONE_2, result.zone)
    }

    @Test
    fun `unclaimed and Alliance without Capital remain unknown and cannot route`() {
        val unclaimed = system(1, 0.0)
        val noCapital = system(2, 1.0)
        val snapshot = ForceProjectionZoneSnapshotBuilder.build(
            listOf(unclaimed, noCapital),
            sovereignty(noCapital.id to 10L),
            capitals(),
        )

        assertEquals(ForceProjectionZoneStatus.NO_SOVEREIGNTY_ALLIANCE, snapshot.get(unclaimed.id)?.status)
        assertEquals(ForceProjectionZoneStatus.CAPITAL_UNKNOWN, snapshot.get(noCapital.id)?.status)
        assertNull(snapshot.get(noCapital.id)?.zone)
        assertFalse(snapshot.isDestinationAllowed(unclaimed.id, 5))
        assertFalse(snapshot.isDestinationAllowed(noCapital.id, 5))
    }

    @Test
    fun `maximum Zone is an inclusive hard filter`() {
        val entries = (1..5).associateWith { id ->
            SystemForceProjectionZone(
                systemId = id,
                ownerAllianceId = 10,
                capitalSystemId = 1,
                distanceLy = id * 5.0,
                zone = ForceProjectionZone.entries[id - 1],
                status = ForceProjectionZoneStatus.AVAILABLE,
            )
        }
        val snapshot = ForceProjectionZoneSnapshot(entries)

        assertTrue((1..3).all { snapshot.isDestinationAllowed(it, 3) })
        assertTrue((4..5).all { !snapshot.isDestinationAllowed(it, 3) })
    }

    @Test
    fun `runtime cache derives an 8500-system universe into one immutable snapshot`() {
        val systems = (1..8_500).map { id -> system(id, id / 1_000.0) }
        val ownership = systems.associate { system ->
            system.id to SystemOwnership(
                systemId = system.id,
                ownerKind = SystemOwnerKind.ALLIANCE,
                allianceId = 10,
                sovereigntyStatus = SovereigntyStatus.CLAIMED,
                observedAtEpochMillis = 1,
                source = "test",
                freshness = SovereigntyFreshness.AVAILABLE,
            )
        }

        val snapshot = ForceProjectionZoneSnapshotBuilder.build(
            systems,
            SovereigntySnapshot(ownership, 1, "test", SovereigntyFreshness.AVAILABLE),
            capitals(10L to 1),
        )

        assertEquals(8_500, snapshot.systemsById.size)
        assertTrue(snapshot.systemsById.values.all { it.status == ForceProjectionZoneStatus.AVAILABLE })
    }

    private fun sovereignty(vararg owners: Pair<Int, Long>): SovereigntySnapshot {
        val records = owners.associate { (systemId, allianceId) ->
            systemId to SystemOwnership(
                systemId = systemId,
                ownerKind = SystemOwnerKind.ALLIANCE,
                allianceId = allianceId,
                sovereigntyStatus = SovereigntyStatus.CLAIMED,
                observedAtEpochMillis = 1,
                source = "test",
                freshness = SovereigntyFreshness.AVAILABLE,
            )
        }
        return SovereigntySnapshot(records, 1, "test", SovereigntyFreshness.AVAILABLE)
    }

    private fun capitals(vararg records: Pair<Long, Int>): AllianceCapitalSnapshot {
        val byAlliance = records.associate { (allianceId, systemId) ->
            allianceId to AllianceCapitalRecord(
                allianceId = allianceId,
                capitalSystemId = systemId,
                status = AllianceCapitalStatus.AVAILABLE,
                observedAtEpochMillis = 1,
                source = "test",
                freshness = SovereigntyFreshness.AVAILABLE,
            )
        }
        return AllianceCapitalSnapshot(byAlliance, 1, "test", SovereigntyFreshness.AVAILABLE)
    }

    private fun system(id: Int, xLy: Double) = SolarSystem(
        id = id,
        constellationId = 1,
        regionId = 1,
        name = "System $id",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(xLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR, 0.0, 0.0),
        schematicPosition = SchematicPosition(xLy, 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )
}
