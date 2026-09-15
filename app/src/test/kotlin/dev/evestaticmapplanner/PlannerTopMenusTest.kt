package dev.evestaticmapplanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTopMenusTest {
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
                embeddedAiOpen = false,
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
                openEmbeddedAi = { opened += "embedded-ai" },
            ),
        )

        val markerItems = menus.single { it.label == "Marker" }.items
        val miniMapItems = menus.single { it.label == "Mini-map" }.items
        val allPreferencesItems = menus.single { it.label == "Preferences" }.items
        val aiItems = menus.single { it.label == "AI" }.items

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
        aiItems.single().onClick()
        assertEquals(
            listOf("marker-settings", "mini-map-settings", "general-preferences", "embedded-ai"),
            opened,
        )
    }
}
