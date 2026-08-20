package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.preferences.MapDisplayPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class MapLabelStyleTest {
    @Test
    fun `font preference changes immediately alter label presentation styles`() {
        val original = MapDisplayPreferences.Defaults
        val updated = original.copy(
            regionPrimaryFontSizeSp = 21f,
            regionBackgroundFontSizeSp = 24f,
            constellationFontSizeSp = 16f,
            systemFontSizeSp = 14f,
        )

        assertEquals(21f, MapLabelStyleResolver.resolve(MapLabelType.REGION_PRIMARY, updated).fontSizeSp)
        assertEquals(24f, MapLabelStyleResolver.resolve(MapLabelType.REGION_BACKGROUND, updated).fontSizeSp)
        assertEquals(16f, MapLabelStyleResolver.resolve(MapLabelType.CONSTELLATION, updated).fontSizeSp)
        assertEquals(14f, MapLabelStyleResolver.resolve(MapLabelType.SYSTEM, updated).fontSizeSp)
    }

    @Test
    fun `background alpha preference immediately alters rendered label color`() {
        val updated = MapDisplayPreferences.Defaults.copy(regionBackgroundAlpha = 0.19f)

        assertEquals(0.19f, MapLabelStyleResolver.resolve(MapLabelType.REGION_BACKGROUND, updated).alpha)
        assertEquals(0.19f, labelColor(MapLabelType.REGION_BACKGROUND, updated).alpha, absoluteTolerance = 0.003f)
    }
}
