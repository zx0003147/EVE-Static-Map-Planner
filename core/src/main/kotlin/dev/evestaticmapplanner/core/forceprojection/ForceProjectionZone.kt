package dev.evestaticmapplanner.core.forceprojection

import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.model.SolarSystem

enum class ForceProjectionZone {
    ZONE_1,
    ZONE_2,
    ZONE_3,
    ZONE_4,
    ZONE_5,
    ;

    companion object {
        fun fromDistanceLy(distanceLy: Double): ForceProjectionZone {
            require(distanceLy.isFinite() && distanceLy >= 0.0) {
                "Force projection distance must be finite and non-negative"
            }
            return when {
                distanceLy <= 5.0 -> ZONE_1
                distanceLy <= 10.0 -> ZONE_2
                distanceLy <= 15.0 -> ZONE_3
                distanceLy <= 20.0 -> ZONE_4
                else -> ZONE_5
            }
        }
    }
}

data class ForceProjectionZoneResult(
    val allianceId: Long,
    val capitalSystemId: Int,
    val systemId: Int,
    val distanceLy: Double,
    val zone: ForceProjectionZone,
) {
    init {
        require(allianceId > 0) { "Alliance ID must be positive" }
        require(capitalSystemId > 0) { "Capital solar system ID must be positive" }
        require(systemId > 0) { "Solar system ID must be positive" }
        require(distanceLy.isFinite() && distanceLy >= 0.0) {
            "Force projection distance must be finite and non-negative"
        }
        require(zone == ForceProjectionZone.fromDistanceLy(distanceLy)) {
            "Force projection zone must match its distance"
        }
    }
}

/** Pure runtime derivation over static-universe positions; no Zone values are persisted on [SolarSystem]. */
class ForceProjectionZoneResolver(systems: Iterable<SolarSystem>) {
    private val systemsById: Map<Int, SolarSystem>

    init {
        val systemList = systems.toList()
        systemsById = systemList.associateBy(SolarSystem::id)
        require(systemsById.size == systemList.size) {
            "Force projection universe must not contain duplicate system IDs"
        }
    }

    fun resolve(allianceId: Long, capitalSystemId: Int, systemId: Int): ForceProjectionZoneResult {
        require(allianceId > 0) { "Alliance ID must be positive" }
        val capital = requireNotNull(systemsById[capitalSystemId]) {
            "Unknown capital solar system ID: $capitalSystemId"
        }
        val system = requireNotNull(systemsById[systemId]) { "Unknown solar system ID: $systemId" }
        val distanceLy = UniverseDistanceCalculator.distanceLy(capital.position, system.position)
        return ForceProjectionZoneResult(
            allianceId = allianceId,
            capitalSystemId = capitalSystemId,
            systemId = systemId,
            distanceLy = distanceLy,
            zone = ForceProjectionZone.fromDistanceLy(distanceLy),
        )
    }

    fun resolveAll(allianceId: Long, capitalSystemId: Int): Map<Int, ForceProjectionZoneResult> =
        systemsById.keys.sorted().associateWith { systemId -> resolve(allianceId, capitalSystemId, systemId) }
}
