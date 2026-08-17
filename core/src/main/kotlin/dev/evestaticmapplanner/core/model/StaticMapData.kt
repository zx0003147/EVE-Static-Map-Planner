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
) {
    init {
        require(systems.map(SolarSystem::id).distinct().size == systems.size) { "Solar system IDs must be unique" }
        require(connections.distinct().size == connections.size) { "Stargate connections must be unique" }
    }
}
