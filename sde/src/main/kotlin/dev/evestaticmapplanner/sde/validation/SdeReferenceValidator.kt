package dev.evestaticmapplanner.sde.validation

import dev.evestaticmapplanner.sde.SdeDataSet

data class SdeReferenceValidationReport(
    val regionCount: Int,
    val constellationCount: Int,
    val systemCount: Int,
    val stargateCount: Int,
)

object SdeReferenceValidator {
    fun validate(dataSet: SdeDataSet): SdeReferenceValidationReport {
        require(dataSet.regions.isNotEmpty()) { "No regions were parsed" }
        require(dataSet.constellations.isNotEmpty()) { "No constellations were parsed" }
        require(dataSet.solarSystems.isNotEmpty()) { "No solar systems were parsed" }
        require(dataSet.stargates.isNotEmpty()) { "No stargates were parsed" }

        dataSet.regions.values.forEach { region ->
            region.toDomain()
            requireDistinct("region ${region.id} constellationIDs", region.constellationIDs)
        }
        dataSet.constellations.values.forEach { constellation ->
            constellation.toDomain()
            requireDistinct("constellation ${constellation.id} solarSystemIDs", constellation.solarSystemIDs)
        }
        dataSet.solarSystems.values.forEach { system ->
            system.toDomain()
            requireDistinct("solar system ${system.id} stargateIDs", system.stargateIDs.orEmpty())
        }
        dataSet.stargates.values.forEach { it.toDomain() }

        val constellationsByRegion = dataSet.constellations.values
            .groupBy { it.regionID }
            .mapValues { (_, values) -> values.mapTo(mutableSetOf()) { it.id } }
        for (region in dataSet.regions.values) {
            requireSameIds(
                description = "Region ${region.id} constellation membership",
                declared = region.constellationIDs.toSet(),
                actual = constellationsByRegion[region.id].orEmpty(),
            )
        }
        for (constellation in dataSet.constellations.values) {
            require(dataSet.regions.containsKey(constellation.regionID)) {
                "Constellation ${constellation.id} references missing region ${constellation.regionID}"
            }
        }

        val systemsByConstellation = dataSet.solarSystems.values
            .groupBy { it.constellationID }
            .mapValues { (_, values) -> values.mapTo(mutableSetOf()) { it.id } }
        for (constellation in dataSet.constellations.values) {
            requireSameIds(
                description = "Constellation ${constellation.id} solar system membership",
                declared = constellation.solarSystemIDs.toSet(),
                actual = systemsByConstellation[constellation.id].orEmpty(),
            )
        }
        for (system in dataSet.solarSystems.values) {
            val constellation = dataSet.constellations[system.constellationID]
                ?: error("Solar system ${system.id} references missing constellation ${system.constellationID}")
            require(dataSet.regions.containsKey(system.regionID)) {
                "Solar system ${system.id} references missing region ${system.regionID}"
            }
            require(system.regionID == constellation.regionID) {
                "Solar system ${system.id} has region ${system.regionID}, but constellation " +
                    "${constellation.id} belongs to region ${constellation.regionID}"
            }
        }

        val gatesBySystem = dataSet.stargates.values
            .groupBy { it.solarSystemID }
            .mapValues { (_, values) -> values.mapTo(mutableSetOf()) { it.id } }
        for (system in dataSet.solarSystems.values) {
            requireSameIds(
                description = "Solar system ${system.id} stargate membership",
                declared = system.stargateIDs.orEmpty().toSet(),
                actual = gatesBySystem[system.id].orEmpty(),
            )
        }
        for (gate in dataSet.stargates.values) {
            require(dataSet.solarSystems.containsKey(gate.solarSystemID)) {
                "Stargate ${gate.id} references missing origin system ${gate.solarSystemID}"
            }
            require(dataSet.solarSystems.containsKey(gate.destination.solarSystemID)) {
                "Stargate ${gate.id} references missing destination system ${gate.destination.solarSystemID}"
            }
            val destination = dataSet.stargates[gate.destination.stargateID]
                ?: error("Stargate ${gate.id} references missing destination gate ${gate.destination.stargateID}")
            require(destination.destination.stargateID == gate.id) {
                "Stargate ${gate.id} and ${destination.id} are not reciprocal gate IDs"
            }
            require(destination.solarSystemID == gate.destination.solarSystemID) {
                "Stargate ${gate.id} destination gate ${destination.id} has the wrong origin system"
            }
            require(destination.destination.solarSystemID == gate.solarSystemID) {
                "Stargate ${gate.id} destination gate ${destination.id} does not return to its origin system"
            }
        }

        return SdeReferenceValidationReport(
            regionCount = dataSet.regions.size,
            constellationCount = dataSet.constellations.size,
            systemCount = dataSet.solarSystems.size,
            stargateCount = dataSet.stargates.size,
        )
    }
}

private fun requireDistinct(description: String, values: List<Long>) {
    require(values.size == values.toSet().size) { "$description contains duplicate IDs" }
}

private fun requireSameIds(
    description: String,
    declared: Set<Long>,
    actual: Set<Long>,
) {
    require(declared == actual) {
        val missing = (actual - declared).take(10)
        val extra = (declared - actual).take(10)
        "$description mismatch; missing=$missing, extra=$extra"
    }
}
