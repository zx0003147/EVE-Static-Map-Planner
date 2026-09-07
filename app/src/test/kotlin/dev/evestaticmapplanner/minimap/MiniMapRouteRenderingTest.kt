package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.map.MISSION_CAPITAL_COLORS
import dev.evestaticmapplanner.map.MISSION_ROUTE_COLORS
import dev.evestaticmapplanner.map.routeLegRenderStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniMapRouteRenderingTest {
    @Test
    fun `user and Mission routes enter the shared directional rendering pipeline with their own styles`() {
        val segment = MiniMapRouteSegment(
            fromSystemId = 2,
            toSystemId = 3,
            from = MapPoint(20.0, 0.0),
            to = MapPoint(30.0, 0.0),
            edgeType = RouteEdgeType.ANSIBLEX,
        )
        val items = miniMapDirectionalRouteRenderItems(
            listOf(
                MiniMapRouteOverlay("user:normal", MiniMapRouteKind.USER_NORMAL, 0, listOf(segment)),
                MiniMapRouteOverlay("mission:normal", MiniMapRouteKind.MISSION_NORMAL, 0, listOf(segment)),
                MiniMapRouteOverlay(
                    "mission:capital",
                    MiniMapRouteKind.MISSION_CAPITAL,
                    1,
                    listOf(segment.copy(edgeType = null)),
                ),
            ),
        )

        assertEquals(
            listOf(
                MiniMapRouteKind.USER_NORMAL,
                MiniMapRouteKind.MISSION_NORMAL,
                MiniMapRouteKind.MISSION_CAPITAL,
            ),
            items.map(MiniMapDirectionalRouteRenderItem::kind),
        )
        assertEquals(listOf(2 to 3, 2 to 3, 2 to 3), items.map { it.segment.fromSystemId to it.segment.toSystemId })

        val userStyle = routeLegRenderStyle(RouteEdgeType.ANSIBLEX)
        assertEquals(userStyle.color, items[0].style.color)
        assertEquals(userStyle.strokeWidth, items[0].style.referenceStrokeWidth)
        assertEquals(userStyle.dashPattern, items[0].style.dashPattern)

        assertEquals(MISSION_ROUTE_COLORS[0], items[1].style.color)
        assertEquals(5f, items[1].style.referenceStrokeWidth)
        assertEquals(listOf(14f, 5f), items[1].style.dashPattern)

        assertEquals(MISSION_CAPITAL_COLORS[1], items[2].style.color)
        assertEquals(5f, items[2].style.referenceStrokeWidth)
        assertEquals(listOf(4f, 4f), items[2].style.dashPattern)
    }
}
