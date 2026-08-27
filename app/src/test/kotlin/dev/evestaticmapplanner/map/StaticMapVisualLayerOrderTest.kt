package dev.evestaticmapplanner.map

import kotlin.test.Test
import kotlin.test.assertTrue

class StaticMapVisualLayerOrderTest {
    @Test
    fun `territory remains below topology route marker and selection focus`() {
        assertTrue(BaseMapVisualLayer.BACKGROUND < BaseMapVisualLayer.FEATURE_TERRITORY)
        assertTrue(BaseMapVisualLayer.FEATURE_TERRITORY < BaseMapVisualLayer.FEATURE_TERRITORY_EMBLEMS)
        assertTrue(BaseMapVisualLayer.FEATURE_TERRITORY_EMBLEMS < BaseMapVisualLayer.REGION_BACKGROUND_LABELS)
        assertTrue(BaseMapVisualLayer.FEATURE_TERRITORY_EMBLEMS < BaseMapVisualLayer.STARGATE_CONNECTIONS)
        assertTrue(BaseMapVisualLayer.FEATURE_TERRITORY_EMBLEMS < BaseMapVisualLayer.SYSTEM_NODES_AND_LABELS)
        assertTrue(BaseMapVisualLayer.FEATURE_TERRITORY < BaseMapVisualLayer.STARGATE_CONNECTIONS)
        assertTrue(BaseMapVisualLayer.FEATURE_TERRITORY < BaseMapVisualLayer.SYSTEM_NODES_AND_LABELS)
        assertTrue(StaticMapVisualLayerOrder.BASE_MAP < StaticMapVisualLayerOrder.ANSIBLEX)
        assertTrue(StaticMapVisualLayerOrder.ANSIBLEX < StaticMapVisualLayerOrder.ROUTE_FOCUS)
        assertTrue(StaticMapVisualLayerOrder.ROUTE_FOCUS < StaticMapVisualLayerOrder.SAVED_MARKER)
        assertTrue(StaticMapVisualLayerOrder.SAVED_MARKER < StaticMapVisualLayerOrder.SELECTED_SYSTEM_FOCUS)
        assertTrue(StaticMapVisualLayerOrder.SELECTED_SYSTEM_FOCUS < FEATURE_OVERLAY_LEGEND_Z_INDEX)
        assertTrue(FEATURE_OVERLAY_LEGEND_Z_INDEX < CONTEXT_DISMISS_Z_INDEX)
        assertTrue(CONTEXT_DISMISS_Z_INDEX < CONTEXT_MENU_Z_INDEX)
    }
}
