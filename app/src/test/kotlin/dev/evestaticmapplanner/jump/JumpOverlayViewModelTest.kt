package dev.evestaticmapplanner.jump

import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JumpOverlayViewModelTest {
    @Test
    fun `session overlay workflow adds updates disables removes and intersects`() = runTest {
        val systems = jumpSystems()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = JumpOverlayViewModel(
            StaticMapRepository { StaticMapData(systems, emptyList()) },
            TestSearch(systems),
            this,
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
            searchDebounceMillis = 0,
        )
        advanceUntilIdle()

        viewModel.selectOrigin(systems[0])
        viewModel.updateManualRange("5")
        viewModel.addSelectedOrigin()
        advanceUntilIdle()
        viewModel.selectOrigin(systems[1])
        viewModel.addSelectedOrigin()
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.overlays.size)
        assertEquals(2, viewModel.state.value.coverageCounts.getValue(systems[2].id))
        val first = viewModel.state.value.overlays.first()
        val second = viewModel.state.value.overlays.last()
        viewModel.toggleIntersectionSelection(first.id, true)
        viewModel.toggleIntersectionSelection(second.id, true)
        assertTrue(systems[2].id in viewModel.state.value.intersectionSystemIds)

        viewModel.setEnabled(first.id, false)
        assertFalse(viewModel.state.value.overlays.first().enabled)
        viewModel.updateManualRange("2")
        viewModel.updateWithCurrentRange(second.id)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.overlays.last().reachableSystemIds.size)
        viewModel.remove(first.id)
        assertEquals(1, viewModel.state.value.overlays.size)
        viewModel.clear()
        assertEquals(emptyList(), viewModel.state.value.overlays)
    }
}

private fun jumpSystems() = listOf(
    jumpSystem(30_000_001, "A", 0.0),
    jumpSystem(30_000_002, "B", 3.0),
    jumpSystem(30_000_003, "C", 4.0),
    jumpSystem(30_000_004, "D", 8.0),
)

private fun jumpSystem(id: Int, name: String, xLy: Double) = SolarSystem(
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

private class TestSearch(private val systems: List<SolarSystem>) : SystemSearchRepository {
    override fun searchSystems(query: String, limit: Int): List<SolarSystem> =
        systems.filter { it.name.startsWith(query, ignoreCase = true) }.take(limit)
}
