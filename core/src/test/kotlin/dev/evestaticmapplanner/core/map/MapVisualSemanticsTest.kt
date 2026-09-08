package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapVisualSemanticsTest {
    @Test
    fun `Desktop and Web route classifications share exact stroke semantics`() {
        assertEquals(MapStrokeSemantics(0x553F6685L, 1.0), MapVisualSemantics.stargateNetwork)
        assertEquals(MapStrokeSemantics(0x997C5CE0L, 1.5, listOf(6.0, 5.0), true), MapVisualSemantics.ansiblexNetwork)
        assertEquals(MapStrokeSemantics(0xFF42D6F5L, 3.0), MapVisualSemantics.normalRouteByEdgeType.getValue(RouteEdgeType.STARGATE))
        assertEquals(
            MapStrokeSemantics(0xFFFF9F43L, 4.0, listOf(12.0, 7.0), true),
            MapVisualSemantics.normalRouteByEdgeType.getValue(RouteEdgeType.ANSIBLEX),
        )
        assertEquals(MapStrokeSemantics(0xFF32D6C5L, 4.0), MapVisualSemantics.normalRouteByEdgeType.getValue(RouteEdgeType.WORMHOLE))
        assertEquals(MapStrokeSemantics(0xFFB388FFL, 4.0), MapVisualSemantics.capitalRoute)
    }

    @Test
    fun `Keepstar is primary shape while state treatments remain ordered overlays`() {
        assertEquals(PrimarySystemNodeShape.SYSTEM, MapVisualSemantics.primaryNodeShape(false))
        assertEquals(PrimarySystemNodeShape.KEEPSTAR, MapVisualSemantics.primaryNodeShape(true))
        assertTrue(SystemStateOverlayPriority.ROUTE < SystemStateOverlayPriority.WAYPOINT)
        assertTrue(SystemStateOverlayPriority.WAYPOINT < SystemStateOverlayPriority.SELECTED_OR_HOVERED)
    }
}
