package dev.evestaticmapplanner.core.model

data class Region(
    val id: Int,
    val nameEn: String,
    val position: UniversePosition,
    val wormholeClassId: Int?,
    val nameZh: String? = null,
) {
    /** Canonical compatibility accessor. Region identity and protocols always use English. */
    val name: String get() = nameEn

    init {
        require(id > 0) { "Region ID must be positive" }
        require(nameEn.isNotBlank()) { "Canonical English region name must not be blank" }
    }
}
