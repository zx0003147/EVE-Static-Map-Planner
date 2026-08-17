package dev.evestaticmapplanner.core.model

data class Stargate(
    val id: Int,
    val fromSystemId: Int,
    val toSystemId: Int,
    val destinationGateId: Int,
    val typeId: Int,
    val position: UniversePosition,
) {
    init {
        require(id > 0 && destinationGateId > 0 && typeId > 0) { "Stargate IDs must be positive" }
        require(fromSystemId > 0 && toSystemId > 0) { "Solar system IDs must be positive" }
        require(id != destinationGateId) { "A stargate cannot target itself" }
        require(fromSystemId != toSystemId) { "A stargate cannot connect a solar system to itself" }
    }
}
