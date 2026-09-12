package dev.evestaticmapplanner.core.route

enum class MultiPointRouteOptimizationMethod {
    EXACT_HELD_KARP,
    HEURISTIC,
}

enum class MultiPointRouteOptimizationError {
    INVALID_INPUT,
    TOO_MANY_TARGETS,
    START_SYSTEM_NOT_FOUND,
    TARGET_SYSTEM_NOT_FOUND,
    UNREACHABLE_TARGETS,
    NO_FEASIBLE_ROUTE,
    INTERNAL_OPTIMIZATION_FAILURE,
}

data class MultiPointRouteSegment(
    val fromSystemId: Int,
    val toSystemId: Int,
    val jumps: Int,
)

data class MultiPointRouteOptimizationStats(
    val graphNodes: Int,
    val graphEdges: Int,
    val bfsRuns: Int,
)

sealed interface MultiPointRouteOptimizationOutcome {
    data class Success(
        val startSystemId: Int,
        val inputTargetCount: Int,
        val uniqueTargetCount: Int,
        val startWasTarget: Boolean,
        val method: MultiPointRouteOptimizationMethod,
        val guaranteedOptimal: Boolean,
        val orderedTargetSystemIds: List<Int>,
        val segments: List<MultiPointRouteSegment>,
        val totalJumps: Int,
        val stats: MultiPointRouteOptimizationStats,
    ) : MultiPointRouteOptimizationOutcome

    data class Failure(
        val error: MultiPointRouteOptimizationError,
        val message: String,
        val systemIds: List<Int> = emptyList(),
        val uniqueTargetCount: Int? = null,
        val maximumTargetCount: Int? = null,
    ) : MultiPointRouteOptimizationOutcome
}

/**
 * Stateless open-path optimizer for one fixed start and a free final target.
 *
 * Every allocation made by [optimize] is request-scoped. The class retains no graph, distance matrix, route,
 * BFS tree, or optimization history after the call returns.
 */
class MultiPointRouteOptimizer {
    fun optimize(
        graph: RouteGraph,
        startSystemId: Int,
        targetSystemIds: List<Int>,
    ): MultiPointRouteOptimizationOutcome {
        if (startSystemId <= 0 || targetSystemIds.any { it <= 0 }) {
            return failure(
                MultiPointRouteOptimizationError.INVALID_INPUT,
                "startSystemId and every targetSystemIds value must be positive integers.",
            )
        }

        val startWasTarget = startSystemId in targetSystemIds
        val targets = targetSystemIds.asSequence().filter { it != startSystemId }.distinct().sorted().toList()
        if (targets.isEmpty()) {
            return failure(
                MultiPointRouteOptimizationError.INVALID_INPUT,
                "At least one unique target different from startSystemId is required.",
            )
        }
        if (targets.size > MAX_TARGETS) {
            return MultiPointRouteOptimizationOutcome.Failure(
                error = MultiPointRouteOptimizationError.TOO_MANY_TARGETS,
                message = "At most $MAX_TARGETS unique targets are supported after removing startSystemId.",
                uniqueTargetCount = targets.size,
                maximumTargetCount = MAX_TARGETS,
            )
        }
        if (!graph.containsSystem(startSystemId)) {
            return failure(
                MultiPointRouteOptimizationError.START_SYSTEM_NOT_FOUND,
                "startSystemId $startSystemId is missing from the route graph.",
                listOf(startSystemId),
            )
        }
        val missingTargets = targets.filterNot(graph::containsSystem)
        if (missingTargets.isNotEmpty()) {
            return failure(
                MultiPointRouteOptimizationError.TARGET_SYSTEM_NOT_FOUND,
                "One or more target systems are missing from the route graph.",
                missingTargets,
            )
        }

        val relevantSystemIds = IntArray(targets.size + 1)
        relevantSystemIds[0] = startSystemId
        targets.forEachIndexed { index, systemId -> relevantSystemIds[index + 1] = systemId }
        val indexedGraph = IndexedRouteGraph.from(graph)
        val distances = buildDirectedDistanceMatrix(indexedGraph, relevantSystemIds)
        val unreachableTargets = targets.filterIndexed { index, _ -> distances[0, index + 1] == UNREACHABLE }
        if (unreachableTargets.isNotEmpty()) {
            return failure(
                MultiPointRouteOptimizationError.UNREACHABLE_TARGETS,
                "One or more required targets cannot be reached from startSystemId.",
                unreachableTargets,
            )
        }

        val order = if (targets.size <= EXACT_THRESHOLD) {
            heldKarpOpenPath(distances, targets.size)
        } else {
            heuristicOpenPath(distances, targets)
        } ?: return failure(
            MultiPointRouteOptimizationError.NO_FEASIBLE_ROUTE,
            "No directed route can explicitly visit every target.",
        )

        val orderedTargetSystemIds = order.map(targets::get)
        val missingCoverage = validateCoverage(targets, orderedTargetSystemIds)
        if (missingCoverage != null) {
            return failure(
                MultiPointRouteOptimizationError.INTERNAL_OPTIMIZATION_FAILURE,
                "Optimized route failed the required target coverage check.",
                missingCoverage,
            )
        }

        val matrixIndexes = relevantSystemIds.withIndex().associate { (index, systemId) -> systemId to index }
        val segments = ArrayList<MultiPointRouteSegment>(targets.size)
        var previousSystemId = startSystemId
        var totalJumps = 0
        for (systemId in orderedTargetSystemIds) {
            val jumps = distances[
                checkNotNull(matrixIndexes[previousSystemId]),
                checkNotNull(matrixIndexes[systemId]),
            ]
            if (jumps == UNREACHABLE) {
                return failure(
                    MultiPointRouteOptimizationError.INTERNAL_OPTIMIZATION_FAILURE,
                    "Optimizer emitted an unreachable segment.",
                    listOf(systemId),
                )
            }
            segments += MultiPointRouteSegment(previousSystemId, systemId, jumps)
            totalJumps += jumps
            previousSystemId = systemId
        }

        val exact = targets.size <= EXACT_THRESHOLD
        return MultiPointRouteOptimizationOutcome.Success(
            startSystemId = startSystemId,
            inputTargetCount = targetSystemIds.size,
            uniqueTargetCount = targets.size,
            startWasTarget = startWasTarget,
            method = if (exact) {
                MultiPointRouteOptimizationMethod.EXACT_HELD_KARP
            } else {
                MultiPointRouteOptimizationMethod.HEURISTIC
            },
            guaranteedOptimal = exact,
            orderedTargetSystemIds = orderedTargetSystemIds,
            segments = segments,
            totalJumps = totalJumps,
            stats = MultiPointRouteOptimizationStats(
                graphNodes = indexedGraph.systemIds.size,
                graphEdges = indexedGraph.edgeCount,
                bfsRuns = relevantSystemIds.size,
            ),
        )
    }

    private fun heldKarpOpenPath(distances: DirectedDistanceMatrix, targetCount: Int): IntArray? {
        val stateCount = 1 shl targetCount
        val fullMask = stateCount - 1
        val costs = IntArray(stateCount * targetCount) { INFINITE_COST }
        val predecessors = ByteArray(stateCount * targetCount) { NO_PREDECESSOR }

        for (target in 0 until targetCount) {
            val initialCost = distances[0, target + 1]
            if (initialCost != UNREACHABLE) costs[(1 shl target) * targetCount + target] = initialCost
        }

        for (mask in 1 until stateCount) {
            val stateOffset = mask * targetCount
            for (last in 0 until targetCount) {
                val currentCost = costs[stateOffset + last]
                if (currentCost == INFINITE_COST) continue
                var remaining = fullMask xor mask
                while (remaining != 0) {
                    val bit = remaining and -remaining
                    val next = bit.countTrailingZeroBits()
                    remaining = remaining xor bit
                    val step = distances[last + 1, next + 1]
                    if (step == UNREACHABLE) continue
                    val nextIndex = (mask or bit) * targetCount + next
                    val candidate = currentCost + step
                    val existing = costs[nextIndex]
                    val previous = predecessors[nextIndex].toInt()
                    if (candidate < existing || candidate == existing && (previous < 0 || last < previous)) {
                        costs[nextIndex] = candidate
                        predecessors[nextIndex] = last.toByte()
                    }
                }
            }
        }

        var finalTarget = -1
        var finalCost = INFINITE_COST
        val fullOffset = fullMask * targetCount
        for (target in 0 until targetCount) {
            val cost = costs[fullOffset + target]
            if (cost < finalCost) {
                finalCost = cost
                finalTarget = target
            }
        }
        if (finalTarget < 0) return null

        val order = IntArray(targetCount)
        var orderIndex = targetCount - 1
        var mask = fullMask
        var last = finalTarget
        while (orderIndex >= 0) {
            order[orderIndex--] = last
            val previous = predecessors[mask * targetCount + last].toInt()
            mask = mask xor (1 shl last)
            if (previous < 0) break
            last = previous
        }
        return if (orderIndex == -1) order else null
    }

    private fun heuristicOpenPath(distances: DirectedDistanceMatrix, targetSystemIds: List<Int>): IntArray? {
        val targetCount = targetSystemIds.size
        val candidates = ArrayList<RouteCandidate>(targetCount)
        val seedOrder = (0 until targetCount).sortedWith(
            compareBy<Int>(
                { distances[0, it + 1].takeUnless { distance -> distance == UNREACHABLE } ?: INFINITE_COST },
                { targetSystemIds[it] },
            ),
        )
        for (seed in seedOrder) {
            if (distances[0, seed + 1] == UNREACHABLE) continue
            val route = nearestNeighbor(seed, distances, targetSystemIds) ?: continue
            val cost = routeCost(route, distances)
            if (cost != null) candidates += RouteCandidate(cost, route)
        }
        if (candidates.isEmpty()) return null
        candidates.sortWith { first, second -> compareCandidates(first, second, targetSystemIds) }

        var best: RouteCandidate? = null
        for (index in 0 until minOf(HEURISTIC_LOCAL_STARTS, candidates.size)) {
            val improved = localSearch(candidates[index].route, distances)
            val currentBest = best
            if (currentBest == null || compareCandidates(improved, currentBest, targetSystemIds) < 0) best = improved
        }
        return best?.route
    }

    private fun nearestNeighbor(
        seed: Int,
        distances: DirectedDistanceMatrix,
        targetSystemIds: List<Int>,
    ): IntArray? {
        val targetCount = targetSystemIds.size
        val remaining = BooleanArray(targetCount) { true }
        val route = IntArray(targetCount)
        route[0] = seed
        remaining[seed] = false
        var current = seed
        for (position in 1 until targetCount) {
            var best = -1
            var bestDistance = INFINITE_COST
            var bestSystemId = Int.MAX_VALUE
            for (candidate in 0 until targetCount) {
                if (!remaining[candidate]) continue
                val distance = distances[current + 1, candidate + 1]
                if (distance == UNREACHABLE) continue
                val systemId = targetSystemIds[candidate]
                if (distance < bestDistance || distance == bestDistance && systemId < bestSystemId) {
                    best = candidate
                    bestDistance = distance
                    bestSystemId = systemId
                }
            }
            if (best < 0) return null
            route[position] = best
            remaining[best] = false
            current = best
        }
        return route
    }

    private fun localSearch(initial: IntArray, distances: DirectedDistanceMatrix): RouteCandidate {
        val route = initial.copyOf()
        var currentCost = checkNotNull(routeCost(route, distances))
        val candidate = IntArray(route.size)
        val bestRoute = IntArray(route.size)

        repeat(HEURISTIC_MAX_PASSES) {
            var bestCost = currentCost
            var foundImprovement = false

            for (fromIndex in route.indices) {
                for (insertIndex in route.indices) {
                    if (fromIndex == insertIndex) continue
                    relocate(route, fromIndex, insertIndex, candidate)
                    val candidateCost = routeCost(candidate, distances) ?: continue
                    if (candidateCost < bestCost) {
                        bestCost = candidateCost
                        candidate.copyInto(bestRoute)
                        foundImprovement = true
                    }
                }
            }

            for (first in 0 until route.lastIndex) {
                for (second in first + 1..route.lastIndex) {
                    route.copyInto(candidate)
                    val value = candidate[first]
                    candidate[first] = candidate[second]
                    candidate[second] = value
                    val candidateCost = routeCost(candidate, distances) ?: continue
                    if (candidateCost < bestCost) {
                        bestCost = candidateCost
                        candidate.copyInto(bestRoute)
                        foundImprovement = true
                    }
                }
            }

            if (!foundImprovement) return RouteCandidate(currentCost, route)
            bestRoute.copyInto(route)
            currentCost = bestCost
        }
        return RouteCandidate(currentCost, route)
    }

    private fun routeCost(route: IntArray, distances: DirectedDistanceMatrix): Int? {
        var total = distances[0, route[0] + 1]
        if (total == UNREACHABLE) return null
        for (index in 0 until route.lastIndex) {
            val step = distances[route[index] + 1, route[index + 1] + 1]
            if (step == UNREACHABLE) return null
            total += step
        }
        return total
    }

    private fun relocate(route: IntArray, fromIndex: Int, insertIndex: Int, destination: IntArray) {
        val moved = route[fromIndex]
        var source = 0
        for (destinationIndex in destination.indices) {
            if (destinationIndex == insertIndex) {
                destination[destinationIndex] = moved
            } else {
                if (source == fromIndex) source++
                destination[destinationIndex] = route[source++]
            }
        }
    }

    private fun compareCandidates(
        first: RouteCandidate,
        second: RouteCandidate,
        targetSystemIds: List<Int>,
    ): Int {
        if (first.cost != second.cost) return first.cost.compareTo(second.cost)
        for (index in first.route.indices) {
            val comparison = targetSystemIds[first.route[index]].compareTo(targetSystemIds[second.route[index]])
            if (comparison != 0) return comparison
        }
        return 0
    }

    private data class RouteCandidate(val cost: Int, val route: IntArray)

    companion object {
        const val MAX_TARGETS = 50
        const val EXACT_THRESHOLD = 15
        private const val HEURISTIC_LOCAL_STARTS = 8
        private const val HEURISTIC_MAX_PASSES = 25
        private const val UNREACHABLE = -1
        private const val INFINITE_COST = Int.MAX_VALUE / 4
        private const val NO_PREDECESSOR: Byte = -1
    }
}

internal fun validateCoverage(required: List<Int>, ordered: List<Int>): List<Int>? {
    if (required.size == ordered.size && required.toSet() == ordered.toSet()) return null
    val missing = (required.toSet() - ordered.toSet()).sorted()
    return if (missing.isNotEmpty()) missing else required.sorted()
}

private class DirectedDistanceMatrix(
    private val size: Int,
    private val distances: IntArray,
) {
    operator fun get(from: Int, to: Int): Int = distances[from * size + to]
    operator fun set(from: Int, to: Int, value: Int) {
        distances[from * size + to] = value
    }
}

private fun buildDirectedDistanceMatrix(
    graph: IndexedRouteGraph,
    relevantSystemIds: IntArray,
): DirectedDistanceMatrix {
    val matrix = DirectedDistanceMatrix(
        relevantSystemIds.size,
        IntArray(relevantSystemIds.size * relevantSystemIds.size) { -1 },
    )
    val relevantIndexes = IntArray(relevantSystemIds.size) { index ->
        checkNotNull(graph.indexBySystemId[relevantSystemIds[index]])
    }
    val distances = IntArray(graph.systemIds.size)
    val queue = IntArray(graph.systemIds.size)

    for (sourcePosition in relevantSystemIds.indices) {
        distances.fill(-1)
        var head = 0
        var tail = 0
        var remaining = relevantSystemIds.size - 1
        val sourceIndex = relevantIndexes[sourcePosition]
        distances[sourceIndex] = 0
        matrix[sourcePosition, sourcePosition] = 0
        queue[tail++] = sourceIndex

        while (head < tail && remaining > 0) {
            val current = queue[head++]
            val nextDistance = distances[current] + 1
            for (neighbor in graph.adjacency[current]) {
                if (distances[neighbor] != -1) continue
                distances[neighbor] = nextDistance
                queue[tail++] = neighbor
            }
            for (targetPosition in relevantIndexes.indices) {
                if (matrix[sourcePosition, targetPosition] != -1) continue
                val distance = distances[relevantIndexes[targetPosition]]
                if (distance == -1) continue
                matrix[sourcePosition, targetPosition] = distance
                remaining--
            }
        }
    }
    return matrix
}

private class IndexedRouteGraph(
    val systemIds: IntArray,
    val indexBySystemId: Map<Int, Int>,
    val adjacency: Array<IntArray>,
    val edgeCount: Int,
) {
    companion object {
        fun from(graph: RouteGraph): IndexedRouteGraph {
            val systemIds = graph.systemIds.sorted().toIntArray()
            val indexBySystemId = HashMap<Int, Int>(systemIds.size * 4 / 3 + 1)
            systemIds.forEachIndexed { index, systemId -> indexBySystemId[systemId] = index }
            var edgeCount = 0
            val adjacency = Array(systemIds.size) { index ->
                val systemId = systemIds[index]
                val neighborIds = graph.neighbors(systemId).asSequence()
                    .map(RouteEdge::toSystemId)
                    .distinct()
                    .toList()
                edgeCount += neighborIds.size
                IntArray(neighborIds.size) { neighborIndex -> checkNotNull(indexBySystemId[neighborIds[neighborIndex]]) }
            }
            return IndexedRouteGraph(systemIds, indexBySystemId, adjacency, edgeCount)
        }
    }
}

private fun failure(
    error: MultiPointRouteOptimizationError,
    message: String,
    systemIds: List<Int> = emptyList(),
) = MultiPointRouteOptimizationOutcome.Failure(error, message, systemIds)
