package dev.evestaticmapplanner.core.route

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiPointRouteOptimizerTest {
    private val optimizer = MultiPointRouteOptimizer()

    @Test
    fun `directed distances never invent a reverse route`() {
        val graph = directedGraph(1..2, 1 to 2)

        assertIs<MultiPointRouteOptimizationOutcome.Success>(optimizer.optimize(graph, 1, listOf(2)))
        val failure = assertIs<MultiPointRouteOptimizationOutcome.Failure>(optimizer.optimize(graph, 2, listOf(1)))

        assertEquals(MultiPointRouteOptimizationError.UNREACHABLE_TARGETS, failure.error)
        assertEquals(listOf(1), failure.systemIds)
    }

    @Test
    fun `duplicates are removed and the start target is already covered`() {
        val result = success(
            optimizer.optimize(directedGraph(1..3, 1 to 2, 2 to 3), 1, listOf(3, 1, 2, 3, 1)),
        )

        assertEquals(5, result.inputTargetCount)
        assertEquals(2, result.uniqueTargetCount)
        assertTrue(result.startWasTarget)
        assertEquals(listOf(2, 3), result.orderedTargetSystemIds)
        assertEquals(2, result.totalJumps)
        assertNull(validateCoverage(listOf(2, 3), result.orderedTargetSystemIds))
        assertEquals(3, result.stats.bfsRuns)
    }

    @Test
    fun `exact Held-Karp finds a fixed-start free-end open optimum`() {
        val result = success(
            optimizer.optimize(directedGraph(1..4, 1 to 2, 2 to 3, 3 to 4), 1, listOf(4, 2, 3)),
        )

        assertEquals(MultiPointRouteOptimizationMethod.EXACT_HELD_KARP, result.method)
        assertTrue(result.guaranteedOptimal)
        assertEquals(listOf(2, 3, 4), result.orderedTargetSystemIds)
        assertEquals(3, result.totalJumps)
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4), result.segments.map { it.fromSystemId to it.toSystemId })
    }

    @Test
    fun `heuristic is deterministic and visits more than fifteen targets exactly once`() {
        val graph = directedGraph(1..21, *(1 until 21).map { it to it + 1 }.toTypedArray())
        val targets = (2..21).reversed()

        val first = success(optimizer.optimize(graph, 1, targets.toList()))
        val second = success(optimizer.optimize(graph, 1, targets.toList()))

        assertEquals(MultiPointRouteOptimizationMethod.HEURISTIC, first.method)
        assertFalse(first.guaranteedOptimal)
        assertEquals((2..21).toList(), first.orderedTargetSystemIds)
        assertEquals(20, first.orderedTargetSystemIds.toSet().size)
        assertEquals(first.orderedTargetSystemIds, second.orderedTargetSystemIds)
        assertEquals(first.totalJumps, second.totalJumps)
        assertEquals(21, first.stats.bfsRuns)
    }

    @Test
    fun `unreachable targets fail without partial coverage`() {
        val failure = assertIs<MultiPointRouteOptimizationOutcome.Failure>(
            optimizer.optimize(directedGraph(1..3, 1 to 2), 1, listOf(2, 3)),
        )

        assertEquals(MultiPointRouteOptimizationError.UNREACHABLE_TARGETS, failure.error)
        assertEquals(listOf(3), failure.systemIds)
    }

    @Test
    fun `target limit accepts fifty and rejects fifty-one unique targets`() {
        val graph = directedGraph(1..52, *(1 until 52).map { it to it + 1 }.toTypedArray())

        val accepted = success(optimizer.optimize(graph, 1, (2..51).toList()))
        val rejected = assertIs<MultiPointRouteOptimizationOutcome.Failure>(
            optimizer.optimize(graph, 1, (2..52).toList()),
        )

        assertEquals(50, accepted.uniqueTargetCount)
        assertEquals(MultiPointRouteOptimizationMethod.HEURISTIC, accepted.method)
        assertEquals(MultiPointRouteOptimizationError.TOO_MANY_TARGETS, rejected.error)
        assertEquals(51, rejected.uniqueTargetCount)
        assertEquals(50, rejected.maximumTargetCount)
    }

    @Test
    fun `coverage validation rejects missing and duplicate targets`() {
        assertEquals(listOf(3), validateCoverage(listOf(1, 2, 3), listOf(1, 2)))
        assertEquals(listOf(3), validateCoverage(listOf(1, 2, 3), listOf(1, 2, 2)))
    }

    private fun success(outcome: MultiPointRouteOptimizationOutcome) =
        assertIs<MultiPointRouteOptimizationOutcome.Success>(outcome)
}

private fun directedGraph(systemIds: IntRange, vararg edges: Pair<Int, Int>): RouteGraph {
    val routeEdges = edges.mapIndexed { index, (from, to) ->
        RouteEdge(
            id = RouteEdgeId("edge-$index"),
            connectionId = RouteConnectionId("connection-$index"),
            fromSystemId = from,
            toSystemId = to,
            type = RouteEdgeType.STARGATE,
        )
    }
    return RouteGraph(systemIds.toSet(), routeEdges.groupBy(RouteEdge::fromSystemId))
}
