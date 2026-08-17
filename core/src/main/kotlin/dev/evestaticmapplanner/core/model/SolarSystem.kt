package dev.evestaticmapplanner.core.model

data class SolarSystem(
    val id: Int,
    val constellationId: Int,
    val regionId: Int,
    val name: String,
    val securityStatus: Double,
    val securityClass: String?,
    val position: UniversePosition,
    val schematicPosition: SchematicPosition?,
    val radius: Double,
    val factionId: Int?,
    val wormholeClassId: Int?,
    val effectiveWormholeClassId: Int? = wormholeClassId,
) {
    init {
        require(id > 0) { "Solar system ID must be positive" }
        require(constellationId > 0) { "Constellation ID must be positive" }
        require(regionId > 0) { "Region ID must be positive" }
        require(name.isNotBlank()) { "Solar system name must not be blank" }
        require(securityStatus.isFinite() && securityStatus in -1.0..1.0) {
            "Security status must be finite and between -1.0 and 1.0"
        }
        require(radius.isFinite() && radius >= 0.0) { "Radius must be finite and non-negative" }
    }
}
