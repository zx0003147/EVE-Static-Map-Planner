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
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.wormhole.WormholeSessionStore
import dev.evestaticmapplanner.wormhole.CreateWormholeUiResult
import dev.evestaticmapplanner.wormhole.WormholeViewModel
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
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler), debounce = 100)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.wormholeConnections.isEmpty())

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
        val viewModel = fixture.viewModel(dispatcher)
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
        val viewModel = fixture.viewModel(dispatcher)
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
        val viewModel = fixture.viewModel(dispatcher)
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

    @Test
    fun `preloaded Wormhole snapshot is projected and only used when enabled`() = runTest {
        val fixture = Fixture()
        fixture.wormholes.add(1, 4)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])

        assertEquals(listOf("wormhole:1:4"), viewModel.state.value.wormholeConnections.map { it.id })
        assertFalse(viewModel.state.value.useWormholes)
        viewModel.calculateRoute()
        assertEquals(3, viewModel.state.value.activeRoute?.stargateJumps)
        assertEquals(0, viewModel.state.value.activeRoute?.wormholeJumps)

        viewModel.setUseWormholes(true)
        assertNull(viewModel.state.value.activeRoute)
        assertNull(viewModel.state.value.routeOutcome)
        assertTrue(viewModel.state.value.routeSystemNames.isEmpty())
        viewModel.calculateRoute()
        assertEquals(1, viewModel.state.value.activeRoute?.wormholeJumps)
    }

    @Test
    fun `adding Wormhole rebuilds graph without recalculating an active route`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseWormholes(true)
        viewModel.calculateRoute()
        val originalRoute = viewModel.state.value.activeRoute
        val originalOutcome = viewModel.state.value.routeOutcome

        fixture.wormholes.add(1, 4)
        advanceUntilIdle()

        assertEquals(originalRoute, viewModel.state.value.activeRoute)
        assertEquals(originalOutcome, viewModel.state.value.routeOutcome)
        assertEquals(0, viewModel.state.value.activeRoute?.wormholeJumps)
        viewModel.calculateRoute()
        assertEquals(1, viewModel.state.value.activeRoute?.wormholeJumps)
    }

    @Test
    fun `Ansiblex and Wormhole can be used together`() = runTest {
        val fixture = Fixture(ansiblexConnections = mutableListOf(connection("bridge", 1, 3)))
        fixture.wormholes.add(3, 5)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[4])

        viewModel.setUseWormholes(true)
        viewModel.calculateRoute()
        assertEquals(3, viewModel.state.value.activeRoute?.totalJumps)
        assertEquals(0, viewModel.state.value.activeRoute?.ansiblexJumps)

        viewModel.setUseAnsiblex(true)
        viewModel.calculateRoute()
        assertEquals(listOf(RouteEdgeType.ANSIBLEX, RouteEdgeType.WORMHOLE), viewModel.state.value.activeRoute?.edges?.map { it.type })
    }

    @Test
    fun `removing used Wormhole clears route but preserves planning inputs`() = runTest {
        val fixture = Fixture()
        fixture.wormholes.add(1, 4)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseWormholes(true)
        viewModel.calculateRoute()

        fixture.wormholes.remove("wormhole:1:4")
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeRoute)
        assertNull(viewModel.state.value.routeOutcome)
        assertTrue(viewModel.state.value.routeSystemNames.isEmpty())
        assertEquals(1, viewModel.state.value.selectedFrom?.id)
        assertEquals(4, viewModel.state.value.selectedTo?.id)
        assertTrue(viewModel.state.value.useWormholes)
    }

    @Test
    fun `removing unrelated Wormhole preserves a Wormhole route`() = runTest {
        val fixture = Fixture()
        fixture.wormholes.add(1, 4)
        fixture.wormholes.add(2, 5)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseWormholes(true)
        viewModel.calculateRoute()
        val route = viewModel.state.value.activeRoute

        fixture.wormholes.remove("wormhole:2:5")
        advanceUntilIdle()

        assertEquals(route, viewModel.state.value.activeRoute)
        assertEquals(1, viewModel.state.value.activeRoute?.wormholeJumps)
    }

    @Test
    fun `Wormhole removal preserves routes that do not use Wormholes`() = runTest {
        val fixture = Fixture()
        fixture.wormholes.add(1, 4)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.calculateRoute()
        val route = viewModel.state.value.activeRoute

        fixture.wormholes.clear()
        advanceUntilIdle()

        assertEquals(route, viewModel.state.value.activeRoute)
        assertEquals(3, viewModel.state.value.activeRoute?.stargateJumps)
    }

    @Test
    fun `clearing Store invalidates a route that uses a Wormhole`() = runTest {
        val fixture = Fixture()
        fixture.wormholes.add(1, 4)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseWormholes(true)
        viewModel.calculateRoute()

        fixture.wormholes.clear()
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeRoute)
        assertNull(viewModel.state.value.routeOutcome)
    }

    @Test
    fun `Ansiblex refresh retains Wormhole edges and Wormhole update retains Ansiblex edges`() = runTest {
        val fixture = Fixture(withShortcut = true)
        fixture.wormholes.add(2, 5)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.setConnectionEnabled("shortcut", false)
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[1])
        viewModel.selectTo(fixture.systems[4])
        viewModel.setUseWormholes(true)
        viewModel.calculateRoute()
        assertEquals(1, viewModel.state.value.activeRoute?.wormholeJumps)

        viewModel.setConnectionEnabled("shortcut", true)
        advanceUntilIdle()
        fixture.wormholes.add(3, 5)
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseAnsiblex(true)
        viewModel.calculateRoute()
        assertEquals(1, viewModel.state.value.activeRoute?.ansiblexJumps)
    }

    @Test
    fun `planning snapshot restores Wormhole option against current global topology`() = runTest {
        val fixture = Fixture()
        fixture.wormholes.add(1, 4)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])
        viewModel.setUseWormholes(true)
        viewModel.calculateRoute()
        val snapshot = viewModel.planningSnapshot()

        fixture.wormholes.remove("wormhole:1:4")
        fixture.wormholes.add(1, 3)
        advanceUntilIdle()
        viewModel.restorePlanningSnapshot(snapshot)

        assertTrue(viewModel.state.value.useWormholes)
        assertEquals(listOf("wormhole:1:3"), viewModel.state.value.wormholeConnections.map { it.id })
        assertEquals(2, viewModel.state.value.activeRoute?.totalJumps)
        assertEquals(1, viewModel.state.value.activeRoute?.wormholeJumps)
    }

    @Test
    fun `Ansiblex read failure still builds Stargate and Wormhole graph`() = runTest {
        val fixture = Fixture(ansiblexReadFailure = IllegalStateException("database unavailable"))
        fixture.wormholes.add(1, 4)
        val viewModel = fixture.viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        viewModel.selectFrom(fixture.systems[0])
        viewModel.selectTo(fixture.systems[3])

        assertFalse(viewModel.state.value.isAnsiblexAvailable)
        viewModel.calculateRoute()
        assertEquals(3, viewModel.state.value.activeRoute?.stargateJumps)
        viewModel.setUseWormholes(true)
        viewModel.calculateRoute()
        assertTrue(viewModel.state.value.useWormholes)
        assertEquals(1, viewModel.state.value.activeRoute?.wormholeJumps)
        assertEquals(listOf("wormhole:1:4"), viewModel.state.value.wormholeConnections.map { it.id })
    }

    @Test
    fun `Manager add reaches RoutePlanner through shared Store without recalculating active route`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val routeViewModel = fixture.viewModel(dispatcher)
        val wormholeViewModel = fixture.wormholeUi(dispatcher)
        advanceUntilIdle()

        routeViewModel.selectFrom(fixture.systems[0])
        routeViewModel.selectTo(fixture.systems[3])
        routeViewModel.setUseWormholes(true)
        routeViewModel.calculateRoute()
        assertEquals(3, routeViewModel.state.value.activeRoute?.stargateJumps)

        wormholeViewModel.selectManagerFrom(fixture.systems[0])
        wormholeViewModel.selectManagerTo(fixture.systems[3])
        assertEquals(CreateWormholeUiResult.CREATED, wormholeViewModel.addFromManager())
        advanceUntilIdle()

        assertEquals(listOf("wormhole:1:4"), routeViewModel.state.value.wormholeConnections.map { it.id })
        assertEquals(3, routeViewModel.state.value.activeRoute?.stargateJumps)
        assertEquals(0, routeViewModel.state.value.activeRoute?.wormholeJumps)
        routeViewModel.calculateRoute()
        assertEquals(1, routeViewModel.state.value.activeRoute?.wormholeJumps)
    }

    @Test
    fun `right click add uses shared Store and Manager removal preserves unrelated route then clears used route`() = runTest {
        val fixture = Fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val routeViewModel = fixture.viewModel(dispatcher)
        val wormholeViewModel = fixture.wormholeUi(dispatcher)
        advanceUntilIdle()

        wormholeViewModel.beginQuickCreate(fixture.systems[0])
        wormholeViewModel.selectQuickTo(fixture.systems[3])
        assertEquals(CreateWormholeUiResult.CREATED, wormholeViewModel.addFromQuickCreate())
        fixture.wormholes.add(2, 5)
        advanceUntilIdle()
        routeViewModel.selectFrom(fixture.systems[0])
        routeViewModel.selectTo(fixture.systems[3])
        routeViewModel.setUseWormholes(true)
        routeViewModel.calculateRoute()
        assertEquals(1, routeViewModel.state.value.activeRoute?.wormholeJumps)

        assertTrue(wormholeViewModel.remove("wormhole:2:5"))
        advanceUntilIdle()
        assertEquals(1, routeViewModel.state.value.activeRoute?.wormholeJumps)
        assertTrue(wormholeViewModel.remove("wormhole:1:4"))
        advanceUntilIdle()
        assertNull(routeViewModel.state.value.activeRoute)
    }
}

private class Fixture(
    withShortcut: Boolean = false,
    ansiblexConnections: MutableList<AnsiblexConnection>? = null,
    ansiblexReadFailure: Throwable? = null,
) {
    val systems = listOf(
        system(1, "Alpha"),
        system(2, "Bravo"),
        system(3, "Charlie"),
        system(4, "Delta"),
        system(5, "Echo"),
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
        ansiblexConnections
            ?: if (withShortcut) mutableListOf(connection("shortcut", 1, 4)) else mutableListOf(),
        ansiblexReadFailure,
    )
    val wormholes = WormholeSessionStore()

    fun viewModel(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        debounce: Long = 0,
        ansiblexRepository: AnsiblexRepository? = ansiblex,
        userDatabaseError: String? = null,
        wormholeSessionStore: WormholeSessionStore = wormholes,
    ) = RoutePlannerViewModel(
        staticMapRepository = StaticMapRepository { data },
        searchRepository = search,
        ansiblexRepository = ansiblexRepository,
        importService = null,
        userDatabaseError = userDatabaseError,
        wormholeSessionStore = wormholeSessionStore,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher),
        ioDispatcher = dispatcher,
        searchDebounceMillis = debounce,
    )

    fun wormholeUi(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = WormholeViewModel(
        store = wormholes,
        staticMapRepository = StaticMapRepository { StaticMapData(systems, emptyList()) },
        searchRepository = search,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher),
        ioDispatcher = dispatcher,
        searchDebounceMillis = 0,
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

private class FakeAnsiblexRepository(
    private val connections: MutableList<AnsiblexConnection>,
    private val readFailure: Throwable? = null,
) : AnsiblexRepository {
    override fun getAll(): List<AnsiblexConnection> {
        readFailure?.let { throw it }
        return connections.toList()
    }
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
