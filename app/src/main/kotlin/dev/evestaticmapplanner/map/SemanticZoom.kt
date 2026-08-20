package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.preferences.MapDisplayPreferences

enum class SemanticLabelMode {
    REGION_ONLY,
    CONSTELLATION,
    SYSTEM,
}

enum class RegionLabelRole {
    PRIMARY,
    BACKGROUND,
}

val SemanticLabelMode.regionLabelRole: RegionLabelRole
    get() = if (this == SemanticLabelMode.REGION_ONLY) RegionLabelRole.PRIMARY else RegionLabelRole.BACKGROUND

object SemanticZoomPolicy {
    fun initialMode(
        zoom: Double,
        preferences: MapDisplayPreferences = MapDisplayPreferences.Defaults,
    ): SemanticLabelMode {
        require(zoom.isFinite() && zoom > 0.0)
        return when {
            zoom >= preferences.systemZoomThreshold -> SemanticLabelMode.SYSTEM
            zoom >= preferences.constellationZoomThreshold -> SemanticLabelMode.CONSTELLATION
            else -> SemanticLabelMode.REGION_ONLY
        }
    }

    fun transition(
        current: SemanticLabelMode,
        zoom: Double,
        preferences: MapDisplayPreferences = MapDisplayPreferences.Defaults,
    ): SemanticLabelMode {
        require(zoom.isFinite() && zoom > 0.0)
        val constellationReturnZoom = preferences.constellationZoomThreshold * HYSTERESIS_RETURN_RATIO
        val systemReturnZoom = maxOf(
            preferences.systemZoomThreshold * HYSTERESIS_RETURN_RATIO,
            preferences.constellationZoomThreshold,
        )
        return when (current) {
            SemanticLabelMode.REGION_ONLY -> when {
                zoom >= preferences.systemZoomThreshold -> SemanticLabelMode.SYSTEM
                zoom >= preferences.constellationZoomThreshold -> SemanticLabelMode.CONSTELLATION
                else -> SemanticLabelMode.REGION_ONLY
            }
            SemanticLabelMode.CONSTELLATION -> when {
                zoom >= preferences.systemZoomThreshold -> SemanticLabelMode.SYSTEM
                zoom <= constellationReturnZoom -> SemanticLabelMode.REGION_ONLY
                else -> SemanticLabelMode.CONSTELLATION
            }
            SemanticLabelMode.SYSTEM -> when {
                zoom <= constellationReturnZoom -> SemanticLabelMode.REGION_ONLY
                zoom <= systemReturnZoom -> SemanticLabelMode.CONSTELLATION
                else -> SemanticLabelMode.SYSTEM
            }
        }
    }

    fun constellationReturnZoom(preferences: MapDisplayPreferences): Double =
        preferences.constellationZoomThreshold * HYSTERESIS_RETURN_RATIO

    fun systemReturnZoom(preferences: MapDisplayPreferences): Double = maxOf(
        preferences.systemZoomThreshold * HYSTERESIS_RETURN_RATIO,
        preferences.constellationZoomThreshold,
    )
}

// Users choose only the two enter thresholds. Return thresholds are derived to keep the UI stable.
const val HYSTERESIS_RETURN_RATIO = 0.83
