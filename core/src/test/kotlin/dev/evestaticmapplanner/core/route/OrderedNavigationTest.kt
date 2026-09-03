package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.jump.jumpTestSystem
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrderedNavigationTest {
    @Test
    fun `normal planner visits required waypoints and removes only segment boundary duplicates`() {
        val graph = orderedGraph(1, 2, 3, 4, 5, gates = listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5))

        val outcome = assertIs<NormalNavigationOutcome.Found>(
            NormalNavigationPlanner().calculate(
                graph,
                NavigationIntent(startSystemId = 1, waypointSystemIds = listOf(3), destinationSystemId = 5),
            ),
        )

        assertEquals(listOf(1, 2, 3, 4, 5), outcome.route.systems)
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5), outcome.route.edges.map { it.fromSystemId to it.toSystemId })
    }

    @Test
    fun `normal options apply consistently to every segment`() {
        val graph = orderedGraph(
            1, 2, 3, 4, 5,
            gates = listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5),
            ansiblex = listOf(orderedAnsiblex("first", 1, 3), orderedAnsiblex("second", 3, 5)),
        )
        val intent = NavigationIntent(1, listOf(3), 5)

        val disabled = assertIs<NormalNavigationOutcome.Found>(
            NormalNavigationPlanner().calculate(graph, intent, RouteOptions(useAnsiblex = false)),
        ).route
        val enabled = assertIs<NormalNavigationOutcome.Found>(
            NormalNavigationPlanner().calculate(graph, intent, RouteOptions(useAnsiblex = true)),
        ).route

        assertEquals(4, disabled.stargateJumps)
        assertEquals(2, enabled.ansiblexJumps)
        assertEquals(listOf(1, 3, 5), enabled.systems)
    }

    @Test
    fun `normal composition retains each mixed edge kind in segment order`() {
        val route = assertIs<NormalNavigationOutcome.Found>(
            NormalNavigationPlanner().calculate(
                orderedGraph(
                    1, 2, 3, 4,
                    gates = listOf(1 to 2),
                    ansiblex = listOf(orderedAnsiblex("last", 3, 4)),
                    wormholes = listOf(WormholeConnection.between(2, 3)),
                ),
                NavigationIntent(1, listOf(2, 3), 4),
                RouteOptions(useAnsiblex = true, useWormholes = true),
            ),
        ).route

        assertEquals(
            listOf(RouteEdgeType.STARGATE, RouteEdgeType.WORMHOLE, RouteEdgeType.ANSIBLEX),
            route.edges.map(RouteEdge::type),
        )
    }

    @Test
    fun `failed later segment identifies its boundaries without exposing a partial route`() {
        val outcome = assertIs<NormalNavigationOutcome.SegmentFailed>(
            NormalNavigationPlanner().calculate(
                orderedGraph(1, 2, 3, gates = listOf(1 to 2)),
                NavigationIntent(1, listOf(2), 3),
            ),
        )

        assertEquals(1, outcome.segment.index)
        assertEquals(2, outcome.segment.fromSystemId)
        assertEquals(3, outcome.segment.toSystemId)
        assertIs<RouteCalculationOutcome.Unreachable>(outcome.cause)
    }

    @Test
    fun `last waypoint is the effective endpoint when destination is omitted`() {
        val outcome = assertIs<NormalNavigationOutcome.Found>(
            NormalNavigationPlanner().calculate(
                orderedGraph(1, 2, 3, gates = listOf(1 to 2, 2 to 3)),
                NavigationIntent(startSystemId = 1, waypointSystemIds = listOf(2, 3)),
            ),
        )

        assertEquals(3, outcome.route.destinationSystemId)
        assertEquals(listOf(1, 2, 3), outcome.route.systems)
    }

    @Test
    fun `non adjacent repeats are retained while adjacent explicit stops are rejected`() {
        val loop = NavigationIntent(startSystemId = 1, waypointSystemIds = listOf(2, 1), destinationSystemId = 3)
        val duplicate = NavigationIntent(startSystemId = 1, waypointSystemIds = listOf(1), destinationSystemId = 3)

        assertEquals(NavigationIntentValidation.Valid, loop.validate())
        assertEquals(listOf(1, 2, 1, 3), loop.explicitStops)
        val invalid = assertIs<NavigationIntentValidation.AdjacentDuplicate>(duplicate.validate())
        assertEquals(1, invalid.systemId)
        assertEquals(0, invalid.firstStopIndex)
    }

    @Test
    fun `missing terminal stop and non positive IDs are invalid intents`() {
        assertEquals(
            NavigationIntentValidation.MissingTerminalStop,
            NavigationIntent(startSystemId = 1).validate(),
        )
        assertEquals(
            NavigationIntentValidation.InvalidSystemId,
            NavigationIntent(startSystemId = 1, waypointSystemIds = listOf(0)).validate(),
        )
    }

    @Test
    fun `capital planner composes ordered legs and reports the failing segment atomically`() {
        val systems = listOf(
            jumpTestSystem(30_000_001, xLy = 0.0),
            jumpTestSystem(30_000_002, xLy = 4.0),
            jumpTestSystem(30_000_003, xLy = 8.0),
            jumpTestSystem(30_000_004, xLy = 12.0),
            jumpTestSystem(30_000_005, xLy = 16.0),
        )
        val planner = CapitalNavigationPlanner(
            CapitalRouteEngine(CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(systems))),
        )

        val found = assertIs<CapitalNavigationOutcome.Found>(
            planner.calculate(
                NavigationIntent(30_000_001, listOf(30_000_003), 30_000_005),
                JumpProfile.manual(5.0),
            ),
        ).route
        assertEquals((30_000_001..30_000_005).toList(), found.systems)
        assertEquals(4, found.totalJumps)
        assertTrue(found.legs.all { it.distanceLy <= 5.0 })

        val failed = assertIs<CapitalNavigationOutcome.SegmentFailed>(
            planner.calculate(
                NavigationIntent(30_000_001, listOf(30_000_003), 30_000_005),
                JumpProfile.manual(3.0),
            ),
        )
        assertEquals(0, failed.segment.index)
        assertIs<CapitalRouteOutcome.Unreachable>(failed.cause)
    }
}

private fun orderedGraph(
    vararg systemIds: Int,
    gates: List<Pair<Int, Int>> = emptyList(),
    ansiblex: List<AnsiblexConnection> = emptyList(),
    wormholes: List<WormholeConnection> = emptyList(),
) = RouteGraphBuilder.build(
    StaticMapData(
        systems = systemIds.map(::orderedSystem),
        connections = gates.map { StargateConnection.between(it.first, it.second) }.distinct(),
    ),
    ansiblex,
    wormholes,
)

private fun orderedSystem(id: Int) = SolarSystem(
    id = id,
    constellationId = 10,
    regionId = 1,
    name = "System $id",
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, 0.0),
    schematicPosition = SchematicPosition(id.toDouble(), 0.0),
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)

private fun orderedAnsiblex(id: String, first: Int, second: Int) = AnsiblexConnection(
    id = id,
    firstSystemId = minOf(first, second),
    secondSystemId = maxOf(first, second),
    direction = AnsiblexDirection.BIDIRECTIONAL,
    displayName = null,
    notes = null,
    source = AnsiblexSource.MANUAL,
    sourceBatchId = null,
    enabled = true,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
)
