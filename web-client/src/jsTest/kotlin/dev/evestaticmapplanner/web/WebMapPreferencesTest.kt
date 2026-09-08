package dev.evestaticmapplanner.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebMapPreferencesTest {
    @Test
    fun `Desktop defaults and hierarchy threshold crossings are preserved`() {
        val defaults = WebMapPreferences.Defaults
        assertEquals(2.0, defaults.constellationZoomThreshold)
        assertEquals(6.0, defaults.systemZoomThreshold)
        assertEquals(WebSemanticLabelMode.REGION, WebSemanticZoomPolicy.initial(1.99, defaults))
        assertEquals(WebSemanticLabelMode.CONSTELLATION, WebSemanticZoomPolicy.initial(2.0, defaults))
        assertEquals(WebSemanticLabelMode.SYSTEM, WebSemanticZoomPolicy.initial(6.0, defaults))
        assertEquals(
            WebSemanticLabelMode.CONSTELLATION,
            WebSemanticZoomPolicy.transition(WebSemanticLabelMode.SYSTEM, 4.9, defaults),
        )
    }

    @Test
    fun `custom preferences persist invalid data falls back and reset removes storage`() {
        val storage = MemoryBrowserStore()
        val store = WebMapPreferencesStore(storage)
        val custom = WebMapPreferences(3.5, 8.0)
        store.save(custom)
        assertEquals(custom, WebMapPreferencesStore(storage).load())

        storage.set("eve-static-map-planner.web-map-preferences.v1", "9|3")
        assertEquals(WebMapPreferences.Defaults, store.load())
        assertFalse(WebMapPreferences.isValid(6.0, 6.0))
        assertFalse(WebMapPreferences.isValid(Double.NaN, 6.0))

        assertEquals(WebMapPreferences.Defaults, store.reset())
        assertEquals(WebMapPreferences.Defaults, store.load())
    }
}

internal class MemoryBrowserStore : BrowserStringStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun set(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
