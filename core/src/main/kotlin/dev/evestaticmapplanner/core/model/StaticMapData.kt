package dev.evestaticmapplanner.core.model

data class StargateConnection(
    val firstSystemId: Int,
    val secondSystemId: Int,
) {
    init {
        require(firstSystemId > 0 && secondSystemId > 0)
        require(firstSystemId < secondSystemId) { "Stargate connection IDs must be canonical and distinct" }
    }

    companion object {
        fun between(firstSystemId: Int, secondSystemId: Int): StargateConnection {
            require(firstSystemId != secondSystemId) { "A stargate connection cannot be a self-loop" }
            return if (firstSystemId < secondSystemId) {
                StargateConnection(firstSystemId, secondSystemId)
            } else {
                StargateConnection(secondSystemId, firstSystemId)
            }
        }
    }
}

data class StaticMapData(
    val systems: List<SolarSystem>,
    val connections: List<StargateConnection>,
    val regions: List<Region> = emptyList(),
    val constellations: List<Constellation> = emptyList(),
) {
    init {
        require(systems.map(SolarSystem::id).distinct().size == systems.size) { "Solar system IDs must be unique" }
        require(connections.distinct().size == connections.size) { "Stargate connections must be unique" }
        require(regions.map(Region::id).distinct().size == regions.size) { "Region IDs must be unique" }
        require(constellations.map(Constellation::id).distinct().size == constellations.size) {
            "Constellation IDs must be unique"
        }
        require(regions.isEmpty() == constellations.isEmpty()) {
            "Region and constellation hierarchy must either both be present or both be absent"
        }
        if (regions.isNotEmpty()) {
            val regionIds = regions.mapTo(hashSetOf(), Region::id)
            val constellationsById = constellations.associateBy(Constellation::id)
            require(constellations.all { it.regionId in regionIds }) {
                "Every constellation must reference a loaded region"
            }
            require(systems.all { system ->
                val constellation = constellationsById[system.constellationId]
                constellation != null && constellation.regionId == system.regionId && system.regionId in regionIds
            }) {
                "Every solar system must reference its loaded region and constellation"
            }
        }
    }
}
