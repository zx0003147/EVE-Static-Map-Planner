package dev.evestaticmapplanner.web

data class WebPackDocumentDto(
    val schemaVersion: Int,
    val packVersion: String,
    val desktopAppVersion: String,
    val sdeBuild: Long,
    val systems: List<WebPackSystemDto>,
    val stargates: List<WebPackStargateDto>,
    val regions: List<WebPackRegionDto>,
    val constellations: List<WebPackConstellationDto>,
    val ansiblex: List<WebPackAnsiblexDto>,
)

data class WebPackSystemDto(
    val id: Int,
    val name: String,
    val regionId: Int,
    val constellationId: Int,
    val securityStatus: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val officialX: Double?,
    val officialY: Double?,
    val effectiveWormholeClassId: Int?,
)

data class WebPackStargateDto(val firstSystemId: Int, val secondSystemId: Int)
data class WebPackRegionDto(val id: Int, val name: String)
data class WebPackConstellationDto(val id: Int, val regionId: Int, val name: String)

enum class WebPackAnsiblexDirectionDto { BIDIRECTIONAL, FIRST_TO_SECOND, SECOND_TO_FIRST }

data class WebPackAnsiblexDto(
    val id: String,
    val firstSystemId: Int,
    val secondSystemId: Int,
    val direction: WebPackAnsiblexDirectionDto,
    val displayName: String?,
    val enabled: Boolean,
)

internal fun parseWebPackDocument(root: dynamic): WebPackDocumentDto {
    val payload = requiredObject(root.payload, "payload")
    return WebPackDocumentDto(
        schemaVersion = requiredInt(root.schemaVersion, "schemaVersion"),
        packVersion = requiredString(root.packVersion, "packVersion"),
        desktopAppVersion = requiredString(root.desktopAppVersion, "desktopAppVersion"),
        sdeBuild = requiredLong(root.sdeBuild, "sdeBuild"),
        systems = requiredArray(payload.systems, "payload.systems").mapIndexed { index, value ->
            val item = requiredObject(value, "payload.systems[$index]")
            val universe = requiredObject(item.universePosition, "payload.systems[$index].universePosition")
            val official = optionalObject(item.officialPosition)
            WebPackSystemDto(
                id = requiredInt(item.id, "payload.systems[$index].id"),
                name = requiredString(item.name, "payload.systems[$index].name"),
                regionId = requiredInt(item.regionId, "payload.systems[$index].regionId"),
                constellationId = requiredInt(item.constellationId, "payload.systems[$index].constellationId"),
                securityStatus = requiredDouble(item.securityStatus, "payload.systems[$index].securityStatus"),
                x = requiredDouble(universe.x, "payload.systems[$index].universePosition.x"),
                y = requiredDouble(universe.y, "payload.systems[$index].universePosition.y"),
                z = requiredDouble(universe.z, "payload.systems[$index].universePosition.z"),
                officialX = if (official == null) null else requiredDouble(official.x, "payload.systems[$index].officialPosition.x"),
                officialY = if (official == null) null else requiredDouble(official.y, "payload.systems[$index].officialPosition.y"),
                effectiveWormholeClassId = optionalInt(item.effectiveWormholeClassId),
            )
        },
        stargates = requiredArray(payload.stargateLinks, "payload.stargateLinks").mapIndexed { index, value ->
            val item = requiredObject(value, "payload.stargateLinks[$index]")
            WebPackStargateDto(
                requiredInt(item.firstSystemId, "payload.stargateLinks[$index].firstSystemId"),
                requiredInt(item.secondSystemId, "payload.stargateLinks[$index].secondSystemId"),
            )
        },
        regions = requiredArray(payload.regions, "payload.regions").mapIndexed { index, value ->
            val item = requiredObject(value, "payload.regions[$index]")
            WebPackRegionDto(requiredInt(item.id, "payload.regions[$index].id"), requiredString(item.name, "payload.regions[$index].name"))
        },
        constellations = requiredArray(payload.constellations, "payload.constellations").mapIndexed { index, value ->
            val item = requiredObject(value, "payload.constellations[$index]")
            WebPackConstellationDto(
                requiredInt(item.id, "payload.constellations[$index].id"),
                requiredInt(item.regionId, "payload.constellations[$index].regionId"),
                requiredString(item.name, "payload.constellations[$index].name"),
            )
        },
        ansiblex = requiredArray(payload.ansiblexLinks, "payload.ansiblexLinks").mapIndexed { index, value ->
            val item = requiredObject(value, "payload.ansiblexLinks[$index]")
            WebPackAnsiblexDto(
                id = requiredString(item.id, "payload.ansiblexLinks[$index].id"),
                firstSystemId = requiredInt(item.firstSystemId, "payload.ansiblexLinks[$index].firstSystemId"),
                secondSystemId = requiredInt(item.secondSystemId, "payload.ansiblexLinks[$index].secondSystemId"),
                direction = runCatching {
                    WebPackAnsiblexDirectionDto.valueOf(requiredString(item.direction, "payload.ansiblexLinks[$index].direction"))
                }.getOrElse { error("payload.ansiblexLinks[$index].direction is invalid") },
                displayName = optionalString(item.displayName),
                enabled = requiredBoolean(item.enabled, "payload.ansiblexLinks[$index].enabled"),
            )
        },
    ).also { require(it.schemaVersion == 1) { "Unsupported Web Pack schema ${it.schemaVersion}; expected 1" } }
}

private fun requiredObject(value: dynamic, path: String): dynamic {
    require(value != null && jsTypeOf(value) == "object" && !js("Array.isArray(value)") as Boolean) { "$path must be an object" }
    return value
}

private fun optionalObject(value: dynamic): dynamic =
    if (value == null || jsTypeOf(value) == "undefined") null else requiredObject(value, "optional object")

private fun requiredArray(value: dynamic, path: String): Array<dynamic> {
    require(js("Array.isArray(value)") as Boolean) { "$path must be an array" }
    @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
    return value as Array<dynamic>
}

private fun requiredString(value: dynamic, path: String): String {
    require(jsTypeOf(value) == "string" && (value as String).isNotBlank()) { "$path must be a non-blank string" }
    return value
}

private fun optionalString(value: dynamic): String? =
    if (value == null || jsTypeOf(value) == "undefined") null else value as? String

private fun requiredDouble(value: dynamic, path: String): Double {
    require(jsTypeOf(value) == "number") { "$path must be a number" }
    return (value as Number).toDouble().also { require(it.isFinite()) { "$path must be finite" } }
}

private fun requiredInt(value: dynamic, path: String): Int {
    val number = requiredDouble(value, path)
    require(number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) { "$path must be an integer" }
    return number.toInt()
}

private fun requiredLong(value: dynamic, path: String): Long {
    val number = requiredDouble(value, path)
    require(number % 1.0 == 0.0 && number > 0.0) { "$path must be a positive integer" }
    return number.toLong()
}

private fun optionalInt(value: dynamic): Int? =
    if (value == null || jsTypeOf(value) == "undefined") null else requiredInt(value, "optional integer")

private fun requiredBoolean(value: dynamic, path: String): Boolean {
    require(jsTypeOf(value) == "boolean") { "$path must be a boolean" }
    return value as Boolean
}
