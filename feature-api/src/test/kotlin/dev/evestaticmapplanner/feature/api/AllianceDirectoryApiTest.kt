package dev.evestaticmapplanner.feature.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AllianceDirectoryApiTest {
    @Test
    fun `directory snapshots preserve stable IDs and optional display metadata`() {
        val snapshot = AllianceDirectoryProviderSnapshot(
            listOf(AllianceReferenceSnapshot(99), AllianceReferenceSnapshot(100, "Alliance", "ALLY")),
            observedAt = Instant.EPOCH,
            freshnessSeconds = 60,
        )

        assertNull(snapshot.alliances.first().name)
        assertEquals("ALLY", snapshot.alliances.last().ticker)
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.alliances as MutableList).add(AllianceReferenceSnapshot(101))
        }
    }

    @Test
    fun `standard key exposes optional alliance directory capability`() {
        assertEquals("alliance-directory", StandardFeatureCapabilities.ALLIANCE_DIRECTORY.id.value)
        assertNull(FeatureCapabilityLookup.empty().find(StandardFeatureCapabilities.ALLIANCE_DIRECTORY))
    }
}
