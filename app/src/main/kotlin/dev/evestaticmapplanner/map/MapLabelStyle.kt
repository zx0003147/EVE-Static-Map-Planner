package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.preferences.MapDisplayPreferences

data class MapLabelStyle(
    val fontSizeSp: Float,
    val alpha: Float,
    val letterSpacingSp: Float = 0f,
)

object MapLabelStyleResolver {
    fun resolve(type: MapLabelType, preferences: MapDisplayPreferences): MapLabelStyle = when (type) {
        MapLabelType.SYSTEM -> MapLabelStyle(preferences.systemFontSizeSp, SYSTEM_LABEL_ALPHA)
        MapLabelType.REGION_PRIMARY -> MapLabelStyle(
            preferences.regionPrimaryFontSizeSp,
            REGION_PRIMARY_ALPHA,
            REGION_PRIMARY_LETTER_SPACING_SP,
        )
        MapLabelType.REGION_BACKGROUND -> MapLabelStyle(
            preferences.regionBackgroundFontSizeSp,
            preferences.regionBackgroundAlpha,
            REGION_BACKGROUND_LETTER_SPACING_SP,
        )
        MapLabelType.CONSTELLATION -> MapLabelStyle(
            preferences.constellationFontSizeSp,
            CONSTELLATION_ALPHA,
        )
    }
}

private const val SYSTEM_LABEL_ALPHA = 1f
private const val REGION_PRIMARY_ALPHA = 0.86f
private const val CONSTELLATION_ALPHA = 0.80f
private const val REGION_PRIMARY_LETTER_SPACING_SP = 1f
private const val REGION_BACKGROUND_LETTER_SPACING_SP = 1.25f
