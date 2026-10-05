package dev.evestaticmapplanner.core.forceprojection

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalSnapshot
import dev.evestaticmapplanner.core.sovereignty.AllianceCapitalStatus
import dev.evestaticmapplanner.core.sovereignty.SovereigntySnapshot
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind

enum class ForceProjectionZoneStatus {
    AVAILABLE,
    NO_SOVEREIGNTY_ALLIANCE,
    CAPITAL_UNKNOWN,
    SYSTEM_UNAVAILABLE,
}

/** Compact, immutable runtime derivation. It is rebuilt only when either source snapshot changes. */
data class SystemForceProjectionZone(
    val systemId: Int,
    val ownerAllianceId: Long? = null,
    val capitalSystemId: Int? = null,
    val distanceLy: Double? = null,
    val zone: ForceProjectionZone? = null,
    val status: ForceProjectionZoneStatus,
) {
    init {
        require(systemId > 0) { "Solar system ID must be positive" }
        require(ownerAllianceId == null || ownerAllianceId > 0) { "Alliance ID must be positive" }
        require(capitalSystemId == null || capitalSystemId > 0) { "Capital solar system ID must be positive" }
        if (status == ForceProjectionZoneStatus.AVAILABLE) {
            require(ownerAllianceId != null && capitalSystemId != null && distanceLy != null && zone != null) {
                "Available Force Projection data must be complete"
            }
        } else {
            require(distanceLy == null && zone == null) { "Unavailable Force Projection data cannot assert a Zone" }
        }
    }
}

data class ForceProjectionZoneSnapshot(
    val systemsById: Map<Int, SystemForceProjectionZone> = emptyMap(),
) {
    fun get(systemId: Int): SystemForceProjectionZone? = systemsById[systemId]

    fun isDestinationAllowed(systemId: Int, maximumZone: Int): Boolean {
        require(maximumZone in 1..5) { "Maximum Ansiblex Zone must be between 1 and 5" }
        val zone = systemsById[systemId]?.zone ?: return false
        return zone.number <= maximumZone
    }

    companion object {
        val Empty = ForceProjectionZoneSnapshot()
    }
}

object ForceProjectionZoneSnapshotBuilder {
    fun build(
        systems: Iterable<SolarSystem>,
        sovereignty: SovereigntySnapshot,
        allianceCapitals: AllianceCapitalSnapshot,
    ): ForceProjectionZoneSnapshot {
        val systemList = systems.toList()
        val resolver = ForceProjectionZoneResolver(systemList)
        return ForceProjectionZoneSnapshot(
            systemList.associate { system ->
                val ownership = sovereignty.getOwnership(system.id)
                val allianceId = ownership?.allianceId?.takeIf { ownership.ownerKind == SystemOwnerKind.ALLIANCE }
                val derived = if (allianceId == null) {
                    SystemForceProjectionZone(
                        systemId = system.id,
                        status = ForceProjectionZoneStatus.NO_SOVEREIGNTY_ALLIANCE,
                    )
                } else {
                    val capital = allianceCapitals.recordsByAllianceId[allianceId]
                    val capitalSystemId = capital?.capitalSystemId
                    if (capital?.status != AllianceCapitalStatus.AVAILABLE || capitalSystemId == null) {
                        SystemForceProjectionZone(
                            systemId = system.id,
                            ownerAllianceId = allianceId,
                            status = ForceProjectionZoneStatus.CAPITAL_UNKNOWN,
                        )
                    } else {
                        runCatching { resolver.resolve(allianceId, capitalSystemId, system.id) }
                            .fold(
                                onSuccess = { result ->
                                    SystemForceProjectionZone(
                                        systemId = system.id,
                                        ownerAllianceId = allianceId,
                                        capitalSystemId = capitalSystemId,
                                        distanceLy = result.distanceLy,
                                        zone = result.zone,
                                        status = ForceProjectionZoneStatus.AVAILABLE,
                                    )
                                },
                                onFailure = {
                                    SystemForceProjectionZone(
                                        systemId = system.id,
                                        ownerAllianceId = allianceId,
                                        capitalSystemId = capitalSystemId,
                                        status = ForceProjectionZoneStatus.SYSTEM_UNAVAILABLE,
                                    )
                                },
                            )
                    }
                }
                system.id to derived
            },
        )
    }
}

val ForceProjectionZone.number: Int
    get() = ordinal + 1

val ForceProjectionZone.shortLabel: String
    get() = "Z$number"
