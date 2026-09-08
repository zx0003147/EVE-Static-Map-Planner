package dev.evestaticmapplanner.webpack

import kotlinx.serialization.Serializable

object WebPackSchema {
    const val VERSION: Int = 1
    const val MANIFEST_FILE_NAME: String = "manifest.json"
    const val EXPORT_DIRECTORY_NAME: String = "EVE-Web-Pack"

    fun packFileName(packVersion: String): String = "web-pack-$packVersion.json.gz"
}

@Serializable
data class WebPackDocument(
    val schemaVersion: Int,
    val packVersion: String,
    val generatedAt: String,
    val desktopAppVersion: String,
    val sdeBuild: Long,
    val counts: WebPackCounts,
    val payload: WebPackPayload,
)

@Serializable
data class WebPackManifest(
    val schemaVersion: Int,
    val packVersion: String,
    val generatedAt: String,
    val desktopAppVersion: String,
    val sdeBuild: Long,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val counts: WebPackCounts,
)

@Serializable
data class WebPackCounts(
    val systems: Int,
    val stargateLinks: Int,
    val regions: Int,
    val constellations: Int,
    val ansiblexLinks: Int,
)

@Serializable
data class WebPackPayload(
    val systems: List<WebPackSolarSystem>,
    val stargateLinks: List<WebPackStargateLink>,
    val regions: List<WebPackRegion>,
    val constellations: List<WebPackConstellation>,
    val ansiblexLinks: List<WebPackAnsiblexLink>,
) {
    fun counts(): WebPackCounts = WebPackCounts(
        systems = systems.size,
        stargateLinks = stargateLinks.size,
        regions = regions.size,
        constellations = constellations.size,
        ansiblexLinks = ansiblexLinks.size,
    )
}

@Serializable
data class WebPackSolarSystem(
    val id: Int,
    val name: String,
    val regionId: Int,
    val constellationId: Int,
    val securityStatus: Double,
    val universePosition: WebPackPosition3D,
    val officialPosition: WebPackPosition2D?,
    val effectiveWormholeClassId: Int?,
)

@Serializable
data class WebPackPosition3D(
    val x: Double,
    val y: Double,
    val z: Double,
)

@Serializable
data class WebPackPosition2D(
    val x: Double,
    val y: Double,
)

@Serializable
data class WebPackStargateLink(
    val firstSystemId: Int,
    val secondSystemId: Int,
)

@Serializable
data class WebPackRegion(
    val id: Int,
    val name: String,
)

@Serializable
data class WebPackConstellation(
    val id: Int,
    val regionId: Int,
    val name: String,
)

@Serializable
enum class WebPackAnsiblexDirection {
    BIDIRECTIONAL,
    FIRST_TO_SECOND,
    SECOND_TO_FIRST,
}

@Serializable
data class WebPackAnsiblexLink(
    val id: String,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val direction: WebPackAnsiblexDirection,
    val displayName: String?,
    val enabled: Boolean,
)
