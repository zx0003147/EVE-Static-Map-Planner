package dev.evestaticmapplanner.wormhole

import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WormholeViewModelTest {
    @Test
    fun `empty manager loads one reusable system name index`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        assertFalse(fixture.viewModel.state.value.isLoading)
        assertTrue(fixture.viewModel.state.value.connections.isEmpty())
        assertEquals(mapOf(1 to "Alpha", 2 to "Bravo", 3 to "Charlie", 4 to "Delta"), fixture.viewModel.state.value.systemNamesById)
        assertEquals(1, fixture.staticLoadCount)
    }

    @Test
    fun `manager requires two explicit distinct selections and editing text clears selection`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val viewModel = fixture.viewModel

        assertFalse(viewModel.state.value.canAddFromManager)
        viewModel.selectManagerFrom(fixture.systems[0])
        assertFalse(viewModel.state.value.canAddFromManager)
        viewModel.selectManagerTo(fixture.systems[1])
        assertTrue(viewModel.state.value.canAddFromManager)

        viewModel.updateManagerFromQuery("Alph")
        assertNull(viewModel.state.value.selectedManagerFrom)
        assertFalse(viewModel.state.value.canAddFromManager)
    }

    @Test
    fun `manager add updates Store clears form and supports multiple connections from one system`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val viewModel = fixture.viewModel

        viewModel.selectManagerFrom(fixture.systems[0])
        viewModel.selectManagerTo(fixture.systems[1])
        assertEquals(CreateWormholeUiResult.CREATED, viewModel.addFromManager())
        viewModel.selectManagerFrom(fixture.systems[0])
        viewModel.selectManagerTo(fixture.systems[2])
        assertEquals(CreateWormholeUiResult.CREATED, viewModel.addFromManager())
        advanceUntilIdle()

        assertEquals(listOf("wormhole:1:2", "wormhole:1:3"), fixture.store.connections.value.map { it.id })
        assertEquals(2, viewModel.state.value.connections.size)
        assertEquals("", viewModel.state.value.managerFromQuery)
        assertEquals("", viewModel.state.value.managerToQuery)
        assertEquals(WORMHOLE_ADDED_MESSAGE, viewModel.state.value.managerMessage)
    }

    @Test
    fun `manager reports duplicates in either direction without closing or clearing selection`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        fixture.store.add(1, 2)
        advanceUntilIdle()

        fixture.viewModel.selectManagerFrom(fixture.systems[0])
        fixture.viewModel.selectManagerTo(fixture.systems[1])
        assertEquals(CreateWormholeUiResult.ALREADY_EXISTS, fixture.viewModel.addFromManager())
        assertEquals(WORMHOLE_DUPLICATE_MESSAGE, fixture.viewModel.state.value.managerMessage)
        assertEquals(fixture.systems[0], fixture.viewModel.state.value.selectedManagerFrom)

        fixture.viewModel.selectManagerFrom(fixture.systems[1])
        fixture.viewModel.selectManagerTo(fixture.systems[0])
        assertEquals(CreateWormholeUiResult.ALREADY_EXISTS, fixture.viewModel.addFromManager())
        assertEquals(1, fixture.store.connections.value.size)
    }

    @Test
    fun `manager blocks a self loop before Store validation`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        fixture.viewModel.selectManagerFrom(fixture.systems[0])
        fixture.viewModel.selectManagerTo(fixture.systems[0])

        assertFalse(fixture.viewModel.state.value.canAddFromManager)
        assertEquals(SAME_ENDPOINT_MESSAGE, fixture.viewModel.state.value.managerMessage)
        assertEquals(CreateWormholeUiResult.SAME_ENDPOINT, fixture.viewModel.addFromManager())
        assertTrue(fixture.store.connections.value.isEmpty())
    }

    @Test
    fun `presentation rows use names deterministic Store order and fallback IDs`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.store.add(4, 2)
        fixture.store.add(3, 1)
        fixture.store.add(4, 1)
        advanceUntilIdle()

        val rows = WormholePresentationBuilder.rows(
            fixture.viewModel.state.value.connections,
            fixture.viewModel.state.value.systemNamesById - 4,
        )

        assertEquals(listOf("Alpha ↔ Charlie", "Alpha ↔ System 4", "Bravo ↔ System 4"), rows.map { it.canonicalLabel })
        assertEquals(listOf("Charlie", "System 4"), WormholePresentationBuilder.rowsForSystem(1, fixture.viewModel.state.value.connections, fixture.viewModel.state.value.systemNamesById - 4).map { it.otherEndpointName(1) })
    }

    @Test
    fun `remove one preserves all other connections and missing removal is normal feedback`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.store.add(1, 2)
        fixture.store.add(1, 3)
        advanceUntilIdle()

        assertTrue(fixture.viewModel.remove("wormhole:1:2"))
        advanceUntilIdle()
        assertEquals(listOf("wormhole:1:3"), fixture.viewModel.state.value.connections.map { it.id })
        assertFalse(fixture.viewModel.remove("wormhole:1:4"))
        assertEquals(WORMHOLE_MISSING_MESSAGE, fixture.viewModel.state.value.managerMessage)
    }

    @Test
    fun `clear all mutates the same Store and returns the removed count`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.store.add(1, 2)
        fixture.store.add(1, 3)
        advanceUntilIdle()

        assertEquals(2, fixture.viewModel.clearAll())
        advanceUntilIdle()
        assertTrue(fixture.store.connections.value.isEmpty())
        assertTrue(fixture.viewModel.state.value.connections.isEmpty())
    }

    @Test
    fun `quick create fixes the clicked origin searches target and keeps duplicate dialog state`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val viewModel = fixture.viewModel

        viewModel.beginQuickCreate(fixture.systems[0])
        assertEquals("Alpha", viewModel.state.value.quickOrigin?.name)
        assertFalse(viewModel.state.value.canAddFromQuickCreate)
        viewModel.updateQuickToQuery("Bra")
        advanceUntilIdle()
        assertEquals(listOf("Bravo"), viewModel.state.value.quickToResults.map { it.name })
        viewModel.selectQuickTo(fixture.systems[1])
        assertTrue(viewModel.state.value.canAddFromQuickCreate)
        assertEquals(CreateWormholeUiResult.CREATED, viewModel.addFromQuickCreate())
        advanceUntilIdle()

        assertEquals("Alpha", viewModel.state.value.quickOrigin?.name)
        assertEquals(CreateWormholeUiResult.ALREADY_EXISTS, viewModel.addFromQuickCreate())
        assertEquals(WORMHOLE_DUPLICATE_MESSAGE, viewModel.state.value.quickMessage)
    }

    @Test
    fun `quick create blocks self loop and editing target clears old selection`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val viewModel = fixture.viewModel

        viewModel.beginQuickCreate(fixture.systems[0])
        viewModel.selectQuickTo(fixture.systems[0])
        assertFalse(viewModel.state.value.canAddFromQuickCreate)
        assertEquals(SAME_ENDPOINT_MESSAGE, viewModel.state.value.quickMessage)
        viewModel.selectQuickTo(fixture.systems[1])
        assertTrue(viewModel.state.value.canAddFromQuickCreate)
        viewModel.updateQuickToQuery("B")
        assertNull(viewModel.state.value.selectedQuickTo)
        assertFalse(viewModel.state.value.canAddFromQuickCreate)
    }

    private class Fixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val systems = listOf(
            system(1, "Alpha"),
            system(2, "Bravo"),
            system(3, "Charlie"),
            system(4, "Delta"),
        )
        val store = WormholeSessionStore()
        var staticLoadCount = 0
        private val searchRepository = object : SystemSearchRepository {
            override fun searchSystems(query: String, limit: Int): List<SolarSystem> = systems
                .filter { it.name.startsWith(query, ignoreCase = true) || it.id.toString() == query }
                .take(limit)
        }
        val viewModel = WormholeViewModel(
            store = store,
            staticMapRepository = StaticMapRepository {
                staticLoadCount++
                StaticMapData(systems, emptyList())
            },
            searchRepository = searchRepository,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            ioDispatcher = dispatcher,
            searchDebounceMillis = 0,
        )
    }
}

private fun system(id: Int, name: String) = SolarSystem(
    id = id,
    constellationId = 20_000_001,
    regionId = 10_000_001,
    name = name,
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, 0.0),
    schematicPosition = SchematicPosition(id.toDouble(), 0.0),
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)
