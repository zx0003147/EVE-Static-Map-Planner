package dev.evestaticmapplanner.feature.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SovereigntyApiTest {
    @Test
    fun `typed snapshot preserves stable IDs separately from display metadata`() {
        val ownership = SystemOwnershipDto(
            systemId = 30_000_001,
            ownerKind = SovereigntyOwnerKindDto.ALLIANCE,
            allianceId = 99,
            allianceName = "Display Name",
            corporationId = 100,
            corporationName = "Corporation",
            sovereigntyStatus = SovereigntyStatusDto.CLAIMED,
        )
        val snapshot = SovereigntySnapshotDto(
            systems = listOf(ownership),
            observedAt = Instant.parse("2026-09-24T00:00:00Z"),
            source = "test",
            freshness = SovereigntyFreshnessDto.AVAILABLE,
        )

        assertEquals(99, snapshot.systems.single().allianceId)
        assertEquals("Display Name", snapshot.systems.single().allianceName)
        assertEquals(100, snapshot.systems.single().corporationId)
    }

    @Test
    fun `snapshot rejects duplicate systems`() {
        val ownership = SystemOwnershipDto(
            30_000_001,
            SovereigntyOwnerKindDto.UNCLAIMED,
            sovereigntyStatus = SovereigntyStatusDto.UNCLAIMED,
        )

        assertFailsWith<IllegalArgumentException> {
            SovereigntySnapshotDto(
                systems = listOf(ownership, ownership),
                source = "test",
                freshness = SovereigntyFreshnessDto.AVAILABLE,
            )
        }
    }

    @Test
    fun `unavailable snapshot cannot publish ownership`() {
        val ownership = SystemOwnershipDto(
            30_000_001,
            SovereigntyOwnerKindDto.UNKNOWN,
        )

        assertFailsWith<IllegalArgumentException> {
            SovereigntySnapshotDto(
                systems = listOf(ownership),
                source = "test",
                freshness = SovereigntyFreshnessDto.UNAVAILABLE,
            )
        }
    }
}
