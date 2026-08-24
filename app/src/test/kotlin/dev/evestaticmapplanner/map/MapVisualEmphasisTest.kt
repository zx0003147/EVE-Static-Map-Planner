package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.control.MissionMapUiState
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.control.mission.MissionRouteId
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapVisualEmphasisTest {
    @Test
    fun `no displayed route preserves normal emphasis for every background layer`() {
        val emphasis = derive()

        assertSame(MapVisualEmphasis.None, emphasis)
        assertFalse(emphasis.isActive)
        assertEquals(1f, emphasis.systemAlphaMultiplier(99))
        assertEquals(1f, emphasis.systemLabelAlphaMultiplier(99))
        assertEquals(1f, emphasis.hierarchyLabelAlphaMultiplier)
        assertEquals(1f, emphasis.backgroundConnectionAlphaMultiplier)
    }

    @Test
    fun `user normal route emphasizes its systems and subdues unrelated content`() {
        val route = normalRoute(1, 2, RouteEdgeType.ANSIBLEX)
        val emphasis = derive(userNormalRoute = route)

        assertTrue(emphasis.isActive)
        assertEquals(1, emphasis.activeRouteCount)
        assertEquals(setOf(1, 2), emphasis.focusedSystemIds)
        assertEquals(1f, emphasis.systemAlphaMultiplier(1))
        assertEquals(1f, emphasis.systemLabelAlphaMultiplier(2))
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, emphasis.systemAlphaMultiplier(3))
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_LABEL_ALPHA, emphasis.systemLabelAlphaMultiplier(3))
        assertEquals(ROUTE_FOCUS_BACKGROUND_HIERARCHY_LABEL_ALPHA, emphasis.hierarchyLabelAlphaMultiplier)
        assertEquals(ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA, emphasis.backgroundConnectionAlphaMultiplier)
    }

    @Test
    fun `user capital route activates the same route focus`() {
        val emphasis = derive(userCapitalRoute = capitalRoute(10, 11))

        assertTrue(emphasis.isActive)
        assertEquals(setOf(10, 11), emphasis.focusedSystemIds)
        assertEquals(1f, emphasis.systemAlphaMultiplier(10))
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, emphasis.systemAlphaMultiplier(12))
    }

    @Test
    fun `AI normal route activates route focus`() {
        val route = normalRoute(20, 21)
        val missionState = MissionMapUiState(normalRoutes = listOf(missionNormal(route, "normal")))

        val emphasis = derive(missionState = missionState)

        assertTrue(emphasis.isActive)
        assertEquals(setOf(20, 21), emphasis.focusedSystemIds)
    }

    @Test
    fun `AI capital route activates route focus`() {
        val route = capitalRoute(30, 31)
        val missionState = MissionMapUiState(capitalRoutes = listOf(missionCapital(route, "capital")))

        val emphasis = derive(missionState = missionState)

        assertTrue(emphasis.isActive)
        assertEquals(setOf(30, 31), emphasis.focusedSystemIds)
    }

    @Test
    fun `removing the final route restores the exact normal emphasis`() {
        val active = derive(userNormalRoute = normalRoute(1, 2))
        val restored = derive()

        assertTrue(active.isActive)
        assertSame(MapVisualEmphasis.None, restored)
        assertEquals(1f, restored.systemAlphaMultiplier(3))
        assertEquals(1f, restored.systemLabelAlphaMultiplier(3))
        assertEquals(1f, restored.backgroundConnectionAlphaMultiplier)
    }

    @Test
    fun `all simultaneously displayed user and AI routes contribute to the focused union`() {
        val userNormal = normalRoute(1, 2)
        val userCapital = capitalRoute(2, 3)
        val aiNormal = normalRoute(3, 4)
        val aiCapital = capitalRoute(4, 5)
        val missionState = MissionMapUiState(
            normalRoutes = listOf(missionNormal(aiNormal, "ai-normal")),
            capitalRoutes = listOf(missionCapital(aiCapital, "ai-capital")),
        )

        val emphasis = derive(userNormal, userCapital, missionState)

        assertEquals(4, emphasis.activeRouteCount)
        assertEquals(setOf(1, 2, 3, 4, 5), emphasis.focusedSystemIds)
        assertEquals(1f, emphasis.systemAlphaMultiplier(5))
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, emphasis.systemAlphaMultiplier(6))
    }

    private fun derive(
        userNormalRoute: RouteResult? = null,
        userCapitalRoute: CapitalRouteResult? = null,
        missionState: MissionMapUiState = MissionMapUiState(),
    ) = MapVisualEmphasis.fromDisplayedRoutes(userNormalRoute, userCapitalRoute, missionState)

    private fun normalRoute(
        from: Int,
        to: Int,
        type: RouteEdgeType = RouteEdgeType.STARGATE,
    ): RouteResult {
        val connectionId = RouteConnectionId("${type.name.lowercase()}:$from:$to")
        return RouteResult(
            startSystemId = from,
            destinationSystemId = to,
            systems = listOf(from, to),
            edges = listOf(
                RouteEdge(
                    id = RouteEdgeId("${connectionId.value}:$from:$to"),
                    connectionId = connectionId,
                    fromSystemId = from,
                    toSystemId = to,
                    type = type,
                ),
            ),
        )
    }

    private fun capitalRoute(from: Int, to: Int) = CapitalRouteResult(
        startSystemId = from,
        destinationSystemId = to,
        profile = JumpProfile.manual(5.0),
        systems = listOf(from, to),
        legs = listOf(CapitalRouteLeg(from, to, distanceMeters = 1.0)),
    )

    private fun missionNormal(route: RouteResult, suffix: String) = MissionRoute.Normal(
        missionId = MissionId("mission-$suffix"),
        routeId = MissionRouteId("route-$suffix"),
        route = route,
    )

    private fun missionCapital(route: CapitalRouteResult, suffix: String) = MissionRoute.Capital(
        missionId = MissionId("mission-$suffix"),
        routeId = MissionRouteId("route-$suffix"),
        route = route,
    )
}
