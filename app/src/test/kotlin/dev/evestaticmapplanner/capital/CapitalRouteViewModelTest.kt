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
        assertTrue(viewModel.state.value.activeRoute!!.legs.all { it.distanceLy <= 5.0 })
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
