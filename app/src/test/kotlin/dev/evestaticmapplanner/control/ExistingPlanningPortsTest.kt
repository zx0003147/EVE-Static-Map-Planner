package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.identity.CurrentIdentityContext
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.StargateConnection
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExistingPlanningPortsTest {
    @Test
    fun `normal route graph snapshot preserves directed topology and nodes without official coordinates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ansiblex = SnapshotAnsiblexRepository()
        val wormholes = WormholeSessionStore().apply { add(FIRST, FIFTH) }
        val ports = ExistingPlanningPorts(
            StaticMapRepository { snapshotStaticData() },
            ansiblex,
            wormholes,
            currentIdentityContextProvider = { CurrentIdentityContext.manual(TEST_ALLIANCE_ID) },
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
        )

        val stargatesOnly = ports.getNormalRouteGraph(useAnsiblex = false)
        val withAnsiblex = ports.getNormalRouteGraph(useAnsiblex = true)

        assertEquals(1, withAnsiblex.schemaVersion)
        assertEquals(NormalRouteGraphProjection.OFFICIAL_2D, withAnsiblex.projection)
        assertEquals(listOf(FIRST, SECOND, THIRD, FOURTH, FIFTH), withAnsiblex.nodes.map { it.systemId })
        assertEquals(2.0, withAnsiblex.nodes.first { it.systemId == FIRST }.official2dX)
        assertEquals(3.0, withAnsiblex.nodes.first { it.systemId == FIRST }.official2dY)
        with(withAnsiblex.nodes.first { it.systemId == THIRD }) {
            assertNull(official2dX)
            assertNull(official2dY)
        }
        assertEquals(
            listOf(
                Triple(FIRST, SECOND, NormalRouteGraphEdgeType.STARGATE),
                Triple(SECOND, FIRST, NormalRouteGraphEdgeType.STARGATE),
            ),
            stargatesOnly.edges.map { Triple(it.fromSystemId, it.toSystemId, it.type) },
        )
        assertEquals(
            setOf(
                Triple(FIRST, SECOND, NormalRouteGraphEdgeType.STARGATE),
                Triple(SECOND, FIRST, NormalRouteGraphEdgeType.STARGATE),
                Triple(SECOND, THIRD, NormalRouteGraphEdgeType.ANSIBLEX),
                Triple(FOURTH, THIRD, NormalRouteGraphEdgeType.ANSIBLEX),
                Triple(FOURTH, FIFTH, NormalRouteGraphEdgeType.ANSIBLEX),
                Triple(FIFTH, FOURTH, NormalRouteGraphEdgeType.ANSIBLEX),
            ),
            withAnsiblex.edges.mapTo(linkedSetOf()) { Triple(it.fromSystemId, it.toSystemId, it.type) },
        )
        assertTrue(stargatesOnly.edges.none { it.type == NormalRouteGraphEdgeType.ANSIBLEX })
        assertTrue(withAnsiblex.edges.none { setOf(it.fromSystemId, it.toSystemId) == setOf(FIRST, FIFTH) })
        assertEquals(1, ansiblex.readCount)
    }

    @Test
    fun `normal routing reads enabled Ansiblex snapshot without any mutation capability use`() = runTest {
        val repository = ReadOnlyProofAnsiblexRepository()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ports = ExistingPlanningPorts(
            StaticMapRepository { staticData() },
            repository,
            WormholeSessionStore(),
            currentIdentityContextProvider = { CurrentIdentityContext.manual(TEST_ALLIANCE_ID) },
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
    fun `multi-point optimization uses enabled Ansiblex only when requested`() = runTest {
        val repository = ReadOnlyProofAnsiblexRepository()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ports = ExistingPlanningPorts(
            StaticMapRepository { staticData() },
            repository,
            WormholeSessionStore(),
            currentIdentityContextProvider = { CurrentIdentityContext.manual(TEST_ALLIANCE_ID) },
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
        )

        val stargatesOnly = assertIs<MultiPointRouteOptimizationDto.Failed>(
            ports.optimizeMultiPointRoute(FIRST, listOf(SECOND), useAnsiblex = false),
        )
        val withAnsiblex = assertIs<MultiPointRouteOptimizationDto.Succeeded>(
            ports.optimizeMultiPointRoute(FIRST, listOf(SECOND), useAnsiblex = true),
        )

        assertEquals("UNREACHABLE_TARGETS", stargatesOnly.error)
        assertEquals(1, withAnsiblex.totalJumps)
        assertEquals(listOf(SECOND), withAnsiblex.orderedTargets.map(MultiPointRouteTargetDto::systemId))
        assertEquals("System $SECOND", withAnsiblex.orderedTargets.single().systemName)
        assertEquals(1, repository.readCount)
        assertEquals(0, repository.mutationCount)
    }

    @Test
    fun `control routing rejects enabled Ansiblex owned by another alliance`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ports = ExistingPlanningPorts(
            StaticMapRepository { staticData() },
            ReadOnlyProofAnsiblexRepository(),
            WormholeSessionStore(),
            currentIdentityContextProvider = { CurrentIdentityContext.manual(OTHER_ALLIANCE_ID) },
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
        )

        assertIs<RouteCalculationOutcome.Unreachable>(ports.calculateNormalRoute(FIRST, SECOND, true))
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
                TEST_ALLIANCE_ID,
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

private class SnapshotAnsiblexRepository : AnsiblexRepository {
    var readCount = 0

    override fun getAll(): List<AnsiblexConnection> {
        readCount++
        return listOf(
            ansiblex("forward", SECOND, THIRD, AnsiblexDirection.FIRST_TO_SECOND),
            ansiblex("reverse", THIRD, FOURTH, AnsiblexDirection.SECOND_TO_FIRST),
            ansiblex("both", FOURTH, FIFTH, AnsiblexDirection.BIDIRECTIONAL),
            ansiblex("disabled", FIRST, FIFTH, AnsiblexDirection.BIDIRECTIONAL, enabled = false),
        )
    }

    override fun addManual(draft: AnsiblexDraft): AnsiblexConnection = unsupported()
    override fun setEnabled(id: String, enabled: Boolean): Boolean = unsupported()
    override fun delete(id: String): Boolean = unsupported()
    override fun clearImported(): Int = unsupported()
    override fun clearAll(): Int = unsupported()

    private fun <T> unsupported(): T = error("Snapshot repository is read-only")
}

private fun ansiblex(
    id: String,
    firstSystemId: Int,
    secondSystemId: Int,
    direction: AnsiblexDirection,
    enabled: Boolean = true,
) = AnsiblexConnection(
    id,
    firstSystemId,
    secondSystemId,
    direction,
    null,
    null,
    AnsiblexSource.MANUAL,
    null,
    enabled,
    Instant.EPOCH,
    Instant.EPOCH,
    TEST_ALLIANCE_ID,
)

private const val TEST_ALLIANCE_ID = 99_000_001L
private const val OTHER_ALLIANCE_ID = 99_000_002L

private fun staticData() = StaticMapData(
    systems = listOf(
        system(FIRST, 0.0),
        system(SECOND, UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR),
    ),
    connections = emptyList(),
)

private fun snapshotStaticData() = StaticMapData(
    systems = listOf(
        system(FIRST, 0.0, SchematicPosition(2.0e15, -3.0e15)),
        system(SECOND, 1.0),
        system(THIRD, 2.0),
        system(FOURTH, 3.0),
        system(FIFTH, 4.0),
    ),
    connections = listOf(StargateConnection.between(FIRST, SECOND)),
)

private fun system(id: Int, x: Double, schematicPosition: SchematicPosition? = null) = SolarSystem(
    id = id,
    constellationId = 20_000_001,
    regionId = 10_000_001,
    name = "System $id",
    securityStatus = 0.1,
    securityClass = null,
    position = UniversePosition(x, 0.0, 0.0),
    schematicPosition = schematicPosition,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)

private const val FIRST = 30_000_001
private const val SECOND = 30_000_002
private const val THIRD = 30_000_003
private const val FOURTH = 30_000_004
private const val FIFTH = 30_000_005
