package dev.evestaticmapplanner.core.model

data class SolarSystemDetails(
    val system: SolarSystem,
    val region: Region,
    val constellation: Constellation,
    val stargates: List<Stargate>,
) {
    val stargateCount: Int get() = stargates.size
}
