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
        assertTrue(StaticMapVisualLayerOrder.ANSIBLEX < StaticMapVisualLayerOrder.WORMHOLE)
        assertTrue(StaticMapVisualLayerOrder.WORMHOLE < StaticMapVisualLayerOrder.RANGE_OVERLAY)
        assertTrue(StaticMapVisualLayerOrder.RANGE_OVERLAY < StaticMapVisualLayerOrder.SHARED_MARKER)
        assertTrue(StaticMapVisualLayerOrder.SHARED_MARKER < StaticMapVisualLayerOrder.ROUTE)
        assertTrue(StaticMapVisualLayerOrder.ROUTE < StaticMapVisualLayerOrder.ROUTE_FOCUS)
        assertTrue(StaticMapVisualLayerOrder.ROUTE_FOCUS < StaticMapVisualLayerOrder.ROUTE_WAYPOINT)
        assertTrue(StaticMapVisualLayerOrder.ROUTE_WAYPOINT < StaticMapVisualLayerOrder.SAVED_MARKER)
        assertTrue(StaticMapVisualLayerOrder.SAVED_MARKER < StaticMapVisualLayerOrder.SELECTED_SYSTEM_FOCUS)
        assertTrue(StaticMapVisualLayerOrder.SELECTED_SYSTEM_FOCUS < FEATURE_OVERLAY_LEGEND_Z_INDEX)
        assertTrue(FEATURE_OVERLAY_LEGEND_Z_INDEX < CONTEXT_DISMISS_Z_INDEX)
        assertTrue(CONTEXT_DISMISS_Z_INDEX < CONTEXT_MENU_Z_INDEX)
    }
}
