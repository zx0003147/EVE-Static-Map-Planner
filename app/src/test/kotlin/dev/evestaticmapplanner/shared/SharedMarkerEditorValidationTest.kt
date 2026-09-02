package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMarkerDraft
import dev.evestaticmapplanner.shared.model.SharedMarkerValidation
import dev.evestaticmapplanner.shared.model.SharedMarkerValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SharedMarkerEditorValidationTest {
    @Test
    fun `free text tags accept future valid keys and normalize marker text like server`() {
        val tags = parseSharedMarkerTags("staging, future.tag custom-key")
        val normalized = SharedMarkerValidation.normalize(
            SharedMarkerDraft("  Enemy Staging  ", SharedMarkerColor.RED, tags, "\r\n notes \r\n"),
        )
        assertEquals(listOf("staging", "future.tag", "custom-key"), normalized.tags)
        assertEquals("Enemy Staging", normalized.name)
        assertEquals("notes", normalized.notes)
    }

    @Test
    fun `blank notes become null and invalid tags are rejected`() {
        assertNull(
            SharedMarkerValidation.normalize(
                SharedMarkerDraft("Name", SharedMarkerColor.BLUE, emptyList(), "  \n "),
            ).notes,
        )
        assertFailsWith<SharedMarkerValidationException> {
            SharedMarkerValidation.normalize(
                SharedMarkerDraft("Name", SharedMarkerColor.BLUE, listOf("UPPER"), null),
            )
        }
    }
}
