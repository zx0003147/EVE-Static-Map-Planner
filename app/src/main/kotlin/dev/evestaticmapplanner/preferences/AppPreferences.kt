package dev.evestaticmapplanner.preferences

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

data class AppPreferences(
    val mapDisplay: MapDisplayPreferences = MapDisplayPreferences.Defaults,
    val marker: MarkerPreferences = MarkerPreferences.Defaults,
    val aiControl: AiControlPreferences = AiControlPreferences.Defaults,
    val overlayVisibility: OverlayVisibilityPreferences = OverlayVisibilityPreferences.Defaults,
) {
    companion object {
        val Defaults = AppPreferences()
    }
}

data class AiControlPreferences(
    val enabled: Boolean = false,
) {
    companion object {
        val Defaults = AiControlPreferences()
    }
}

data class MarkerPreferences(
    val showMarkers: Boolean = true,
    val showMarkerNames: Boolean = true,
    val savedMarkerAppearance: SavedMarkerAppearancePreferences = SavedMarkerAppearancePreferences.Defaults,
) {
    companion object {
        val Defaults = MarkerPreferences()
    }
}

data class SavedMarkerAppearancePreferences(
    val ringRadiusDp: Float = DEFAULT_SAVED_MARKER_RING_RADIUS_DP,
    val lineWidthDp: Float = DEFAULT_SAVED_MARKER_LINE_WIDTH_DP,
    val glowEnabled: Boolean = true,
    val glowStrength: Float = DEFAULT_SAVED_MARKER_GLOW_STRENGTH,
) {
    init {
        require(ringRadiusDp.isFinite() && ringRadiusDp in MIN_SAVED_MARKER_RING_RADIUS_DP..MAX_SAVED_MARKER_RING_RADIUS_DP)
        require(lineWidthDp.isFinite() && lineWidthDp in MIN_SAVED_MARKER_LINE_WIDTH_DP..MAX_SAVED_MARKER_LINE_WIDTH_DP)
        require(glowStrength.isFinite() && glowStrength in MIN_SAVED_MARKER_GLOW_STRENGTH..MAX_SAVED_MARKER_GLOW_STRENGTH)
    }

    companion object {
        val Defaults = SavedMarkerAppearancePreferences()
    }
}

data class MapDisplayPreferences(
    val constellationZoomThreshold: Double = DEFAULT_CONSTELLATION_ZOOM_THRESHOLD,
    val systemZoomThreshold: Double = DEFAULT_SYSTEM_ZOOM_THRESHOLD,
    val regionPrimaryFontSizeSp: Float = DEFAULT_REGION_PRIMARY_FONT_SIZE_SP,
    val regionBackgroundFontSizeSp: Float = DEFAULT_REGION_BACKGROUND_FONT_SIZE_SP,
    val regionBackgroundAlpha: Float = DEFAULT_REGION_BACKGROUND_ALPHA,
    val constellationFontSizeSp: Float = DEFAULT_CONSTELLATION_FONT_SIZE_SP,
    val systemFontSizeSp: Float = DEFAULT_SYSTEM_FONT_SIZE_SP,
    val sovereigntyLogoEmphasisZoom: Double = DEFAULT_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM,
) {
    init {
        require(
            constellationZoomThreshold.isFinite() &&
                constellationZoomThreshold > 0.0 &&
                constellationZoomThreshold <= MAX_ZOOM_THRESHOLD,
        ) {
            "Constellation threshold must be greater than zero"
        }
        require(
            systemZoomThreshold.isFinite() &&
                constellationZoomThreshold < systemZoomThreshold &&
                systemZoomThreshold <= MAX_ZOOM_THRESHOLD,
        ) {
            "System threshold must be greater than constellation threshold"
        }
        require(regionPrimaryFontSizeSp.isFinite() && regionPrimaryFontSizeSp in 1f..MAX_FONT_SIZE_SP)
        require(regionBackgroundFontSizeSp.isFinite() && regionBackgroundFontSizeSp in 1f..MAX_FONT_SIZE_SP)
        require(regionBackgroundAlpha.isFinite() && regionBackgroundAlpha in 0f..1f)
        require(constellationFontSizeSp.isFinite() && constellationFontSizeSp in 1f..MAX_FONT_SIZE_SP)
        require(systemFontSizeSp.isFinite() && systemFontSizeSp in 1f..MAX_FONT_SIZE_SP)
        require(
            sovereigntyLogoEmphasisZoom.isFinite() &&
                sovereigntyLogoEmphasisZoom in MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM..MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM,
        ) {
            "Sovereignty logo emphasis zoom must be between " +
                "$MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM and $MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM"
        }
    }

    companion object {
        val Defaults = MapDisplayPreferences()
    }
}

interface PreferencesStore {
    fun load(): AppPreferences
    fun save(preferences: AppPreferences)

    fun resetToDefaults(): AppPreferences = AppPreferences.Defaults.also(::save)
}

object DefaultPreferencesStore : PreferencesStore {
    override fun load(): AppPreferences = AppPreferences.Defaults
    override fun save(preferences: AppPreferences) = Unit
}

class PropertiesPreferencesStore(
    val path: Path,
    private val warningSink: (String) -> Unit = {},
) : PreferencesStore {
    override fun load(): AppPreferences {
        if (!Files.isRegularFile(path)) return AppPreferences.Defaults
        val properties = runCatching {
            Properties().also { values -> Files.newInputStream(path).use(values::load) }
        }.getOrElse {
            warningSink("Preferences could not be read; AI Control remains disabled")
            return AppPreferences.Defaults
        }
        if (properties.getProperty(KEY_SETTINGS_VERSION) != SETTINGS_VERSION) return AppPreferences.Defaults

        val defaults = MapDisplayPreferences.Defaults
        val markerDefaults = MarkerPreferences.Defaults
        val savedMarkerAppearanceDefaults = markerDefaults.savedMarkerAppearance
        val constellationThreshold = properties.validDouble(
            KEY_CONSTELLATION_THRESHOLD,
            defaults.constellationZoomThreshold,
        ) { it > 0.0 && it <= MAX_ZOOM_THRESHOLD }
        val systemThreshold = properties.validDouble(
            KEY_SYSTEM_THRESHOLD,
            defaults.systemZoomThreshold,
        ) { it > 0.0 && it <= MAX_ZOOM_THRESHOLD }
        val thresholds = if (constellationThreshold < systemThreshold) {
            constellationThreshold to systemThreshold
        } else {
            defaults.constellationZoomThreshold to defaults.systemZoomThreshold
        }
        return AppPreferences(
            mapDisplay = MapDisplayPreferences(
                constellationZoomThreshold = thresholds.first,
                systemZoomThreshold = thresholds.second,
                regionPrimaryFontSizeSp = properties.validFloat(
                    KEY_REGION_PRIMARY_FONT_SIZE,
                    defaults.regionPrimaryFontSizeSp,
                ) { it in 1f..MAX_FONT_SIZE_SP },
                regionBackgroundFontSizeSp = properties.validFloat(
                    KEY_REGION_BACKGROUND_FONT_SIZE,
                    defaults.regionBackgroundFontSizeSp,
                ) { it in 1f..MAX_FONT_SIZE_SP },
                regionBackgroundAlpha = properties.validFloat(
                    KEY_REGION_BACKGROUND_ALPHA,
                    defaults.regionBackgroundAlpha,
                ) { it in 0f..1f },
                constellationFontSizeSp = properties.validFloat(
                    KEY_CONSTELLATION_FONT_SIZE,
                    defaults.constellationFontSizeSp,
                ) { it in 1f..MAX_FONT_SIZE_SP },
                systemFontSizeSp = properties.validFloat(
                    KEY_SYSTEM_FONT_SIZE,
                    defaults.systemFontSizeSp,
                ) { it in 1f..MAX_FONT_SIZE_SP },
                sovereigntyLogoEmphasisZoom = properties.validDouble(
                    KEY_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM,
                    defaults.sovereigntyLogoEmphasisZoom,
                ) { it in MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM..MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM },
            ),
            marker = MarkerPreferences(
                showMarkers = properties.validBoolean(KEY_SHOW_MARKERS, markerDefaults.showMarkers),
                showMarkerNames = properties.validBoolean(
                    KEY_SHOW_MARKER_NAMES,
                    markerDefaults.showMarkerNames,
                ),
                savedMarkerAppearance = SavedMarkerAppearancePreferences(
                    ringRadiusDp = properties.validFloat(
                        KEY_SAVED_MARKER_RING_RADIUS,
                        savedMarkerAppearanceDefaults.ringRadiusDp,
                    ) { it in MIN_SAVED_MARKER_RING_RADIUS_DP..MAX_SAVED_MARKER_RING_RADIUS_DP },
                    lineWidthDp = properties.validFloat(
                        KEY_SAVED_MARKER_LINE_WIDTH,
                        savedMarkerAppearanceDefaults.lineWidthDp,
                    ) { it in MIN_SAVED_MARKER_LINE_WIDTH_DP..MAX_SAVED_MARKER_LINE_WIDTH_DP },
                    glowEnabled = properties.validBoolean(
                        KEY_SAVED_MARKER_GLOW_ENABLED,
                        savedMarkerAppearanceDefaults.glowEnabled,
                    ),
                    glowStrength = properties.validFloat(
                        KEY_SAVED_MARKER_GLOW_STRENGTH,
                        savedMarkerAppearanceDefaults.glowStrength,
                    ) { it in MIN_SAVED_MARKER_GLOW_STRENGTH..MAX_SAVED_MARKER_GLOW_STRENGTH },
                ),
            ),
            aiControl = AiControlPreferences(
                enabled = properties.safeAiControlEnabled(warningSink),
            ),
            overlayVisibility = properties.overlayVisibilityPreferences(),
        )
    }

    @Synchronized
    override fun save(preferences: AppPreferences) {
        val normalizedPath = path.toAbsolutePath().normalize()
        val parent = normalizedPath.parent
            ?: throw IllegalArgumentException("Preferences path requires a parent directory: $path")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "settings-", ".tmp")
        try {
            val mapDisplay = preferences.mapDisplay
            val marker = preferences.marker
            val aiControl = preferences.aiControl
            val overlayVisibility = preferences.overlayVisibility
            val properties = Properties().apply {
                setProperty(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
                setProperty(KEY_CONSTELLATION_THRESHOLD, mapDisplay.constellationZoomThreshold.toString())
                setProperty(KEY_SYSTEM_THRESHOLD, mapDisplay.systemZoomThreshold.toString())
                setProperty(KEY_REGION_PRIMARY_FONT_SIZE, mapDisplay.regionPrimaryFontSizeSp.toString())
                setProperty(KEY_REGION_BACKGROUND_FONT_SIZE, mapDisplay.regionBackgroundFontSizeSp.toString())
                setProperty(KEY_REGION_BACKGROUND_ALPHA, mapDisplay.regionBackgroundAlpha.toString())
                setProperty(KEY_CONSTELLATION_FONT_SIZE, mapDisplay.constellationFontSizeSp.toString())
                setProperty(KEY_SYSTEM_FONT_SIZE, mapDisplay.systemFontSizeSp.toString())
                setProperty(KEY_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM, mapDisplay.sovereigntyLogoEmphasisZoom.toString())
                setProperty(KEY_SHOW_MARKERS, marker.showMarkers.toString())
                setProperty(KEY_SHOW_MARKER_NAMES, marker.showMarkerNames.toString())
                setProperty(KEY_SAVED_MARKER_RING_RADIUS, marker.savedMarkerAppearance.ringRadiusDp.toString())
                setProperty(KEY_SAVED_MARKER_LINE_WIDTH, marker.savedMarkerAppearance.lineWidthDp.toString())
                setProperty(KEY_SAVED_MARKER_GLOW_ENABLED, marker.savedMarkerAppearance.glowEnabled.toString())
                setProperty(KEY_SAVED_MARKER_GLOW_STRENGTH, marker.savedMarkerAppearance.glowStrength.toString())
                setProperty(KEY_AI_CONTROL_ENABLED, aiControl.enabled.toString())
                setProperty(
                    KEY_OVERLAY_DISABLED_LAYERS,
                    overlayVisibility.disabledLayers.map(OverlayLayerKey::encode).sorted().joinToString(","),
                )
            }
            Files.newOutputStream(temporary).use {
                properties.store(it, "EVE Static Map Planner preferences")
            }
            try {
                Files.move(
                    temporary,
                    normalizedPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, normalizedPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun Properties.validDouble(key: String, default: Double, predicate: (Double) -> Boolean): Double =
    getProperty(key)?.toDoubleOrNull()?.takeIf { it.isFinite() && predicate(it) } ?: default

private fun Properties.validFloat(key: String, default: Float, predicate: (Float) -> Boolean): Float =
    getProperty(key)?.toFloatOrNull()?.takeIf { it.isFinite() && predicate(it) } ?: default

private fun Properties.validBoolean(key: String, default: Boolean): Boolean = when (getProperty(key)) {
    "true" -> true
    "false" -> false
    else -> default
}

private fun Properties.safeAiControlEnabled(warningSink: (String) -> Unit): Boolean = when (getProperty(KEY_AI_CONTROL_ENABLED)) {
    null, "false" -> false
    "true" -> true
    else -> {
        warningSink("AI Control preference is invalid and was disabled")
        false
    }
}

private fun Properties.overlayVisibilityPreferences(): OverlayVisibilityPreferences {
    val disabledLayers = getProperty(KEY_OVERLAY_DISABLED_LAYERS)
        ?.split(',')
        .orEmpty()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull(OverlayLayerKey::decode)
        .toSet()
    return OverlayVisibilityPreferences(disabledLayers)
}

const val SETTINGS_VERSION = "1"
const val DEFAULT_CONSTELLATION_ZOOM_THRESHOLD = 2.0
const val DEFAULT_SYSTEM_ZOOM_THRESHOLD = 6.0
const val DEFAULT_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM = 0.75
const val MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM = 0.01
const val MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM = 250.0
const val DEFAULT_REGION_PRIMARY_FONT_SIZE_SP = 16f
const val DEFAULT_REGION_BACKGROUND_FONT_SIZE_SP = 20f
const val DEFAULT_REGION_BACKGROUND_ALPHA = 0.07f
const val DEFAULT_CONSTELLATION_FONT_SIZE_SP = 13f
const val DEFAULT_SYSTEM_FONT_SIZE_SP = 11f
const val MAX_ZOOM_THRESHOLD = 250.0
const val MAX_FONT_SIZE_SP = 72f
const val DEFAULT_SAVED_MARKER_RING_RADIUS_DP = 13f
const val MIN_SAVED_MARKER_RING_RADIUS_DP = 10f
const val MAX_SAVED_MARKER_RING_RADIUS_DP = 30f
const val DEFAULT_SAVED_MARKER_LINE_WIDTH_DP = 2f
const val MIN_SAVED_MARKER_LINE_WIDTH_DP = 1f
const val MAX_SAVED_MARKER_LINE_WIDTH_DP = 5f
const val DEFAULT_SAVED_MARKER_GLOW_STRENGTH = 0.5f
const val MIN_SAVED_MARKER_GLOW_STRENGTH = 0f
const val MAX_SAVED_MARKER_GLOW_STRENGTH = 1f

private const val KEY_SETTINGS_VERSION = "settings.version"
private const val KEY_CONSTELLATION_THRESHOLD = "mapDisplay.constellationZoomThreshold"
private const val KEY_SYSTEM_THRESHOLD = "mapDisplay.systemZoomThreshold"
private const val KEY_REGION_PRIMARY_FONT_SIZE = "mapDisplay.regionPrimaryFontSizeSp"
private const val KEY_REGION_BACKGROUND_FONT_SIZE = "mapDisplay.regionBackgroundFontSizeSp"
private const val KEY_REGION_BACKGROUND_ALPHA = "mapDisplay.regionBackgroundAlpha"
private const val KEY_CONSTELLATION_FONT_SIZE = "mapDisplay.constellationFontSizeSp"
private const val KEY_SYSTEM_FONT_SIZE = "mapDisplay.systemFontSizeSp"
private const val KEY_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM = "mapDisplay.sovereigntyLogoEmphasisZoom"
private const val KEY_SHOW_MARKERS = "marker.showMarkers"
private const val KEY_SHOW_MARKER_NAMES = "marker.showMarkerNames"
private const val KEY_SAVED_MARKER_RING_RADIUS = "marker.savedMarkerAppearance.ringRadiusDp"
private const val KEY_SAVED_MARKER_LINE_WIDTH = "marker.savedMarkerAppearance.lineWidthDp"
private const val KEY_SAVED_MARKER_GLOW_ENABLED = "marker.savedMarkerAppearance.glowEnabled"
private const val KEY_SAVED_MARKER_GLOW_STRENGTH = "marker.savedMarkerAppearance.glowStrength"
private const val KEY_AI_CONTROL_ENABLED = "aiControl.enabled"
private const val KEY_OVERLAY_DISABLED_LAYERS = "overlay.disabledLayers"
