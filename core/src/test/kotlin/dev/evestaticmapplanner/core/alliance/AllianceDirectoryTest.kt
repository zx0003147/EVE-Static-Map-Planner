package dev.evestaticmapplanner.core.alliance

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllianceDirectoryTest {
    @Test
    fun `directory merges only by stable alliance ID and keeps missing display metadata optional`() {
        val snapshot = AllianceDirectoryMerger.merge(
            listOf(
                AllianceDirectorySourceSnapshot(
                    "sovereignty",
                    listOf(AllianceReference(1, "Old Name"), AllianceReference(2, "Shared Name")),
                    Instant.parse("2026-01-01T00:00:00Z"),
                ),
                AllianceDirectorySourceSnapshot(
                    "identity",
                    listOf(AllianceReference(1, "New Name", "NEW"), AllianceReference(3, "Shared Name")),
                    Instant.parse("2026-02-01T00:00:00Z"),
                    priority = 10,
                ),
                AllianceDirectorySourceSnapshot("id-only", listOf(AllianceReference(4))),
            ),
        )

        assertEquals(setOf(1L, 2L, 3L, 4L), snapshot.alliancesById.keys)
        assertEquals("New Name", snapshot.alliancesById.getValue(1).name)
        assertEquals("NEW", snapshot.alliancesById.getValue(1).ticker)
        assertEquals("Shared Name", snapshot.alliancesById.getValue(2).name)
        assertEquals("Shared Name", snapshot.alliancesById.getValue(3).name)
        assertNull(snapshot.alliancesById.getValue(4).name)
        assertTrue(snapshot.diagnostics.single().contains("conflicting names"))
    }

    @Test
    fun `search ranks exact prefix substring and fuzzy candidates without collapsing duplicate text`() {
        val index = AllianceSearchIndex(snapshot())

        assertEquals(10L, index.search("CONDI").first().alliance.allianceId)
        assertEquals(10L, index.search("CON").first().alliance.allianceId)
        assertEquals(10L, index.search("Goons").first().alliance.allianceId)
        assertEquals(20L, index.search("Collective").first().alliance.allianceId)
        assertEquals(30L, index.search("Fraternity").first().alliance.allianceId)
        assertEquals(20L, index.search("BRAVR").first().alliance.allianceId)
        assertEquals(2, index.exactMatches("SAME").size)
    }

    @Test
    fun `owner resolver never promotes fuzzy or ambiguous text to a business identity`() {
        val resolver = AllianceOwnerResolver(snapshot())

        assertEquals(99L, assertIs<AllianceOwnerResolution.ResolvedExact>(resolver.resolve("99")).alliance.allianceId)
        assertEquals(10L, assertIs<AllianceOwnerResolution.ResolvedExact>(resolver.resolve("CONDI")).alliance.allianceId)
        assertEquals(10L, assertIs<AllianceOwnerResolution.ResolvedExact>(resolver.resolve("Goonswarm Federation")).alliance.allianceId)
        assertIs<AllianceOwnerResolution.AmbiguousExact>(resolver.resolve("SAME"))
        assertIs<AllianceOwnerResolution.NeedsConfirmation>(resolver.resolve("BRAVR"))
        assertIs<AllianceOwnerResolution.Unknown>(resolver.resolve("NO SUCH ALLIANCE"))
    }

    private fun snapshot() = AllianceDirectoryMerger.merge(
        listOf(
            AllianceDirectorySourceSnapshot(
                "fixture",
                listOf(
                    AllianceReference(10, "Goonswarm Federation", "CONDI"),
                    AllianceReference(20, "Brave Collective", "BRAVE"),
                    AllianceReference(30, "Fraternity.", "FRT"),
                    AllianceReference(40, "Same One", "SAME"),
                    AllianceReference(41, "Same Two", "SAME"),
                ),
            ),
        ),
    )
}
