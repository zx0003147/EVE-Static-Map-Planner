package dev.evestaticmapplanner.web

import kotlinx.browser.window

data class WebMapPreferences(
    val constellationZoomThreshold: Double = DEFAULT_CONSTELLATION_ZOOM_THRESHOLD,
    val systemZoomThreshold: Double = DEFAULT_SYSTEM_ZOOM_THRESHOLD,
) {
    init {
        require(isValid(constellationZoomThreshold, systemZoomThreshold)) {
            "Constellation threshold must be positive and lower than the System threshold (maximum 250)."
        }
    }

    companion object {
        val Defaults = WebMapPreferences()

        fun isValid(constellation: Double, system: Double): Boolean =
            constellation.isFinite() && system.isFinite() &&
                constellation > 0.0 && constellation < system && system <= MAX_ZOOM_THRESHOLD
    }
}

enum class WebSemanticLabelMode {
    REGION,
    CONSTELLATION,
    SYSTEM,
}

object WebSemanticZoomPolicy {
    fun initial(zoom: Double, preferences: WebMapPreferences): WebSemanticLabelMode = when {
        zoom >= preferences.systemZoomThreshold -> WebSemanticLabelMode.SYSTEM
        zoom >= preferences.constellationZoomThreshold -> WebSemanticLabelMode.CONSTELLATION
        else -> WebSemanticLabelMode.REGION
    }

    fun transition(
        current: WebSemanticLabelMode,
        zoom: Double,
        preferences: WebMapPreferences,
    ): WebSemanticLabelMode {
        val constellationReturn = preferences.constellationZoomThreshold * HYSTERESIS_RETURN_RATIO
        val systemReturn = maxOf(
            preferences.systemZoomThreshold * HYSTERESIS_RETURN_RATIO,
            preferences.constellationZoomThreshold,
        )
        return when (current) {
            WebSemanticLabelMode.REGION -> initial(zoom, preferences)
            WebSemanticLabelMode.CONSTELLATION -> when {
                zoom >= preferences.systemZoomThreshold -> WebSemanticLabelMode.SYSTEM
                zoom <= constellationReturn -> WebSemanticLabelMode.REGION
                else -> current
            }
            WebSemanticLabelMode.SYSTEM -> when {
                zoom <= constellationReturn -> WebSemanticLabelMode.REGION
                zoom <= systemReturn -> WebSemanticLabelMode.CONSTELLATION
                else -> current
            }
        }
    }
}

interface BrowserStringStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

object LocalStorageStringStore : BrowserStringStore {
    override fun get(key: String): String? = runCatching { window.localStorage.getItem(key) }.getOrNull()
    override fun set(key: String, value: String) {
        runCatching { window.localStorage.setItem(key, value) }
    }
    override fun remove(key: String) {
        runCatching { window.localStorage.removeItem(key) }
    }
}

class WebMapPreferencesStore(private val storage: BrowserStringStore = LocalStorageStringStore) {
    fun load(): WebMapPreferences {
        val raw = storage.get(STORAGE_KEY) ?: return WebMapPreferences.Defaults
        val parts = raw.split('|')
        val constellation = parts.getOrNull(0)?.toDoubleOrNull()
        val system = parts.getOrNull(1)?.toDoubleOrNull()
        return if (constellation != null && system != null && WebMapPreferences.isValid(constellation, system)) {
            WebMapPreferences(constellation, system)
        } else {
            WebMapPreferences.Defaults
        }
    }

    fun save(preferences: WebMapPreferences) {
        storage.set(STORAGE_KEY, "${preferences.constellationZoomThreshold}|${preferences.systemZoomThreshold}")
    }

    fun reset(): WebMapPreferences = WebMapPreferences.Defaults.also {
        storage.remove(STORAGE_KEY)
    }

    private companion object {
        const val STORAGE_KEY = "eve-static-map-planner.web-map-preferences.v1"
    }
}

internal const val DEFAULT_CONSTELLATION_ZOOM_THRESHOLD = 2.0
internal const val DEFAULT_SYSTEM_ZOOM_THRESHOLD = 6.0
internal const val MAX_ZOOM_THRESHOLD = 250.0
internal const val HYSTERESIS_RETURN_RATIO = 0.83
