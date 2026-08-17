package dev.evestaticmapplanner.core.jump

import dev.evestaticmapplanner.core.model.SolarSystem

data class JumpRangeResult(
    val originSystemId: Int,
    val profile: JumpProfile,
    val reachableSystemIds: Set<Int>,
    val originVerdict: EligibilityVerdict,
    val queryStrategy: PositionQueryStrategy?,
)

class CapitalJumpCandidateProvider(
    private val positionIndex: SystemPositionIndex,
    private val eligibilityPolicy: JumpEligibilityPolicy = JumpEligibilityPolicy(),
) {
    val systemsById: Map<Int, SolarSystem> get() = positionIndex.systemsById

    fun reachableFrom(originSystemId: Int, profile: JumpProfile): JumpRangeResult {
        val origin = positionIndex.systemsById[originSystemId]
            ?: return JumpRangeResult(
                originSystemId,
                profile,
                emptySet(),
                EligibilityVerdict.Ineligible("Unknown origin solar system"),
                null,
            )
        val originVerdict = eligibilityPolicy.evaluateOrigin(origin)
        if (originVerdict !is EligibilityVerdict.Eligible) {
            return JumpRangeResult(originSystemId, profile, emptySet(), originVerdict, null)
        }

        val candidates = positionIndex.candidates(origin.position, profile.maxRangeLy)
        val reachable = candidates.systemIds.asSequence()
            .filter { it != originSystemId }
            .mapNotNull(positionIndex.systemsById::get)
            .filter { eligibilityPolicy.evaluateDestination(it) is EligibilityVerdict.Eligible }
            .filter {
                UniverseDistanceCalculator.isWithinRange(origin.position, it.position, profile.maxRangeLy)
            }
            .map(SolarSystem::id)
            .sorted()
            .toCollection(linkedSetOf())
        return JumpRangeResult(originSystemId, profile, reachable, originVerdict, candidates.strategy)
    }

    fun distanceLy(firstSystemId: Int, secondSystemId: Int): Double? {
        return distanceMeters(firstSystemId, secondSystemId)?.div(UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR)
    }

    fun distanceMeters(firstSystemId: Int, secondSystemId: Int): Double? {
        val first = positionIndex.systemsById[firstSystemId] ?: return null
        val second = positionIndex.systemsById[secondSystemId] ?: return null
        return UniverseDistanceCalculator.distanceMeters(first.position, second.position)
    }

    fun evaluateOrigin(systemId: Int): EligibilityVerdict = positionIndex.systemsById[systemId]
        ?.let(eligibilityPolicy::evaluateOrigin)
        ?: EligibilityVerdict.Ineligible("Unknown origin solar system")

    fun evaluateDestination(systemId: Int): EligibilityVerdict = positionIndex.systemsById[systemId]
        ?.let(eligibilityPolicy::evaluateDestination)
        ?: EligibilityVerdict.Ineligible("Unknown destination solar system")
}
