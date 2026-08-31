package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.control.MissionMapUiState
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.control.mission.MissionRouteId
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.ProjectedStargateEdge
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapVisualEmphasisTest {
    @Test
    fun `no displayed route and no selection preserves normal emphasis for every background layer`() {
        val emphasis = derive()

        assertSame(MapVisualEmphasis.None, emphasis)
        assertFalse(emphasis.isActive)
        assertEquals(1f, emphasis.systemAlphaMultiplier(99))
        assertEquals(1f, emphasis.systemLabelAlphaMultiplier(99))
        assertEquals(1f, emphasis.hierarchyLabelAlphaMultiplier)
        assertEquals(1f, emphasis.stargateAlphaMultiplier(1, 2))
        assertEquals(1f, emphasis.ansiblexAlphaMultiplier("a"))
        assertEquals(1f, emphasis.wormholeAlphaMultiplier("wormhole:1:2"))
    }

    @Test
    fun `selection emphasizes only direct Stargate and visible enabled Ansiblex adjacency`() {
        val emphasis = derive(
            selectedSystemId = 1,
            stargateEdges = listOf(edge(1, 2), edge(1, 3), edge(2, 5)),
            visibleAnsiblexConnections = listOf(
                ansiblex("direct", 1, 4),
                ansiblex("disabled", 1, 6, enabled = false),
                ansiblex("unrelated", 4, 7),
            ),
        )

        assertTrue(emphasis.isActive)
        assertEquals(0, emphasis.activeRouteCount)
        assertEquals(1, emphasis.selectedSystemId)
        assertEquals(listOf(1, 2, 3, 4), emphasis.prioritizedSystemIds)
        assertEquals(setOf(1, 2, 3, 4), emphasis.focusedSystemIds)
        assertEquals(
            setOf(MapSystemConnection.between(1, 2), MapSystemConnection.between(1, 3)),
            emphasis.selectedStargateConnections,
        )
        assertEquals(setOf("direct"), emphasis.selectedAnsiblexConnectionIds)
        assertEquals(1f, emphasis.systemAlphaMultiplier(1))
        assertEquals(1f, emphasis.systemAlphaMultiplier(2))
        assertEquals(1f, emphasis.systemLabelAlphaMultiplier(4))
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, emphasis.systemAlphaMultiplier(5))
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, emphasis.systemAlphaMultiplier(6))
        assertEquals(1f, emphasis.stargateAlphaMultiplier(1, 2))
        assertEquals(ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA, emphasis.stargateAlphaMultiplier(2, 5))
        assertEquals(1f, emphasis.ansiblexAlphaMultiplier("direct"))
        assertEquals(ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA, emphasis.ansiblexAlphaMultiplier("unrelated"))
        assertEquals(ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA, emphasis.ansiblexAlphaMultiplier("disabled"))
        assertEquals(ROUTE_FOCUS_BACKGROUND_HIERARCHY_LABEL_ALPHA, emphasis.hierarchyLabelAlphaMultiplier)
    }

    @Test
    fun `selection does not recursively expand past a direct neighbor`() {
        val emphasis = derive(
            selectedSystemId = 1,
            stargateEdges = listOf(edge(1, 2), edge(2, 3)),
        )

        assertEquals(setOf(1, 2), emphasis.focusedSystemIds)
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, emphasis.systemAlphaMultiplier(3))
        assertEquals(ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA, emphasis.stargateAlphaMultiplier(2, 3))
    }

    @Test
    fun `selection includes direct Wormhole neighbors without recursive expansion`() {
        val emphasis = derive(
            selectedSystemId = 1,
            wormholeConnections = listOf(
                WormholeConnection.between(1, 4),
                WormholeConnection.between(4, 7),
            ),
        )

        assertEquals(listOf(1, 4), emphasis.prioritizedSystemIds)
        assertEquals(setOf(1, 4), emphasis.focusedSystemIds)
        assertEquals(setOf("wormhole:1:4"), emphasis.selectedWormholeConnectionIds)
        assertEquals(1f, emphasis.wormholeAlphaMultiplier("wormhole:1:4"))
        assertEquals(
            ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA,
            emphasis.wormholeAlphaMultiplier("wormhole:4:7"),
        )
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, emphasis.systemAlphaMultiplier(7))
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
        assertEquals(ROUTE_FOCUS_BACKGROUND_CONNECTION_ALPHA, emphasis.stargateAlphaMultiplier(1, 2))
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
    fun `AI normal and capital routes activate route focus`() {
        val normal = normalRoute(20, 21)
        val capital = capitalRoute(30, 31)
        val missionState = MissionMapUiState(
            normalRoutes = listOf(missionNormal(normal, "normal")),
            capitalRoutes = listOf(missionCapital(capital, "capital")),
        )

        val emphasis = derive(missionState = missionState)

        assertTrue(emphasis.isActive)
        assertEquals(2, emphasis.activeRouteCount)
        assertEquals(setOf(20, 21, 30, 31), emphasis.focusedSystemIds)
    }

    @Test
    fun `route and selection compose as a union with selection labels first`() {
        val emphasis = derive(
            userNormalRoute = normalRoute(10, 11),
            selectedSystemId = 1,
            stargateEdges = listOf(edge(1, 2)),
            visibleAnsiblexConnections = listOf(ansiblex("selected-bridge", 1, 3)),
        )

        assertEquals(listOf(1, 2, 3, 10, 11), emphasis.prioritizedSystemIds)
        assertEquals(setOf(1, 2, 3, 10, 11), emphasis.focusedSystemIds)
        assertEquals(1f, emphasis.systemAlphaMultiplier(10))
        assertEquals(1f, emphasis.systemAlphaMultiplier(3))
        assertEquals(1f, emphasis.stargateAlphaMultiplier(1, 2))
        assertEquals(1f, emphasis.ansiblexAlphaMultiplier("selected-bridge"))
    }

    @Test
    fun `clearing selection while route remains restores route-only focus`() {
        val route = normalRoute(10, 11)
        val combined = derive(
            userNormalRoute = route,
            selectedSystemId = 1,
            stargateEdges = listOf(edge(1, 2)),
        )
        val routeOnly = derive(userNormalRoute = route, stargateEdges = listOf(edge(1, 2)))

        assertEquals(setOf(1, 2, 10, 11), combined.focusedSystemIds)
        assertEquals(setOf(10, 11), routeOnly.focusedSystemIds)
        assertTrue(routeOnly.isActive)
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, routeOnly.systemAlphaMultiplier(2))
    }

    @Test
    fun `removing route while selection remains preserves selection-only focus`() {
        val edge = edge(1, 2)
        val combined = derive(
            userNormalRoute = normalRoute(10, 11),
            selectedSystemId = 1,
            stargateEdges = listOf(edge),
        )
        val selectionOnly = derive(selectedSystemId = 1, stargateEdges = listOf(edge))

        assertEquals(setOf(1, 2, 10, 11), combined.focusedSystemIds)
        assertEquals(setOf(1, 2), selectionOnly.focusedSystemIds)
        assertTrue(selectionOnly.isActive)
        assertEquals(1f, selectionOnly.stargateAlphaMultiplier(1, 2))
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, selectionOnly.systemAlphaMultiplier(10))
    }

    @Test
    fun `clearing the only selection restores exact normal emphasis`() {
        val active = derive(selectedSystemId = 1, stargateEdges = listOf(edge(1, 2)))
        val restored = derive(stargateEdges = listOf(edge(1, 2)))

        assertTrue(active.isActive)
        assertSame(MapVisualEmphasis.None, restored)
        assertEquals(1f, restored.systemAlphaMultiplier(3))
        assertEquals(1f, restored.systemLabelAlphaMultiplier(3))
        assertEquals(1f, restored.stargateAlphaMultiplier(1, 2))
    }

    @Test
    fun `multiple user and AI routes remain in the focused union with selection`() {
        val userNormal = normalRoute(10, 11)
        val userCapital = capitalRoute(11, 12)
        val aiNormal = normalRoute(12, 13)
        val aiCapital = capitalRoute(13, 14)
        val missionState = MissionMapUiState(
            normalRoutes = listOf(missionNormal(aiNormal, "ai-normal")),
            capitalRoutes = listOf(missionCapital(aiCapital, "ai-capital")),
        )

        val emphasis = derive(
            userNormalRoute = userNormal,
            userCapitalRoute = userCapital,
            missionState = missionState,
            selectedSystemId = 1,
            stargateEdges = listOf(edge(1, 2)),
        )

        assertEquals(4, emphasis.activeRouteCount)
        assertEquals(setOf(1, 2, 10, 11, 12, 13, 14), emphasis.focusedSystemIds)
        assertEquals(listOf(1, 2, 10, 11, 12, 13, 14), emphasis.prioritizedSystemIds)
        assertEquals(ROUTE_FOCUS_BACKGROUND_SYSTEM_ALPHA, emphasis.systemAlphaMultiplier(15))
    }

    private fun derive(
        userNormalRoute: RouteResult? = null,
        userCapitalRoute: CapitalRouteResult? = null,
        missionState: MissionMapUiState = MissionMapUiState(),
        selectedSystemId: Int? = null,
        stargateEdges: List<ProjectedStargateEdge> = emptyList(),
        visibleAnsiblexConnections: List<AnsiblexConnection> = emptyList(),
        wormholeConnections: List<WormholeConnection> = emptyList(),
    ) = MapVisualEmphasis.fromDisplayedMapState(
        userNormalRoute = userNormalRoute,
        userCapitalRoute = userCapitalRoute,
        missionState = missionState,
        selectedSystemId = selectedSystemId,
        stargateEdges = stargateEdges,
        visibleAnsiblexConnections = visibleAnsiblexConnections,
        wormholeConnections = wormholeConnections,
    )

    private fun edge(first: Int, second: Int) = ProjectedStargateEdge(
        firstSystemId = first,
        secondSystemId = second,
        first = MapPoint(first.toDouble(), 0.0),
        second = MapPoint(second.toDouble(), 0.0),
    )

    private fun ansiblex(id: String, first: Int, second: Int, enabled: Boolean = true) = AnsiblexConnection(
        id = id,
        firstSystemId = minOf(first, second),
        secondSystemId = maxOf(first, second),
        direction = AnsiblexDirection.BIDIRECTIONAL,
        displayName = null,
        notes = null,
        source = AnsiblexSource.MANUAL,
        sourceBatchId = null,
        enabled = enabled,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

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
