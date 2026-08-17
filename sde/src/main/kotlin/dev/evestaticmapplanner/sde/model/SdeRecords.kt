package dev.evestaticmapplanner.sde.model

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.Stargate
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SdeLocalizedName(
    val en: String,
)

@Serializable
data class SdePosition(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun toDomain() = UniversePosition(x, y, z)
}

@Serializable
data class SdePosition2D(
    val x: Double,
    val y: Double,
) {
    fun toDomain() = SchematicPosition(x, y)
}

@Serializable
data class SdeRegionRecord(
    @SerialName("_key") val id: Long,
    val name: SdeLocalizedName,
    val position: SdePosition,
    val constellationIDs: List<Long>,
    val wormholeClassID: Long? = null,
) {
    fun toDomain() = Region(
        id = id.toDomainId("region"),
        name = name.en,
        position = position.toDomain(),
        wormholeClassId = wormholeClassID?.toDomainId("wormhole class"),
    )
}

@Serializable
data class SdeConstellationRecord(
    @SerialName("_key") val id: Long,
    val regionID: Long,
    val name: SdeLocalizedName,
    val position: SdePosition,
    val solarSystemIDs: List<Long>,
    val wormholeClassID: Long? = null,
) {
    fun toDomain() = Constellation(
        id = id.toDomainId("constellation"),
        regionId = regionID.toDomainId("region"),
        name = name.en,
        position = position.toDomain(),
        wormholeClassId = wormholeClassID?.toDomainId("wormhole class"),
    )
}

@Serializable
data class SdeSolarSystemRecord(
    @SerialName("_key") val id: Long,
    val constellationID: Long,
    val regionID: Long,
    val name: SdeLocalizedName,
    val securityStatus: Double,
    val securityClass: String? = null,
    val position: SdePosition,
    val position2D: SdePosition2D? = null,
    val radius: Double,
    val factionID: Long? = null,
    val wormholeClassID: Long? = null,
    val stargateIDs: List<Long>? = null,
) {
    fun toDomain() = SolarSystem(
        id = id.toDomainId("solar system"),
        constellationId = constellationID.toDomainId("constellation"),
        regionId = regionID.toDomainId("region"),
        name = name.en,
        securityStatus = securityStatus,
        securityClass = securityClass,
        position = position.toDomain(),
        schematicPosition = position2D?.toDomain(),
        radius = radius,
        factionId = factionID?.toDomainId("faction"),
        wormholeClassId = wormholeClassID?.toDomainId("wormhole class"),
    )
}

@Serializable
data class SdeStargateDestination(
    val solarSystemID: Long,
    val stargateID: Long,
)

@Serializable
data class SdeStargateRecord(
    @SerialName("_key") val id: Long,
    val solarSystemID: Long,
    val destination: SdeStargateDestination,
    val typeID: Long,
    val position: SdePosition,
) {
    fun toDomain() = Stargate(
        id = id.toDomainId("stargate"),
        fromSystemId = solarSystemID.toDomainId("solar system"),
        toSystemId = destination.solarSystemID.toDomainId("solar system"),
        destinationGateId = destination.stargateID.toDomainId("stargate"),
        typeId = typeID.toDomainId("type"),
        position = position.toDomain(),
    )
}

internal fun Long.toDomainId(kind: String): Int {
    require(this in 1..Int.MAX_VALUE.toLong()) { "$kind ID is outside the supported positive Int range: $this" }
    return toInt()
}
