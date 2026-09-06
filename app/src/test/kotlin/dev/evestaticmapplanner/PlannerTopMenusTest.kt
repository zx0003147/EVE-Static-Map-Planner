package dev.evestaticmapplanner

import dev.evestaticmapplanner.preferences.PreferencesCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTopMenusTest {
    @Test
    fun `feature menus expose direct shortcuts into the shared preferences window`() {
        val destinations = mutableListOf<PreferencesCategory>()
        val menus = plannerTopMenus(
            state = PlannerTopMenuState(
                markerManagerOpen = false,
                sharedMarkerManagerOpen = false,
                temporaryMarkerCount = 0,
                miniMapEnabled = true,
                staticDataOpen = false,
            ),
            actions = PlannerTopMenuActions(
                openMarkerManager = {},
                openSharedMarkerManager = {},
                clearTemporaryMarkers = {},
                toggleMiniMap = {},
                openPreferences = destinations::add,
                openStaticData = {},
            ),
        )

        val markerItems = menus.single { it.label == "Marker" }.items
        val miniMapItems = menus.single { it.label == "Mini-map" }.items
        val allPreferencesItems = menus.single { it.label == "Preferences" }.items

        assertEquals(
            listOf(
                "Marker Manager…",
                "Shared Marker Manager…",
                "Clear All Temporary Markers…",
                "Marker Preferences…",
            ),
            markerItems.map { it.label },
        )
        assertEquals(listOf("Hide Mini-map", "Mini-map Preferences…"), miniMapItems.map { it.label })
        assertTrue(markerItems.last().separatorBefore)
        assertTrue(miniMapItems.last().separatorBefore)

        markerItems.last().onClick()
        miniMapItems.last().onClick()
        allPreferencesItems.single().onClick()
        assertEquals(
            listOf(PreferencesCategory.MARKER, PreferencesCategory.MINI_MAP, PreferencesCategory.MAP_DISPLAY),
            destinations,
        )
    }
}
