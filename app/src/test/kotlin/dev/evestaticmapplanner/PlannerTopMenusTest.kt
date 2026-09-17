package dev.evestaticmapplanner

import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTopMenusTest {
    @Test
    fun `main shell menus use the selected catalog without changing menu structure`() {
        val state = PlannerTopMenuState(false, false, 1, true, false, false)
        val actions = PlannerTopMenuActions({}, {}, {}, {}, {}, {}, {}, {})
        val english = plannerTopMenus(state, actions, AppStringsCatalog.forLocale(AppLocale.EN_US).mainShell)
        val chinese = plannerTopMenus(state, actions, AppStringsCatalog.forLocale(AppLocale.ZH_CN).mainShell)

        assertEquals(english.map { it.items.size }, chinese.map { it.items.size })
        assertEquals(listOf("Marker", "Mini-map", "Preferences", "Static Data"), english.map { it.label })
        assertEquals(listOf("标记", "小地图", "设置", "静态数据"), chinese.map { it.label })
        assertEquals("设置…", chinese.single { it.label == "设置" }.items.single().label)
    }

    @Test
    fun `feature menus open dedicated settings while Preferences remains general`() {
        val opened = mutableListOf<String>()
        val menus = plannerTopMenus(
            state = PlannerTopMenuState(
                markerManagerOpen = false,
                sharedMarkerManagerOpen = false,
                temporaryMarkerCount = 0,
                characterTrackingAvailable = true,
                miniMapEnabled = true,
                staticDataOpen = false,
            ),
            actions = PlannerTopMenuActions(
                openMarkerManager = {},
                openSharedMarkerManager = {},
                clearTemporaryMarkers = {},
                openMarkerSettings = { opened += "marker-settings" },
                toggleMiniMap = {},
                openMiniMapSettings = { opened += "mini-map-settings" },
                openPreferences = { opened += "general-preferences" },
                openStaticData = {},
            ),
            strings = AppStringsCatalog.forLocale(AppLocale.EN_US).mainShell,
        )

        val markerItems = menus.single { it.label == "Marker" }.items
        val miniMapItems = menus.single { it.label == "Mini-map" }.items
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
        assertEquals(listOf("Hide Mini-map", "Mini-map Settings…"), miniMapItems.map { it.label })
        assertTrue(markerItems.last().separatorBefore)
        assertTrue(miniMapItems.last().separatorBefore)

        markerItems.last().onClick()
        miniMapItems.last().onClick()
        allPreferencesItems.single().onClick()
        assertEquals(
            listOf("marker-settings", "mini-map-settings", "general-preferences"),
            opened,
        )
    }
}
