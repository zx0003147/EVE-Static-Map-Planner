package dev.evestaticmapplanner.webpack

import java.time.Instant

class WebPackValidationException(message: String) : IllegalArgumentException(message)

object WebPackValidator {
    fun validate(document: WebPackDocument) {
        requireSupportedSchema(document.schemaVersion, "Web Pack")
        validateIdentity(
            packVersion = document.packVersion,
            generatedAt = document.generatedAt,
            desktopAppVersion = document.desktopAppVersion,
            sdeBuild = document.sdeBuild,
        )
        validate(document.payload)
        if (document.counts != document.payload.counts()) {
            invalid("Web Pack counts do not match the payload")
        }
    }

    fun validate(manifest: WebPackManifest) {
        requireSupportedSchema(manifest.schemaVersion, "Web Pack manifest")
        validateIdentity(
            packVersion = manifest.packVersion,
            generatedAt = manifest.generatedAt,
            desktopAppVersion = manifest.desktopAppVersion,
            sdeBuild = manifest.sdeBuild,
        )
        if (manifest.fileName != WebPackSchema.packFileName(manifest.packVersion)) {
            invalid("Manifest filename does not match packVersion")
        }
        if (manifest.sizeBytes <= 0) invalid("Manifest sizeBytes must be positive")
        if (!manifest.sha256.matches(Regex("[0-9a-f]{64}"))) {
            invalid("Manifest sha256 must be a lowercase SHA-256 digest")
        }
        validateCounts(manifest.counts)
    }

    fun validate(payload: WebPackPayload) {
        val counts = payload.counts()
        validateCounts(counts)

        val regionIds = uniqueIds("region", payload.regions.map(WebPackRegion::id))
        uniqueIds("constellation", payload.constellations.map(WebPackConstellation::id))
        val systemIds = uniqueIds("solar system", payload.systems.map(WebPackSolarSystem::id))

        payload.regions.forEach { region ->
            if (region.id <= 0) invalid("Region ID must be positive: ${region.id}")
            if (region.name.isBlank()) invalid("Region ${region.id} has a blank name")
        }
        val constellationsById = payload.constellations.associateBy(WebPackConstellation::id)
        payload.constellations.forEach { constellation ->
            if (constellation.id <= 0) invalid("Constellation ID must be positive: ${constellation.id}")
            if (constellation.name.isBlank()) invalid("Constellation ${constellation.id} has a blank name")
            if (constellation.regionId !in regionIds) {
                invalid("Constellation ${constellation.id} references missing region ${constellation.regionId}")
            }
        }
        payload.systems.forEach { system ->
            if (system.id <= 0) invalid("Solar system ID must be positive: ${system.id}")
            if (system.name.isBlank()) invalid("Solar system ${system.id} has a blank name")
            if (!system.securityStatus.isFinite() || system.securityStatus !in -1.0..1.0) {
                invalid("Solar system ${system.id} has invalid securityStatus")
            }
            val constellation = constellationsById[system.constellationId]
                ?: invalid("Solar system ${system.id} references missing constellation ${system.constellationId}")
            if (system.regionId !in regionIds || constellation.regionId != system.regionId) {
                invalid("Solar system ${system.id} has an invalid region/constellation hierarchy")
            }
            val position = system.universePosition
            if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) {
                invalid("Solar system ${system.id} has invalid universe coordinates")
            }
            system.officialPosition?.let { official ->
                if (!official.x.isFinite() || !official.y.isFinite()) {
                    invalid("Solar system ${system.id} has invalid Official 2D coordinates")
                }
            }
        }

        val stargatePairs = hashSetOf<Pair<Int, Int>>()
        payload.stargateLinks.forEach { link ->
            validateCanonicalEndpoints("Stargate", link.firstSystemId, link.secondSystemId, systemIds)
            if (!stargatePairs.add(link.firstSystemId to link.secondSystemId)) {
                invalid("Duplicate Stargate link: ${link.firstSystemId}-${link.secondSystemId}")
            }
        }

        val ansiblexIds = hashSetOf<String>()
        val ansiblexPairs = hashSetOf<Pair<Int, Int>>()
        payload.ansiblexLinks.forEach { link ->
            if (link.id.isBlank() || !ansiblexIds.add(link.id)) invalid("Duplicate or blank Ansiblex ID: ${link.id}")
            if (!link.enabled) invalid("Web Pack contains disabled Ansiblex link ${link.id}")
            validateCanonicalEndpoints("Ansiblex ${link.id}", link.firstSystemId, link.secondSystemId, systemIds)
            if (!ansiblexPairs.add(link.firstSystemId to link.secondSystemId)) {
                invalid("Duplicate Ansiblex logical pair: ${link.firstSystemId}-${link.secondSystemId}")
            }
        }
    }

    fun requireSupportedSchema(schemaVersion: Int, subject: String) {
        if (schemaVersion != WebPackSchema.VERSION) {
            invalid("$subject schemaVersion $schemaVersion is unsupported; expected ${WebPackSchema.VERSION}")
        }
    }

    private fun validateIdentity(
        packVersion: String,
        generatedAt: String,
        desktopAppVersion: String,
        sdeBuild: Long,
    ) {
        if (!packVersion.matches(Regex("[A-Za-z0-9._-]+"))) invalid("packVersion is blank or unsafe")
        runCatching { Instant.parse(generatedAt) }
            .getOrElse { invalid("generatedAt is not an ISO-8601 instant") }
        if (desktopAppVersion.isBlank()) invalid("desktopAppVersion must not be blank")
        if (sdeBuild <= 0) invalid("sdeBuild must be positive")
    }

    private fun validateCounts(counts: WebPackCounts) {
        if (counts.systems <= 0) invalid("Web Pack contains no solar systems")
        if (counts.stargateLinks <= 0) invalid("Web Pack contains no Stargate links")
        if (counts.regions <= 0) invalid("Web Pack contains no regions")
        if (counts.constellations <= 0) invalid("Web Pack contains no constellations")
        if (counts.ansiblexLinks < 0) invalid("Web Pack Ansiblex count cannot be negative")
    }

    private fun uniqueIds(kind: String, ids: List<Int>): Set<Int> {
        val unique = ids.toSet()
        if (unique.size != ids.size) invalid("Duplicate $kind IDs")
        return unique
    }

    private fun validateCanonicalEndpoints(
        kind: String,
        firstSystemId: Int,
        secondSystemId: Int,
        systemIds: Set<Int>,
    ) {
        if (firstSystemId >= secondSystemId) invalid("$kind endpoints are not canonical and distinct")
        if (firstSystemId !in systemIds) invalid("$kind references missing solar system $firstSystemId")
        if (secondSystemId !in systemIds) invalid("$kind references missing solar system $secondSystemId")
    }

    private fun invalid(message: String): Nothing = throw WebPackValidationException(message)
}
