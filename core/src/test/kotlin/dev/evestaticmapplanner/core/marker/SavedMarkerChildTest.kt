package dev.evestaticmapplanner.core.marker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SavedMarkerChildTest {
    @Test
    fun `semantic type keys normalize without defining a product taxonomy`() {
        assertEquals("staging-area", SavedMarkerChildType.of("  STAGING-area  ").key)
        assertEquals("logistics.hub", SavedMarkerChildType.of("logistics.hub").key)
        assertFailsWith<IllegalArgumentException> { SavedMarkerChildType.of("") }
        assertFailsWith<IllegalArgumentException> { SavedMarkerChildType.of("danger status") }
        assertFailsWith<IllegalArgumentException> { SavedMarkerChildType.of("_leading") }
    }

    @Test
    fun `child has stable identity parent ownership and explicit order`() {
        val child = SavedMarkerChild.create(
            id = "child-1",
            parentSystemId = 30_000_001,
            type = SavedMarkerChildType.of("danger"),
            orderIndex = 2,
        )

        assertEquals("child-1", child.id)
        assertEquals(30_000_001, child.parentSystemId)
        assertEquals("danger", child.type.key)
        assertEquals(2, child.orderIndex)
        assertFailsWith<IllegalArgumentException> {
            SavedMarkerChild.create("child-2", 0, SavedMarkerChildType.of("home"), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SavedMarkerChild.create("child-3", 1, SavedMarkerChildType.of("home"), -1)
        }
    }
}
