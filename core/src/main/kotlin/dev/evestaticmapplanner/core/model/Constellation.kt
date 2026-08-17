package dev.evestaticmapplanner.core.model

data class Constellation(
    val id: Int,
    val regionId: Int,
    val name: String,
    val position: UniversePosition,
    val wormholeClassId: Int?,
) {
    init {
        require(id > 0) { "Constellation ID must be positive" }
        require(regionId > 0) { "Region ID must be positive" }
        require(name.isNotBlank()) { "Constellation name must not be blank" }
    }
}
