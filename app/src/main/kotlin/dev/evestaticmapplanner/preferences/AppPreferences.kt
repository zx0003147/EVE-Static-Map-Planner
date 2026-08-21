package dev.evestaticmapplanner.preferences

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

data class AppPreferences(
    val mapDisplay: MapDisplayPreferences = MapDisplayPreferences.Defaults,
    val marker: MarkerPreferences = MarkerPreferences.Defaults,
) {
    companion object {
        val Defaults = AppPreferences()
    }
}

data class MarkerPreferences(
    val showMarkers: Boolean = true,
    val showMarkerNames: Boolean = true,
) {
    companion object {
        val Defaults = MarkerPreferences()
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
) : PreferencesStore {
    override fun load(): AppPreferences {
        if (!Files.isRegularFile(path)) return AppPreferences.Defaults
        val properties = runCatching {
            Properties().also { values -> Files.newInputStream(path).use(values::load) }
        }.getOrElse { return AppPreferences.Defaults }
        if (properties.getProperty(KEY_SETTINGS_VERSION) != SETTINGS_VERSION) return AppPreferences.Defaults

        val defaults = MapDisplayPreferences.Defaults
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
            ),
            marker = MarkerPreferences(
                showMarkers = properties.validBoolean(KEY_SHOW_MARKERS, MarkerPreferences.Defaults.showMarkers),
                showMarkerNames = properties.validBoolean(
                    KEY_SHOW_MARKER_NAMES,
                    MarkerPreferences.Defaults.showMarkerNames,
                ),
            ),
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
            val properties = Properties().apply {
                setProperty(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
                setProperty(KEY_CONSTELLATION_THRESHOLD, mapDisplay.constellationZoomThreshold.toString())
                setProperty(KEY_SYSTEM_THRESHOLD, mapDisplay.systemZoomThreshold.toString())
                setProperty(KEY_REGION_PRIMARY_FONT_SIZE, mapDisplay.regionPrimaryFontSizeSp.toString())
                setProperty(KEY_REGION_BACKGROUND_FONT_SIZE, mapDisplay.regionBackgroundFontSizeSp.toString())
                setProperty(KEY_REGION_BACKGROUND_ALPHA, mapDisplay.regionBackgroundAlpha.toString())
                setProperty(KEY_CONSTELLATION_FONT_SIZE, mapDisplay.constellationFontSizeSp.toString())
                setProperty(KEY_SYSTEM_FONT_SIZE, mapDisplay.systemFontSizeSp.toString())
                setProperty(KEY_SHOW_MARKERS, marker.showMarkers.toString())
                setProperty(KEY_SHOW_MARKER_NAMES, marker.showMarkerNames.toString())
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

const val SETTINGS_VERSION = "1"
const val DEFAULT_CONSTELLATION_ZOOM_THRESHOLD = 2.0
const val DEFAULT_SYSTEM_ZOOM_THRESHOLD = 6.0
const val DEFAULT_REGION_PRIMARY_FONT_SIZE_SP = 16f
const val DEFAULT_REGION_BACKGROUND_FONT_SIZE_SP = 20f
const val DEFAULT_REGION_BACKGROUND_ALPHA = 0.07f
const val DEFAULT_CONSTELLATION_FONT_SIZE_SP = 13f
const val DEFAULT_SYSTEM_FONT_SIZE_SP = 11f
const val MAX_ZOOM_THRESHOLD = 250.0
const val MAX_FONT_SIZE_SP = 72f

private const val KEY_SETTINGS_VERSION = "settings.version"
private const val KEY_CONSTELLATION_THRESHOLD = "mapDisplay.constellationZoomThreshold"
private const val KEY_SYSTEM_THRESHOLD = "mapDisplay.systemZoomThreshold"
private const val KEY_REGION_PRIMARY_FONT_SIZE = "mapDisplay.regionPrimaryFontSizeSp"
private const val KEY_REGION_BACKGROUND_FONT_SIZE = "mapDisplay.regionBackgroundFontSizeSp"
private const val KEY_REGION_BACKGROUND_ALPHA = "mapDisplay.regionBackgroundAlpha"
private const val KEY_CONSTELLATION_FONT_SIZE = "mapDisplay.constellationFontSizeSp"
private const val KEY_SYSTEM_FONT_SIZE = "mapDisplay.systemFontSizeSp"
private const val KEY_SHOW_MARKERS = "marker.showMarkers"
private const val KEY_SHOW_MARKER_NAMES = "marker.showMarkerNames"
