package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.route.CapitalRouteLeg
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.route.RoutePlannerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentNavigationRoutesAdapterTest {
    @Test
    fun `empty route states are reported without fabricating a route`() {
        val adapter = CurrentNavigationRoutesAdapter(
            normalStateProvider = { RoutePlannerUiState(isLoading = false) },
            capitalStateProvider = { CapitalRouteUiState(isLoading = false) },
            systemProvider = { null },
        )

        val snapshot = adapter.currentRoutes()

        assertEquals(CurrentNavigationRouteStatus.NOT_PLANNED, snapshot.normal.status)
        assertEquals(CurrentNavigationRouteStatus.NOT_PLANNED, snapshot.capital.status)
        assertFalse(snapshot.normal.exists)
        assertFalse(snapshot.capital.exists)
        assertFalse(snapshot.normal.valid)
        assertFalse(snapshot.capital.valid)
    }

    @Test
    fun `normal and capital routes preserve their distinct ordered business data`() {
        val normalRoute = normalRoute()
        val capitalRoute = capitalRoute()
        val systems = TEST_SYSTEMS.associateBy(SolarSystem::id)
        val adapter = CurrentNavigationRoutesAdapter(
            normalStateProvider = {
                RoutePlannerUiState(
                    isLoading = false,
                    activeRoute = normalRoute,
                    routeSystemNames = normalRoute.systems.map { systems.getValue(it).name },
                    calculatedWaypointSystemIds = listOf(B.id),
                    calculatedExplicitDestinationSystemId = C.id,
                )
            },
            capitalStateProvider = {
                CapitalRouteUiState(
                    isLoading = false,
                    activeRoute = capitalRoute,
                    routeSystemNames = capitalRoute.systems.map { systems.getValue(it).name },
                    calculatedWaypointSystemIds = listOf(C.id),
                    calculatedExplicitDestinationSystemId = D.id,
                )
            },
            systemProvider = systems::get,
        )

        val snapshot = adapter.currentRoutes()

        assertEquals(CurrentNavigationRouteType.NORMAL, snapshot.normal.routeType)
        assertEquals(listOf(A.id, B.id, C.id), snapshot.normal.systems.map(CurrentNavigationSystemDto::systemId))
        assertEquals(
            listOf(CurrentNavigationConnectionType.STARGATE, CurrentNavigationConnectionType.ANSIBLEX),
            snapshot.normal.connections.map(CurrentNavigationConnectionDto::type),
        )
        assertEquals(listOf(B.id), snapshot.normal.waypointSystemIds)
        assertTrue(snapshot.normal.valid)
        assertEquals(CurrentNavigationSecurityBand.HIGH_SECURITY, snapshot.normal.systems[0].securityBand)
        assertEquals(CurrentNavigationSecurityBand.LOW_SECURITY, snapshot.normal.systems[1].securityBand)
        assertEquals(CurrentNavigationSecurityBand.NULL_SECURITY, snapshot.normal.systems[2].securityBand)

        assertEquals(CurrentNavigationRouteType.CAPITAL, snapshot.capital.routeType)
        assertEquals(listOf(A.id, C.id, D.id), snapshot.capital.systems.map(CurrentNavigationSystemDto::systemId))
        assertTrue(snapshot.capital.connections.all { it.type == CurrentNavigationConnectionType.CAPITAL_JUMP })
        assertTrue(snapshot.capital.connections.none { it.type == CurrentNavigationConnectionType.ANSIBLEX })
        assertEquals(listOf(1.5, 2.0), snapshot.capital.connections.map(CurrentNavigationConnectionDto::distanceLy))
        assertEquals(5.0, snapshot.capital.effectiveRangeLy)
        assertTrue(snapshot.capital.valid)
    }

    @Test
    fun `each read uses the latest route states and clear never returns the previous route`() {
        var normalState = RoutePlannerUiState(isLoading = false)
        var capitalState = CapitalRouteUiState(isLoading = false)
        var normalReads = 0
        var capitalReads = 0
        val systems = TEST_SYSTEMS.associateBy(SolarSystem::id)
        val adapter = CurrentNavigationRoutesAdapter(
            normalStateProvider = { normalReads++; normalState },
            capitalStateProvider = { capitalReads++; capitalState },
            systemProvider = systems::get,
        )

        assertEquals(0, normalReads)
        assertEquals(0, capitalReads)
        assertFalse(adapter.currentRoutes().normal.exists)

        val normalRoute = normalRoute()
        normalState = normalState.copy(
            activeRoute = normalRoute,
            routeSystemNames = normalRoute.systems.map { systems.getValue(it).name },
        )
        assertEquals(listOf(A.id, B.id, C.id), adapter.currentRoutes().normal.systems.map { it.systemId })

        normalState = normalState.copy(
            activeRoute = null,
            routeSystemNames = emptyList(),
            routeOutcome = RouteCalculationOutcome.Unreachable(A.id, D.id),
        )
        val cleared = adapter.currentRoutes().normal
        assertFalse(cleared.exists)
        assertFalse(cleared.valid)
        assertEquals(CurrentNavigationRouteStatus.UNAVAILABLE, cleared.status)
        assertEquals("CALCULATION_FAILED", cleared.unavailableReason)
        assertEquals(3, normalReads)
        assertEquals(3, capitalReads)

        assertEquals(RouteCalculationOutcome.Unreachable(A.id, D.id), normalState.routeOutcome)
        assertNull(normalState.activeRoute)
        assertFalse(capitalState.isCalculating)
    }

    @Test
    fun `stale and failed routes are never labeled as valid current routes`() {
        val route = normalRoute()
        val adapter = CurrentNavigationRoutesAdapter(
            normalStateProvider = {
                RoutePlannerUiState(
                    isLoading = false,
                    activeRoute = route,
                    routeSystemNames = listOf(A.name, B.name, C.name),
                    isRouteStale = true,
                )
            },
            capitalStateProvider = {
                CapitalRouteUiState(
                    isLoading = false,
                    outcome = CapitalRouteOutcome.Unreachable(A.id, D.id),
                )
            },
            systemProvider = TEST_SYSTEMS.associateBy(SolarSystem::id)::get,
        )

        val snapshot = adapter.currentRoutes()

        assertEquals(CurrentNavigationRouteStatus.STALE, snapshot.normal.status)
        assertTrue(snapshot.normal.exists)
        assertTrue(snapshot.normal.stale)
        assertFalse(snapshot.normal.valid)
        assertEquals("ROUTE_INPUTS_CHANGED", snapshot.normal.unavailableReason)
        assertEquals(CurrentNavigationRouteStatus.UNAVAILABLE, snapshot.capital.status)
        assertFalse(snapshot.capital.exists)
        assertFalse(snapshot.capital.valid)
    }
}

private fun normalRoute() = RouteResult(
    startSystemId = A.id,
    destinationSystemId = C.id,
    systems = listOf(A.id, B.id, C.id),
    edges = listOf(
        edge(A.id, B.id, RouteEdgeType.STARGATE),
        edge(B.id, C.id, RouteEdgeType.ANSIBLEX),
    ),
)

private fun capitalRoute(): CapitalRouteResult {
    val profile = JumpProfile.manual(5.0)
    return CapitalRouteResult(
        startSystemId = A.id,
        destinationSystemId = D.id,
        profile = profile,
        systems = listOf(A.id, C.id, D.id),
        legs = listOf(
            CapitalRouteLeg(A.id, C.id, 1.5 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
            CapitalRouteLeg(C.id, D.id, 2.0 * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
        ),
    )
}

private fun edge(from: Int, to: Int, type: RouteEdgeType) = RouteEdge(
    id = RouteEdgeId("$type:$from:$to"),
    connectionId = RouteConnectionId("$type:$from:$to"),
    fromSystemId = from,
    toSystemId = to,
    type = type,
)

private fun system(id: Int, name: String, securityStatus: Double) = SolarSystem(
    id = id,
    constellationId = 20_000_001,
    regionId = 10_000_001,
    name = name,
    securityStatus = securityStatus,
    securityClass = null,
    position = UniversePosition(0.0, 0.0, 0.0),
    schematicPosition = null,
    radius = 0.0,
    factionId = null,
    wormholeClassId = 7,
)

private val A = system(30_000_001, "A", 0.9)
private val B = system(30_000_002, "B", 0.2)
private val C = system(30_000_003, "C", -0.1)
private val D = system(30_000_004, "D", 0.0)
private val TEST_SYSTEMS = listOf(A, B, C, D)
