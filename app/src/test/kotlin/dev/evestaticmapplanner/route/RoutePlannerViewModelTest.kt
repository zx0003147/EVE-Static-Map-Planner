package dev.evestaticmapplanner.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlannerViewModelTest {
    @Test
    fun `search is debounced supports exact prefix and numeric IDs`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel(this, StandardTestDispatcher(testScheduler), debounce = 100)
        advanceUntilIdle()

        viewModel.updateSystemQuery("A")
        runCurrent()
        assertEquals(0, fixture.search.calls)
        advanceUntilIdle()
        assertEquals(listOf("Alpha"), viewModel.state.value.systemResults.map { it.name })

        viewModel.updateFromQuery("2")
        advanceUntilIdle()
        assertEquals(listOf(2), viewModel.state.value.fromResults.map { it.id })
    }

    @Test
    fun `calculates Stargate route and optional Ansiblex shortcut`() = runTest {
        val fixture = Fixture(withShortcut = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])

        viewModel.calculateRoute()
        assertEquals(3, viewModel.state.value.activeRoute?.totalJumps)
        assertEquals(0, viewModel.state.value.activeRoute?.ansiblexJumps)

        viewModel.setUseAnsiblex(true)
        assertNull(viewModel.state.value.activeRoute)
        viewModel.calculateRoute()
        assertEquals(1, viewModel.state.value.activeRoute?.totalJumps)
        assertEquals(1, viewModel.state.value.activeRoute?.ansiblexJumps)
        assertEquals(listOf("Alpha", "Delta"), viewModel.state.value.routeSystemNames)
    }

    @Test
    fun `connection mutation clears active route and rebuilds graph`() = runTest {
        val fixture = Fixture(withShortcut = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseAnsiblex(true)
        viewModel.calculateRoute()
        assertEquals(1, viewModel.state.value.activeRoute?.totalJumps)

        viewModel.setConnectionEnabled("shortcut", false)
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeRoute)
        viewModel.calculateRoute()
        assertEquals(3, viewModel.state.value.activeRoute?.totalJumps)
    }

    @Test
    fun `planning snapshot restores endpoints options and active route`() = runTest {
        val fixture = Fixture(withShortcut = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(this, dispatcher)
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseAnsiblex(true)
        viewModel.calculateRoute()
        val snapshot = viewModel.planningSnapshot()

        viewModel.restorePlanningSnapshot(NormalRoutePlanningSnapshot())
        assertNull(viewModel.state.value.activeRoute)
        viewModel.restorePlanningSnapshot(snapshot)

        assertEquals(1, viewModel.state.value.selectedFrom?.id)
        assertEquals(4, viewModel.state.value.selectedTo?.id)
        assertTrue(viewModel.state.value.useAnsiblex)
        assertEquals(1, viewModel.state.value.activeRoute?.totalJumps)
    }

    @Test
    fun `user database failure keeps Stargate routing and disables Ansiblex`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = fixture.viewModel(
            this,
            dispatcher,
            ansiblexRepository = null,
            userDatabaseError = "newer schema",
        )
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseAnsiblex(true)
        viewModel.calculateRoute()

        assertFalse(viewModel.state.value.isAnsiblexAvailable)
        assertFalse(viewModel.state.value.useAnsiblex)
        assertEquals(3, viewModel.state.value.activeRoute?.stargateJumps)
        assertIs<RouteCalculationOutcome.Found>(viewModel.state.value.routeOutcome)
    }
}

private class Fixture(withShortcut: Boolean = false) {
    val systems = listOf(
        system(1, "Alpha"),
        system(2, "Bravo"),
        system(3, "Charlie"),
        system(4, "Delta"),
    )
    private val data = StaticMapData(
        systems,
        listOf(
            StargateConnection.between(1, 2),
            StargateConnection.between(2, 3),
            StargateConnection.between(3, 4),
        ),
    )
    val search = FakeSearchRepository(systems)
    private val ansiblex = FakeAnsiblexRepository(
        if (withShortcut) mutableListOf(connection("shortcut", 1, 4)) else mutableListOf(),
    )

    fun viewModel(
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        debounce: Long = 0,
        ansiblexRepository: AnsiblexRepository? = ansiblex,
        userDatabaseError: String? = null,
    ) = RoutePlannerViewModel(
        staticMapRepository = StaticMapRepository { data },
        searchRepository = search,
        ansiblexRepository = ansiblexRepository,
        importService = null,
        userDatabaseError = userDatabaseError,
        scope = scope,
        ioDispatcher = dispatcher,
        searchDebounceMillis = debounce,
    )
}

private class FakeSearchRepository(private val systems: List<SolarSystem>) : SystemSearchRepository {
    var calls = 0
    override fun searchSystems(query: String, limit: Int): List<SolarSystem> {
        calls++
        query.toIntOrNull()?.let { id -> return systems.filter { it.id == id } }
        return systems.filter { it.name.startsWith(query, ignoreCase = true) }.take(limit)
    }
}

private class FakeAnsiblexRepository(private val connections: MutableList<AnsiblexConnection>) : AnsiblexRepository {
    override fun getAll(): List<AnsiblexConnection> = connections.toList()
    override fun addManual(draft: AnsiblexDraft): AnsiblexConnection = error("Not used")
    override fun setEnabled(id: String, enabled: Boolean): Boolean {
        val index = connections.indexOfFirst { it.id == id }
        if (index < 0) return false
        connections[index] = connections[index].copy(enabled = enabled)
        return true
    }
    override fun delete(id: String): Boolean = connections.removeIf { it.id == id }
    override fun clearImported(): Int = remove { it.source == AnsiblexSource.IMPORT }
    override fun clearAll(): Int = remove { true }
    private fun remove(predicate: (AnsiblexConnection) -> Boolean): Int {
        val before = connections.size
        connections.removeAll(predicate)
        return before - connections.size
    }
}

private fun connection(id: String, first: Int, second: Int) = AnsiblexConnection(
    id,
    first,
    second,
    AnsiblexDirection.BIDIRECTIONAL,
    null,
    null,
    AnsiblexSource.MANUAL,
    null,
    true,
    Instant.EPOCH,
    Instant.EPOCH,
)

private fun system(id: Int, name: String) = SolarSystem(
    id = id,
    constellationId = 10,
    regionId = 1,
    name = name,
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, id.toDouble()),
    schematicPosition = null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)
