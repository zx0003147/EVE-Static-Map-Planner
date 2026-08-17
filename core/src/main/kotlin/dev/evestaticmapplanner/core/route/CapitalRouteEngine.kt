package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import java.util.ArrayDeque

data class CapitalRouteLeg(
    val fromSystemId: Int,
    val toSystemId: Int,
    val distanceMeters: Double,
) {
    init {
        require(fromSystemId != toSystemId)
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
    }

    val distanceLy: Double get() = distanceMeters / UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR
}

data class CapitalRouteResult(
    val startSystemId: Int,
    val destinationSystemId: Int,
    val profile: JumpProfile,
    val systems: List<Int>,
    val legs: List<CapitalRouteLeg>,
) {
    init {
        require(systems.isNotEmpty())
        require(systems.first() == startSystemId && systems.last() == destinationSystemId)
        require(systems.size == legs.size + 1)
        legs.forEachIndexed { index, leg ->
            require(leg.fromSystemId == systems[index] && leg.toSystemId == systems[index + 1])
            require(leg.distanceMeters <= profile.maxRangeLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR)
        }
    }

    val totalJumps: Int get() = legs.size
    val totalDistanceLy: Double get() = legs.sumOf(CapitalRouteLeg::distanceLy)
}

enum class CapitalRouteEndpoint { START, DESTINATION }

sealed interface CapitalRouteOutcome {
    data class Found(val route: CapitalRouteResult) : CapitalRouteOutcome
    data class SameSystem(val route: CapitalRouteResult) : CapitalRouteOutcome
    data class Unreachable(val startSystemId: Int, val destinationSystemId: Int) : CapitalRouteOutcome
    data class InvalidEndpoint(val invalid: Set<CapitalRouteEndpoint>) : CapitalRouteOutcome
    data class IneligibleEndpoint(
        val endpoint: CapitalRouteEndpoint,
        val verdict: EligibilityVerdict,
    ) : CapitalRouteOutcome
}

class CapitalRouteEngine(
    private val candidateProvider: CapitalJumpCandidateProvider,
) {
    fun calculate(
        startSystemId: Int,
        destinationSystemId: Int,
        profile: JumpProfile,
    ): CapitalRouteOutcome {
        val invalid = buildSet {
            if (startSystemId !in candidateProvider.systemsById) add(CapitalRouteEndpoint.START)
            if (destinationSystemId !in candidateProvider.systemsById) add(CapitalRouteEndpoint.DESTINATION)
        }
        if (invalid.isNotEmpty()) return CapitalRouteOutcome.InvalidEndpoint(invalid)

        val originVerdict = candidateProvider.evaluateOrigin(startSystemId)
        if (originVerdict !is EligibilityVerdict.Eligible) {
            return CapitalRouteOutcome.IneligibleEndpoint(CapitalRouteEndpoint.START, originVerdict)
        }
        val destinationVerdict = candidateProvider.evaluateDestination(destinationSystemId)
        if (destinationVerdict !is EligibilityVerdict.Eligible) {
            return CapitalRouteOutcome.IneligibleEndpoint(CapitalRouteEndpoint.DESTINATION, destinationVerdict)
        }
        if (startSystemId == destinationSystemId) {
            return CapitalRouteOutcome.SameSystem(
                CapitalRouteResult(startSystemId, destinationSystemId, profile, listOf(startSystemId), emptyList()),
            )
        }

        val queue = ArrayDeque<Int>()
        val predecessor = mutableMapOf<Int, Int>()
        val visited = mutableSetOf(startSystemId)
        queue.addLast(startSystemId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val reachable = candidateProvider.reachableFrom(current, profile).reachableSystemIds
            for (next in reachable) {
                if (!visited.add(next)) continue
                predecessor[next] = current
                if (next == destinationSystemId) {
                    return CapitalRouteOutcome.Found(reconstruct(startSystemId, destinationSystemId, profile, predecessor))
                }
                queue.addLast(next)
            }
        }
        return CapitalRouteOutcome.Unreachable(startSystemId, destinationSystemId)
    }

    private fun reconstruct(
        startSystemId: Int,
        destinationSystemId: Int,
        profile: JumpProfile,
        predecessor: Map<Int, Int>,
    ): CapitalRouteResult {
        val reversedSystems = mutableListOf(destinationSystemId)
        var current = destinationSystemId
        while (current != startSystemId) {
            current = checkNotNull(predecessor[current]) { "Capital route predecessor chain is incomplete" }
            reversedSystems += current
        }
        val systems = reversedSystems.asReversed()
        val legs = systems.zipWithNext { from, to ->
            CapitalRouteLeg(
                fromSystemId = from,
                toSystemId = to,
                distanceMeters = checkNotNull(candidateProvider.distanceMeters(from, to)),
            )
        }
        return CapitalRouteResult(startSystemId, destinationSystemId, profile, systems, legs)
    }
}
