package dev.evestaticmapplanner.core.jump

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JumpRangeOverlayCollectionTest {
    private val systems = listOf(
        jumpTestSystem(30_000_001, xLy = 0.0),
        jumpTestSystem(30_000_002, xLy = 4.0),
        jumpTestSystem(30_000_003, xLy = 8.0),
        jumpTestSystem(30_000_004, xLy = 12.0),
    )
    private val collection = JumpRangeOverlayCollection(
        CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(systems)),
    )

    @Test
    fun `supports add remove enable update clear and coverage counts`() {
        collection.add("A", 30_000_001, JumpProfile.manual(9.0, "a"))
        collection.add("B", 30_000_002, JumpProfile.manual(9.0, "b"))
        assertEquals(2, collection.all().size)
        assertEquals(2, collection.coverageCounts().getValue(30_000_003))

        assertFalse(collection.setEnabled("A", false)!!.enabled)
        assertEquals(1, collection.coverageCounts().getValue(30_000_003))
        assertTrue(collection.updateProfile("B", JumpProfile.manual(4.0, "b2"))!!.reachableSystemIds.contains(30_000_001))
        assertTrue(collection.remove("A"))
        collection.clear()
        assertEquals(emptyList(), collection.all())
    }

    @Test
    fun `intersects any specified set of enabled overlays`() {
        collection.add("A", 30_000_001, JumpProfile.manual(9.0, "a"))
        collection.add("B", 30_000_002, JumpProfile.manual(9.0, "b"))
        collection.add("C", 30_000_004, JumpProfile.manual(3.0, "c"))

        assertEquals(setOf(30_000_003), collection.intersection(setOf("A", "B")))
        assertEquals(emptySet(), collection.intersection(setOf("A", "B", "C")))
        collection.setEnabled("C", false)
        assertEquals(setOf(30_000_003), collection.intersection())
    }
}
