package dev.evestaticmapplanner.core.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SystemOwnershipTest {
    @Test
    fun `snapshot resolves alliance ownership by system ID`() {
        val ownership = allianceOwnership(allianceId = 99, allianceName = "Alliance")
        val snapshot = snapshotOf(ownership)

        assertEquals(ownership, snapshot.getOwnership(30_000_001))
        assertNull(snapshot.getOwnership(30_000_002))
    }

    @Test
    fun `unclaimed and unknown remain typed without invented IDs`() {
        val unclaimed = ownership(SystemOwnerKind.UNCLAIMED, SovereigntyStatus.UNCLAIMED)
        val unknown = ownership(SystemOwnerKind.UNKNOWN, SovereigntyStatus.UNKNOWN, systemId = 30_000_002)

        assertNull(unclaimed.allianceId)
        assertNull(unclaimed.corporationId)
        assertNull(unclaimed.factionId)
        assertNull(unknown.allianceId)
        assertEquals(SystemOwnerKind.UNCLAIMED, unclaimed.ownerKind)
        assertEquals(SystemOwnerKind.UNKNOWN, unknown.ownerKind)
    }

    @Test
    fun `display name changes do not change stable owner identity`() {
        val before = allianceOwnership(allianceId = 99, allianceName = "Old Name")
        val after = allianceOwnership(allianceId = 99, allianceName = "New Name")

        assertEquals(before.allianceId, after.allianceId)
        assertNotEquals(before.allianceName, after.allianceName)
    }

    @Test
    fun `same display name does not merge different stable owners`() {
        val first = allianceOwnership(allianceId = 99, allianceName = "Same Name")
        val second = allianceOwnership(allianceId = 100, allianceName = "Same Name")

        assertNotEquals(first.allianceId, second.allianceId)
        assertEquals(first.allianceName, second.allianceName)
    }

    private fun allianceOwnership(allianceId: Long, allianceName: String) = SystemOwnership(
        systemId = 30_000_001,
        ownerKind = SystemOwnerKind.ALLIANCE,
        allianceId = allianceId,
        allianceName = allianceName,
        sovereigntyStatus = SovereigntyStatus.CLAIMED,
        observedAtEpochMillis = 1_000,
        source = "test",
        freshness = SovereigntyFreshness.AVAILABLE,
    )

    private fun ownership(
        ownerKind: SystemOwnerKind,
        status: SovereigntyStatus,
        systemId: Int = 30_000_001,
    ) = SystemOwnership(
        systemId = systemId,
        ownerKind = ownerKind,
        sovereigntyStatus = status,
        observedAtEpochMillis = 1_000,
        source = "test",
        freshness = SovereigntyFreshness.AVAILABLE,
    )

    private fun snapshotOf(vararg ownership: SystemOwnership) = SovereigntySnapshot(
        systemsById = ownership.associateBy(SystemOwnership::systemId),
        observedAtEpochMillis = 1_000,
        source = "test",
        freshness = SovereigntyFreshness.AVAILABLE,
    )
}
