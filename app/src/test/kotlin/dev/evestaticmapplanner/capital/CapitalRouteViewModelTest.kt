package dev.evestaticmapplanner.capital

import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CapitalRouteViewModelTest {
    @Test
    fun `calculates minimum jump route from manual effective range`() = runTest {
        val systems = listOf(
            capitalSystem(30_000_001, "A", 0.0),
            capitalSystem(30_000_002, "B", 4.0),
            capitalSystem(30_000_003, "C", 8.0),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = CapitalRouteViewModel(
            StaticMapRepository { StaticMapData(systems, emptyList()) },
            CapitalSearch(systems),
            this,
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
            searchDebounceMillis = 0,
        )
        advanceUntilIdle()
        viewModel.selectFrom(systems.first())
        viewModel.selectTo(systems.last())
        viewModel.updateManualRange("5")
        viewModel.calculate()
        advanceUntilIdle()

        assertIs<CapitalRouteOutcome.Found>(viewModel.state.value.outcome)
        assertEquals(2, viewModel.state.value.activeRoute?.totalJumps)
        assertEquals(listOf("A", "B", "C"), viewModel.state.value.routeSystemNames)
        assertEquals(30_000_003, viewModel.state.value.calculatedExplicitDestinationSystemId)
        assertTrue(viewModel.state.value.activeRoute!!.legs.all { it.distanceLy <= 5.0 })

        val snapshot = viewModel.planningSnapshot()
        viewModel.restorePlanningSnapshot(CapitalRoutePlanningSnapshot())
        viewModel.restorePlanningSnapshot(snapshot)
        // Restoring a View is deliberately synchronous: the form and route overlay must
        // represent the same View before switchView returns, without another scheduler tick.
        assertEquals(30_000_001, viewModel.state.value.selectedFrom?.id)
        assertEquals(30_000_003, viewModel.state.value.selectedTo?.id)
        assertEquals("5", viewModel.state.value.manualRangeText)
        assertEquals(2, viewModel.state.value.activeRoute?.totalJumps)
        assertEquals(listOf("A", "B", "C"), viewModel.state.value.routeSystemNames)
    }

    @Test
    fun `capital waypoint draft preserves old route through a failed recalculation`() = runTest {
        val systems = listOf(
            capitalSystem(30_000_001, "A", 0.0),
            capitalSystem(30_000_002, "B", 4.0),
            capitalSystem(30_000_003, "C", 8.0),
            capitalSystem(30_000_004, "D", 100.0),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = CapitalRouteViewModel(
            StaticMapRepository { StaticMapData(systems, emptyList()) },
            CapitalSearch(systems),
            this,
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
            searchDebounceMillis = 0,
        )
        advanceUntilIdle()
        viewModel.selectFrom(systems[0])
        viewModel.addRouteWaypoint(systems[0].id)
        assertTrue(viewModel.state.value.waypoints.isEmpty())
        assertTrue(viewModel.state.value.navigationMessage.orEmpty().contains("Adjacent"))
        viewModel.addRouteWaypoint(systems[1].id)
        viewModel.updateManualRange("5")
        viewModel.calculate()
        advanceUntilIdle()
        val calculatedRoute = viewModel.state.value.activeRoute

        assertEquals(systems[1].id, calculatedRoute?.destinationSystemId)
        assertEquals(listOf(systems[1].id), viewModel.state.value.calculatedWaypointSystemIds)
        assertEquals(null, viewModel.state.value.calculatedExplicitDestinationSystemId)
        viewModel.addRouteWaypoint(systems[3].id)
        assertEquals(calculatedRoute, viewModel.state.value.activeRoute)
        assertTrue(viewModel.state.value.isRouteStale)

        viewModel.calculate()
        advanceUntilIdle()

        assertEquals(calculatedRoute, viewModel.state.value.activeRoute)
        assertEquals(listOf(systems[1].id), viewModel.state.value.calculatedWaypointSystemIds)
        assertTrue(viewModel.state.value.isRouteStale)
        assertTrue(viewModel.state.value.navigationMessage.orEmpty().contains("Waypoint D"))
    }

    @Test
    fun `waypoint reorder changes only the capital draft until manual recalculation`() = runTest {
        val systems = listOf(
            capitalSystem(30_000_001, "A", 0.0),
            capitalSystem(30_000_002, "B", 4.0),
            capitalSystem(30_000_003, "C", 8.0),
            capitalSystem(30_000_004, "D", 12.0),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = CapitalRouteViewModel(
            StaticMapRepository { StaticMapData(systems, emptyList()) },
            CapitalSearch(systems),
            this,
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
            searchDebounceMillis = 0,
        )
        advanceUntilIdle()
        viewModel.selectFrom(systems[0])
        viewModel.addRouteWaypoint(systems[1].id)
        viewModel.addRouteWaypoint(systems[2].id)
        viewModel.selectTo(systems[3])
        viewModel.updateManualRange("9")
        viewModel.calculate()
        advanceUntilIdle()
        val calculatedRoute = viewModel.state.value.activeRoute

        viewModel.moveRouteWaypoint(fromIndex = 1, toIndex = 0)

        assertEquals(listOf(systems[2], systems[1]), viewModel.state.value.waypoints)
        assertEquals(calculatedRoute, viewModel.state.value.activeRoute)
        assertEquals(listOf(systems[1].id, systems[2].id), viewModel.state.value.calculatedWaypointSystemIds)
        assertTrue(viewModel.state.value.isRouteStale)

        viewModel.calculate()
        advanceUntilIdle()

        assertEquals(listOf(systems[2].id, systems[1].id), viewModel.state.value.calculatedWaypointSystemIds)
        assertEquals(listOf(systems[0].id, systems[2].id, systems[1].id, systems[3].id), viewModel.state.value.activeRoute?.systems)
        assertFalse(viewModel.state.value.isRouteStale)
    }

    @Test
    fun `capital reorder that creates adjacent duplicates is rejected without staling the route`() = runTest {
        val systems = listOf(
            capitalSystem(30_000_001, "A", 0.0),
            capitalSystem(30_000_002, "B", 4.0),
            capitalSystem(30_000_003, "C", 8.0),
            capitalSystem(30_000_004, "D", 12.0),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = CapitalRouteViewModel(
            StaticMapRepository { StaticMapData(systems, emptyList()) },
            CapitalSearch(systems),
            this,
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
            searchDebounceMillis = 0,
        )
        advanceUntilIdle()
        viewModel.selectFrom(systems[0])
        viewModel.addRouteWaypoint(systems[1].id)
        viewModel.addRouteWaypoint(systems[2].id)
        viewModel.addRouteWaypoint(systems[1].id)
        viewModel.selectTo(systems[3])
        viewModel.updateManualRange("9")
        viewModel.calculate()
        advanceUntilIdle()
        val calculatedRoute = viewModel.state.value.activeRoute

        viewModel.moveRouteWaypoint(fromIndex = 2, toIndex = 1)

        assertEquals(listOf(systems[1], systems[2], systems[1]), viewModel.state.value.waypoints)
        assertEquals(calculatedRoute, viewModel.state.value.activeRoute)
        assertEquals(
            listOf(systems[1].id, systems[2].id, systems[1].id),
            viewModel.state.value.calculatedWaypointSystemIds,
        )
        assertFalse(viewModel.state.value.isRouteStale)
        assertTrue(viewModel.state.value.navigationMessage.orEmpty().contains("Adjacent"))
    }
}

private fun capitalSystem(id: Int, name: String, xLy: Double) = SolarSystem(
    id,
    20_000_001,
    10_000_001,
    name,
    0.0,
    null,
    UniversePosition(xLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR, 0.0, 0.0),
    null,
    1.0,
    null,
    null,
)

private class CapitalSearch(private val systems: List<SolarSystem>) : SystemSearchRepository {
    override fun searchSystems(query: String, limit: Int): List<SolarSystem> =
        systems.filter { it.name.startsWith(query, ignoreCase = true) }.take(limit)
}
