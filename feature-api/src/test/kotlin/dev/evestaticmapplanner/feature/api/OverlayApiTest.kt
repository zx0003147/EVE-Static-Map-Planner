package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayApiTest {
    @Test
    fun `provider describes a layer and returns a rendering-neutral snapshot`() {
        val provider = fixtureProvider()

        assertEquals("test.provider", provider.descriptor().id)
        assertEquals("status", provider.layers().single().id)
        val entry = provider.snapshot().entries.single()
        assertEquals(30_000_142, entry.systemId)
        assertEquals("Ready", entry.value)
        assertEquals(OverlayEntryVisibility.VISIBLE, entry.visibility)
    }

    @Test
    fun `overlay state defensively copies collection inputs`() {
        val entries = mutableListOf(OverlayEntry("status", 30_000_142))
        val snapshot = OverlaySnapshot(entries)
        entries.clear()
        val layers = mutableListOf(OverlayLayerState(
            fixtureProvider().descriptor(),
            OverlayLayer("status", "Status"),
            snapshot.entries,
        ))
        val state = OverlayState(layers)
        layers.clear()

        assertEquals(1, snapshot.entries.size)
        assertFalse(state.isEmpty)
        assertEquals(1, state.layers.size)
        assertTrue(OverlayState(emptyList()).isEmpty)
    }

    @Test
    fun `overlay identifiers and system IDs reject invalid values`() {
        assertFailsWith<IllegalArgumentException> { OverlayProviderDescriptor("Bad ID", "Bad") }
        assertFailsWith<IllegalArgumentException> { OverlayLayer("status", " ") }
        assertFailsWith<IllegalArgumentException> { OverlayEntry("status", 0) }
    }

    @Test
    fun `system image marker copies image bytes and validates generic tooltip`() {
        val source = byteArrayOf(1, 2, 3)
        val image = OverlayImage("image/png", source)
        source[0] = 9
        val marker = OverlaySystemMarker(listOf(image), overflowCount = 2, tooltipLines = listOf("Pilot · 42"))

        assertEquals(listOf<Byte>(1, 2, 3), image.content.toList())
        assertEquals(2, marker.overflowCount)
        assertEquals(listOf("Pilot · 42"), marker.tooltipLines)
        assertFailsWith<IllegalArgumentException> { OverlayImage("image/gif", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { OverlaySystemMarker(emptyList()) }
    }

    private fun fixtureProvider() = object : OverlayProvider {
        override fun descriptor() = OverlayProviderDescriptor("test.provider", "Test Provider")

        override fun layers() = listOf(OverlayLayer("status", "Status", priority = 5))

        override fun snapshot() = OverlaySnapshot(listOf(
            OverlayEntry("status", 30_000_142, title = "Fixture", value = "Ready"),
        ))
    }
}
