package dev.evestaticmapplanner.map

import kotlin.test.Test
import kotlin.test.assertTrue

class StaticMapVisualLayerOrderTest {
    @Test
    fun `feature overlay remains below route marker and selection focus`() {
        assertTrue(StaticMapVisualLayerOrder.FEATURE_OVERLAY < StaticMapVisualLayerOrder.ANSIBLEX)
        assertTrue(StaticMapVisualLayerOrder.ANSIBLEX < StaticMapVisualLayerOrder.ROUTE_FOCUS)
        assertTrue(StaticMapVisualLayerOrder.ROUTE_FOCUS < StaticMapVisualLayerOrder.SAVED_MARKER)
        assertTrue(StaticMapVisualLayerOrder.SAVED_MARKER < StaticMapVisualLayerOrder.SELECTED_SYSTEM_FOCUS)
        assertTrue(StaticMapVisualLayerOrder.SELECTED_SYSTEM_FOCUS < FEATURE_OVERLAY_LEGEND_Z_INDEX)
        assertTrue(FEATURE_OVERLAY_LEGEND_Z_INDEX < CONTEXT_DISMISS_Z_INDEX)
        assertTrue(CONTEXT_DISMISS_Z_INDEX < CONTEXT_MENU_Z_INDEX)
    }
}
