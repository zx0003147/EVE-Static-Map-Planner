package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NormalRouteEngineTest {
    private val engine = NormalRouteEngine()

    @Test
    fun `same system returns a zero jump route`() {
        val outcome = engine.calculate(graph(1), 1, 1)

        val route = assertIs<RouteCalculationOutcome.SameSystem>(outcome).route
        assertEquals(listOf(1), route.systems)
        assertEquals(0, route.totalJumps)
    }

    @Test
    fun `invalid endpoints are distinct from an unreachable route`() {
        val graph = graph(1, 2)

        val invalid = assertIs<RouteCalculationOutcome.InvalidEndpoint>(engine.calculate(graph, 99, 98))
        assertEquals(setOf(RouteEndpoint.START, RouteEndpoint.DESTINATION), invalid.invalid)
        assertIs<RouteCalculationOutcome.Unreachable>(engine.calculate(graph, 1, 2))
    }

    @Test
    fun `finds direct and multi hop stargate routes`() {
        val graph = graph(1, 2, 3, gates = listOf(1 to 2, 2 to 3))

        val direct = found(engine.calculate(graph, 1, 2))
        val multi = found(engine.calculate(graph, 1, 3))

        assertEquals(listOf(1, 2), direct.systems)
        assertEquals(listOf(1, 2, 3), multi.systems)
        assertEquals(2, multi.stargateJumps)
        assertEquals(0, multi.ansiblexJumps)
    }

    @Test
    fun `selects the fewest jumps`() {
        val graph = graph(
            1, 2, 3, 4, 5,
            gates = listOf(1 to 2, 2 to 4, 1 to 3, 3 to 5, 5 to 4),
        )

        assertEquals(listOf(1, 2, 4), found(engine.calculate(graph, 1, 4)).systems)
    }

    @Test
    fun `Ansiblex shortcut is used only when enabled in route options`() {
        val graph = graph(
            1, 2, 3, 4,
            gates = listOf(1 to 2, 2 to 3, 3 to 4),
            ansiblex = listOf(ansiblex("shortcut", 1, 4)),
        )

        val without = found(engine.calculate(graph, 1, 4, RouteOptions(useAnsiblex = false)))
        val with = found(engine.calculate(graph, 1, 4, RouteOptions(useAnsiblex = true)))

        assertEquals(3, without.totalJumps)
        assertEquals(0, without.ansiblexJumps)
        assertEquals(1, with.totalJumps)
        assertEquals(1, with.ansiblexJumps)
    }

    @Test
    fun `disabled and directional Ansiblex connections are respected`() {
        val graph = graph(
            1, 2, 3,
            ansiblex = listOf(
                ansiblex("forward", 1, 2, AnsiblexDirection.FIRST_TO_SECOND),
                ansiblex("disabled", 2, 3, enabled = false),
            ),
        )

        assertEquals(listOf(1, 2), found(engine.calculate(graph, 1, 2, RouteOptions(true))).systems)
        assertIs<RouteCalculationOutcome.Unreachable>(engine.calculate(graph, 2, 1, RouteOptions(true)))
        assertIs<RouteCalculationOutcome.Unreachable>(engine.calculate(graph, 2, 3, RouteOptions(true)))
    }

    @Test
    fun `tie breaking is deterministic and cycles terminate`() {
        val graph = graph(
            1, 2, 3, 4,
            gates = listOf(1 to 3, 3 to 4, 1 to 2, 2 to 4, 2 to 3),
        )

        val routes = List(20) { found(engine.calculate(graph, 1, 4)).systems }

        assertTrue(routes.all { it == listOf(1, 2, 4) })
    }

    @Test
    fun `parallel Stargate wins the deterministic edge tie`() {
        val graph = graph(
            1, 2,
            gates = listOf(1 to 2),
            ansiblex = listOf(ansiblex("parallel", 1, 2)),
        )

        val route = found(engine.calculate(graph, 1, 2, RouteOptions(true)))

        assertEquals(RouteEdgeType.STARGATE, route.edges.single().type)
    }

    @Test
    fun `route graph and engine do not require map coordinates`() {
        val data = StaticMapData(
            systems = listOf(system(1, schematic = false), system(2, schematic = false)),
            connections = listOf(StargateConnection.between(1, 2)),
        )

        assertEquals(1, found(engine.calculate(RouteGraphBuilder.build(data), 1, 2)).totalJumps)
    }
}

private fun found(outcome: RouteCalculationOutcome): RouteResult =
    assertIs<RouteCalculationOutcome.Found>(outcome).route

private fun graph(
    vararg systemIds: Int,
    gates: List<Pair<Int, Int>> = emptyList(),
    ansiblex: List<AnsiblexConnection> = emptyList(),
) = RouteGraphBuilder.build(
    StaticMapData(
        systems = systemIds.map(::system),
        connections = gates.map { StargateConnection.between(it.first, it.second) }.distinct(),
    ),
    ansiblex,
)

private fun ansiblex(
    id: String,
    first: Int,
    second: Int,
    direction: AnsiblexDirection = AnsiblexDirection.BIDIRECTIONAL,
    enabled: Boolean = true,
) = AnsiblexConnection(
    id = id,
    firstSystemId = minOf(first, second),
    secondSystemId = maxOf(first, second),
    direction = direction,
    displayName = null,
    notes = null,
    source = AnsiblexSource.MANUAL,
    sourceBatchId = null,
    enabled = enabled,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
)

private fun system(id: Int, schematic: Boolean = true) = SolarSystem(
    id = id,
    constellationId = 10,
    regionId = 1,
    name = "System $id",
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, id.toDouble()),
    schematicPosition = if (schematic) SchematicPosition(id.toDouble(), id.toDouble()) else null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)
