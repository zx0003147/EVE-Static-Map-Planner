package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.route.RoutePlannerUiState
import dev.evestaticmapplanner.shared.model.SharedRouteHandoffType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteHandoffAdaptersTest {
    @Test
    fun `Normal export preserves calculated waypoint intent and directional Ansiblex snapshot`() {
        val edges = listOf(
            RouteEdge(
                RouteEdgeId("route-1"), RouteConnectionId("ansiblex-1"),
                30_000_001, 30_000_003, RouteEdgeType.ANSIBLEX,
            ),
            RouteEdge(
                RouteEdgeId("route-2"), RouteConnectionId("stargate-1"),
                30_000_003, 30_000_004, RouteEdgeType.STARGATE,
            ),
        )
        val route = RouteResult(
            30_000_001,
            30_000_004,
            listOf(30_000_001, 30_000_003, 30_000_004),
            edges,
        )
        val draft = RouteHandoffAdapters.normal(
            RoutePlannerUiState(
                isLoading = false,
                useAnsiblex = true,
                activeRoute = route,
                calculatedWaypointSystemIds = listOf(30_000_003),
            ),
            "sde-2026-09",
        )!!

        assertEquals(SharedRouteHandoffType.NORMAL, draft.type)
        assertEquals(listOf(30_000_003), draft.waypointSystemIds)
        assertEquals(listOf("ANSIBLEX", "STARGATE"), draft.resolvedEdges.map { it.type })
        assertEquals(30_000_001, draft.resolvedEdges.first().fromSystemId)
        assertTrue(draft.useAnsiblex == true)
        assertEquals("sde-2026-09", draft.mapMetadata.universeBuild)
    }

    @Test
    fun `Capital export preserves range profile systems and jump distances`() {
        val profile = JumpProfile.manual(6.0, "carrier-jdc-v")
        val legs = listOf(
            CapitalRouteLeg(30_000_001, 30_000_002, 5.5 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
            CapitalRouteLeg(30_000_002, 30_000_003, 4.5 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
        )
        val route = CapitalRouteResult(
            30_000_001,
            30_000_003,
            profile,
            listOf(30_000_001, 30_000_002, 30_000_003),
            legs,
        )
        val draft = RouteHandoffAdapters.capital(
            CapitalRouteUiState(
                isLoading = false,
                activeRoute = route,
                calculatedWaypointSystemIds = listOf(30_000_002),
            ),
            "sde-2026-09",
        )!!

        assertEquals(SharedRouteHandoffType.CAPITAL, draft.type)
        assertEquals(6.0, draft.capitalRangeLy)
        assertEquals("carrier-jdc-v", draft.jumpProfileId)
        assertEquals(listOf(5.5, 4.5), draft.resolvedEdges.map { it.distanceLy!! })
        assertTrue(draft.resolvedEdges.all { it.type == "CAPITAL" })
        assertNull(draft.useAnsiblex)
    }

    @Test
    fun `route without an active calculated snapshot is not publishable`() {
        assertNull(RouteHandoffAdapters.normal(RoutePlannerUiState(), "sde"))
        assertNull(RouteHandoffAdapters.capital(CapitalRouteUiState(), "sde"))
    }
}
