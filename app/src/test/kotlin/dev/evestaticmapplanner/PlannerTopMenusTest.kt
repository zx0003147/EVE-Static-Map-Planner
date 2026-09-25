package dev.evestaticmapplanner

import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTopMenusTest {
    @Test
    fun `main shell menus use the selected catalog without changing menu structure`() {
        val state = PlannerTopMenuState(false, false, 1, false)
        val actions = PlannerTopMenuActions({}, {}, {}, {}, {}, {})
        val english = plannerTopMenus(state, actions, AppStringsCatalog.forLocale(AppLocale.EN_US).mainShell)
        val chinese = plannerTopMenus(state, actions, AppStringsCatalog.forLocale(AppLocale.ZH_CN).mainShell)

        assertEquals(english.map { it.items.size }, chinese.map { it.items.size })
        assertEquals(listOf("Marker", "Preferences", "Static Data"), english.map { it.label })
        assertEquals(listOf("标记", "设置", "静态数据"), chinese.map { it.label })
        assertEquals("设置…", chinese.single { it.label == "设置" }.items.single().label)
    }

    @Test
    fun `marker menu opens dedicated settings while Preferences remains general`() {
        val opened = mutableListOf<String>()
        val menus = plannerTopMenus(
            state = PlannerTopMenuState(
                markerManagerOpen = false,
                sharedMarkerManagerOpen = false,
                temporaryMarkerCount = 0,
                staticDataOpen = false,
            ),
            actions = PlannerTopMenuActions(
                openMarkerManager = {},
                openSharedMarkerManager = {},
                clearTemporaryMarkers = {},
                openMarkerSettings = { opened += "marker-settings" },
                openPreferences = { opened += "general-preferences" },
                openStaticData = {},
            ),
            strings = AppStringsCatalog.forLocale(AppLocale.EN_US).mainShell,
        )

        val markerItems = menus.single { it.label == "Marker" }.items
        val allPreferencesItems = menus.single { it.label == "Preferences" }.items
        assertTrue(menus.none { it.label == "AI" })

        assertEquals(
            listOf(
                "Marker Manager…",
                "Shared Marker Manager…",
                "Clear All Temporary Markers…",
                "Marker Settings…",
            ),
            markerItems.map { it.label },
        )
        assertTrue(markerItems.last().separatorBefore)

        markerItems.last().onClick()
        allPreferencesItems.single().onClick()
        assertEquals(
            listOf("marker-settings", "general-preferences"),
            opened,
        )
    }
}
