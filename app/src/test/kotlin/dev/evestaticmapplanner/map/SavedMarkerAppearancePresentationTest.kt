package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.preferences.SavedMarkerAppearancePreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavedMarkerAppearancePresentationTest {
    @Test
    fun `default render state preserves the accepted ring and glow appearance`() {
        val state = savedMarkerRingRenderState(SavedMarkerAppearancePreferences.Defaults)

        assertEquals(13f, state.radiusDp)
        assertEquals(2f, state.lineWidthDp)
        assertEquals(2, state.glowLayers.size)
        assertEquals(6f, state.glowLayers[0].expansionDp)
        assertEquals(0.08f, state.glowLayers[0].alpha, absoluteTolerance = 0.000_001f)
        assertEquals(3f, state.glowLayers[1].expansionDp)
        assertEquals(0.18f, state.glowLayers[1].alpha, absoluteTolerance = 0.000_001f)
    }

    @Test
    fun `disabling glow removes only glow layers while retaining the main ring`() {
        val preferences = SavedMarkerAppearancePreferences(
            ringRadiusDp = 25f,
            lineWidthDp = 4f,
            glowEnabled = false,
            glowStrength = 1f,
        )

        val state = savedMarkerRingRenderState(preferences)

        assertEquals(25f, state.radiusDp)
        assertEquals(4f, state.lineWidthDp)
        assertTrue(state.glowLayers.isEmpty())
    }

    @Test
    fun `glow strength produces deterministic bounded layer alpha`() {
        val state = savedMarkerRingRenderState(
            SavedMarkerAppearancePreferences(glowStrength = 1f),
        )

        assertEquals(0.128f, state.glowLayers[0].alpha, absoluteTolerance = 0.000_001f)
        assertEquals(0.288f, state.glowLayers[1].alpha, absoluteTolerance = 0.000_001f)
    }

    @Test
    fun `child orbit keeps its default and clears a larger parent ring`() {
        assertEquals(38f, savedMarkerChildOrbitRadiusDp(13f))
        assertEquals(45f, savedMarkerChildOrbitRadiusDp(30f))
        assertTrue(savedMarkerChildOrbitRadiusDp(30f) > 30f + 9f)
    }
}
