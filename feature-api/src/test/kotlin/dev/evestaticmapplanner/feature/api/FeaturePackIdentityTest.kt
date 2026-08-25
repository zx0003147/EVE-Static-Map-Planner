package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeaturePackIdentityTest {
    @Test
    fun `pack IDs accept only canonical portable syntax`() {
        listOf("a", "universe.overlay", "first-party_pack", "pack-123").forEach { value ->
            assertEquals(value, PackId(value).value)
        }
    }

    @Test
    fun `pack IDs reject traversal unicode separators and Windows device names`() {
        listOf(
            "", ".", "..", "a..b", "a/b", "a\\b", "C:pack", "Uppercase", "星图",
            "-pack", "pack-", "con", "nul.data", "com1.pack",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { PackId(value) }
        }
    }

    @Test
    fun `descriptor validates its human-facing fields and version token`() {
        val descriptor = FeaturePackDescriptor(
            PackId("fixture.pack"),
            "Fixture Pack",
            PackVersion("1.2.3-beta.1"),
            "First Party",
        )

        assertEquals("Fixture Pack", descriptor.displayName)
        assertFailsWith<IllegalArgumentException> {
            FeaturePackDescriptor(PackId("fixture.pack"), " ", PackVersion("1"), "Publisher")
        }
        assertFailsWith<IllegalArgumentException> {
            FeaturePackDescriptor(PackId("fixture.pack"), "Name", PackVersion("1"), "Publisher\nName")
        }
        assertFailsWith<IllegalArgumentException> { PackVersion("1.2.3 bad") }
    }
}
