package dev.evestaticmapplanner.core.marker

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MarkerTest {
    @Test
    fun `temporary marker has session-only defaults`() {
        val marker = Marker.temporary(30_000_001)

        assertEquals(MarkerPersistence.TEMPORARY, marker.persistence)
        assertEquals(MarkerColor.YELLOW, marker.color)
        assertNull(marker.name)
        assertNull(marker.notes)
        assertNull(marker.createdAt)
        assertNull(marker.updatedAt)
    }

    @Test
    fun `draft trims optional text and converts blanks to null`() {
        val draft = MarkerDraft.create(name = "  Staging  ", notes = "  ", color = MarkerColor.BLUE)

        assertEquals("Staging", draft.name)
        assertNull(draft.notes)
        assertEquals(MarkerColor.BLUE, draft.color)
    }

    @Test
    fun `saved marker requires positive system id and carries timestamps`() {
        val created = Instant.parse("2026-08-21T01:00:00Z")
        val updated = Instant.parse("2026-08-21T02:00:00Z")
        val marker = Marker.saved(42, MarkerDraft.create(name = "Home"), created, updated)

        assertEquals(MarkerPersistence.SAVED, marker.persistence)
        assertEquals(created, marker.createdAt)
        assertEquals(updated, marker.updatedAt)
        assertFailsWith<IllegalArgumentException> { Marker.temporary(0) }
        assertFailsWith<IllegalArgumentException> {
            Marker.saved(-1, MarkerDraft.create(), created, updated)
        }
    }

    @Test
    fun `all seven marker colors remain a fixed domain palette`() {
        assertEquals(
            listOf("RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE", "WHITE"),
            MarkerColor.entries.map(MarkerColor::name),
        )
    }
}
