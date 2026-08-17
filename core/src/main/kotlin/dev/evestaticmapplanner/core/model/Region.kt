package dev.evestaticmapplanner.core.model

data class Region(
    val id: Int,
    val name: String,
    val position: UniversePosition,
    val wormholeClassId: Int?,
) {
    init {
        require(id > 0) { "Region ID must be positive" }
        require(name.isNotBlank()) { "Region name must not be blank" }
    }
}
