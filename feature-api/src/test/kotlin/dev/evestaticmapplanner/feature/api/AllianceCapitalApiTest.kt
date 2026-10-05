package dev.evestaticmapplanner.feature.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AllianceCapitalApiTest {
    @Test
    fun `snapshot preserves conflicting capital observations for Host validation`() {
        val snapshot = AllianceCapitalSnapshotDto(
            records = listOf(
                AllianceCapitalRecordDto(99, 30_000_001),
                AllianceCapitalRecordDto(99, 30_000_002),
            ),
            observedAt = Instant.parse("2026-10-05T00:00:00Z"),
            source = "Sovereignty Pack / PUBLIC_ESI",
            freshness = SovereigntyFreshnessDto.AVAILABLE,
        )

        assertEquals(listOf(30_000_001, 30_000_002), snapshot.records.map { it.capitalSystemId })
    }

    @Test
    fun `unavailable snapshot cannot claim a capital`() {
        assertFailsWith<IllegalArgumentException> {
            AllianceCapitalSnapshotDto(
                records = listOf(AllianceCapitalRecordDto(99, 30_000_001)),
                source = "Sovereignty Pack / PUBLIC_ESI",
                freshness = SovereigntyFreshnessDto.UNAVAILABLE,
            )
        }
    }
}
