package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import dev.evestaticmapplanner.wormhole.WormholeSessionStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExistingPlanningPortsTest {
    @Test
    fun `normal routing reads enabled Ansiblex snapshot without any mutation capability use`() = runTest {
        val repository = ReadOnlyProofAnsiblexRepository()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ports = ExistingPlanningPorts(
            StaticMapRepository { staticData() },
            repository,
            WormholeSessionStore(),
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
        )

        assertIs<RouteCalculationOutcome.Unreachable>(ports.calculateNormalRoute(FIRST, SECOND, false))
        val route = assertIs<RouteCalculationOutcome.Found>(ports.calculateNormalRoute(FIRST, SECOND, true)).route

        assertEquals(1, route.ansiblexJumps)
        assertEquals(1, repository.readCount)
        assertEquals(0, repository.mutationCount)
    }

    @Test
    fun `capital and jump operations reuse existing spatial candidate calculations`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ports = ExistingPlanningPorts(
            StaticMapRepository { staticData() },
            null,
            WormholeSessionStore(),
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
        )

        val capital = assertIs<CapitalRouteOutcome.Found>(
            ports.calculateCapitalRoute(FIRST, SECOND, 5.0),
        ).route
        val range = ports.calculateJumpRange(FIRST, 5.0)

        assertEquals(listOf(FIRST, SECOND), capital.systems)
        assertEquals(setOf(SECOND), range.reachableSystemIds)
        assertTrue(capital.legs.single().distanceLy < 5.0)
    }

    @Test
    fun `normal routing uses current global Wormhole snapshot only when requested`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = WormholeSessionStore()
        val ports = ExistingPlanningPorts(
            StaticMapRepository { staticData() },
            null,
            store,
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
        )

        assertIs<RouteCalculationOutcome.Unreachable>(ports.calculateNormalRoute(FIRST, SECOND, false, false))
        store.add(FIRST, SECOND)
        assertIs<RouteCalculationOutcome.Unreachable>(ports.calculateNormalRoute(FIRST, SECOND, false, false))
        val route = assertIs<RouteCalculationOutcome.Found>(
            ports.calculateNormalRoute(FIRST, SECOND, false, true),
        ).route

        assertEquals(1, route.wormholeJumps)
        assertEquals(0, route.stargateJumps)
        assertEquals(0, route.ansiblexJumps)
    }
}

private class ReadOnlyProofAnsiblexRepository : AnsiblexRepository {
    var readCount = 0
    var mutationCount = 0

    override fun getAll(): List<AnsiblexConnection> {
        readCount++
        return listOf(
            AnsiblexConnection(
                "bridge",
                FIRST,
                SECOND,
                AnsiblexDirection.BIDIRECTIONAL,
                null,
                null,
                AnsiblexSource.MANUAL,
                null,
                true,
                Instant.EPOCH,
                Instant.EPOCH,
            ),
        )
    }

    override fun addManual(draft: AnsiblexDraft): AnsiblexConnection = mutation()
    override fun setEnabled(id: String, enabled: Boolean): Boolean = mutation()
    override fun delete(id: String): Boolean = mutation()
    override fun clearImported(): Int = mutation()
    override fun clearAll(): Int = mutation()

    private fun <T> mutation(): T {
        mutationCount++
        error("Control planning must never mutate Ansiblex")
    }
}

private fun staticData() = StaticMapData(
    systems = listOf(
        system(FIRST, 0.0),
        system(SECOND, UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
    ),
    connections = emptyList(),
)

private fun system(id: Int, x: Double) = SolarSystem(
    id = id,
    constellationId = 20_000_001,
    regionId = 10_000_001,
    name = "System $id",
    securityStatus = 0.1,
    securityClass = null,
    position = UniversePosition(x, 0.0, 0.0),
    schematicPosition = null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)

private const val FIRST = 30_000_001
private const val SECOND = 30_000_002
