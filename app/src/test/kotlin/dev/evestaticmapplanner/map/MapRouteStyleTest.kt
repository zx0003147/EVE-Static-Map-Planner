package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapRouteStyleTest {
    @Test
    fun `route types use their exact visual identity colors`() {
        assertEquals(Color(0xFF42D6F5), ROUTE_STARGATE_COLOR)
        assertEquals(Color(0xFFFF9F43), ROUTE_ANSIBLEX_COLOR)
        assertEquals(Color(0xFFB388FF), CAPITAL_ROUTE_COLOR)
        assertEquals(listOf(Color(0xFFF4E06D)), MISSION_ROUTE_COLORS)
        assertEquals(Color(0xFFFF5C57), MISSION_CAPITAL_ROUTE_COLOR)
        assertEquals(
            listOf(Color(0xFFFF5C57), Color(0xE6FF5C57), Color(0xCCFF5C57), Color(0xB3FF5C57)),
            MISSION_CAPITAL_COLORS,
        )
    }

    @Test
    fun `active Ansiblex route remains dashed while Stargate route remains solid`() {
        val ansiblex = routeLegRenderStyle(RouteEdgeType.ANSIBLEX)
        val stargate = routeLegRenderStyle(RouteEdgeType.STARGATE)

        assertEquals(ROUTE_ANSIBLEX_COLOR, ansiblex.color)
        assertEquals(4f, ansiblex.strokeWidth)
        assertEquals(listOf(12f, 7f), ansiblex.dashPattern)
        assertEquals(ROUTE_STARGATE_COLOR, stargate.color)
        assertEquals(3f, stargate.strokeWidth)
        assertNull(stargate.dashPattern)
    }
}
