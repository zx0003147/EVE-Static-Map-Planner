package dev.evestaticmapplanner.jump

import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.jump.JumpRangeColor
import dev.evestaticmapplanner.core.jump.JumpRangePreset
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
    fun `preset and manual ranges create exact instance-owned configurations`() = runTest {
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
        viewModel.selectOrigin(systems.first())

        viewModel.selectPreset(JumpRangePreset.DREADNOUGHT_CARRIER_FAX)
        viewModel.selectColor(JumpRangeColor.RED)
        viewModel.addSelectedOrigin()
        advanceUntilIdle()

        viewModel.selectPreset(JumpRangePreset.COMMAND_CARRIER)
        viewModel.addSelectedOrigin()
        advanceUntilIdle()

        viewModel.selectPreset(JumpRangePreset.LANCER_DREAD)
        viewModel.addSelectedOrigin()
        advanceUntilIdle()

        viewModel.selectPreset(JumpRangePreset.TITAN_SUPERCARRIER)
        viewModel.selectColor(JumpRangeColor.BLUE)
        viewModel.addSelectedOrigin()
        advanceUntilIdle()

        viewModel.selectPreset(JumpRangePreset.CUSTOM)
        viewModel.updateManualRange("6.5")
        viewModel.selectColor(JumpRangeColor.GREEN)
        viewModel.addSelectedOrigin()
        advanceUntilIdle()

        val dread = viewModel.state.value.overlays[0]
        val commandCarrier = viewModel.state.value.overlays[1]
        val lancer = viewModel.state.value.overlays[2]
        val titan = viewModel.state.value.overlays[3]
        val manual = viewModel.state.value.overlays[4]
        assertEquals(7.0, dread.profile.maxRangeLy)
        assertEquals(JumpRangePreset.DREADNOUGHT_CARRIER_FAX, dread.preset)
        assertEquals(JumpRangeColor.RED, dread.displayColor)
        assertEquals(7.5, commandCarrier.profile.maxRangeLy)
        assertEquals(JumpRangePreset.COMMAND_CARRIER, commandCarrier.preset)
        assertEquals(8.0, lancer.profile.maxRangeLy)
        assertEquals(JumpRangePreset.LANCER_DREAD, lancer.preset)
        assertEquals(6.0, titan.profile.maxRangeLy)
        assertEquals(JumpRangePreset.TITAN_SUPERCARRIER, titan.preset)
        assertEquals(JumpRangeColor.BLUE, titan.displayColor)
        assertEquals(6.5, manual.profile.maxRangeLy)
        assertEquals(JumpRangePreset.CUSTOM, manual.preset)
        assertEquals(JumpRangeColor.GREEN, manual.displayColor)

        viewModel.setEnabled(dread.id, false)
        viewModel.remove(titan.id)
        assertEquals(JumpRangeColor.GREEN, viewModel.state.value.overlays.single { it.id == manual.id }.displayColor)
    }

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
        viewModel.selectPreset(JumpRangePreset.CUSTOM)
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
