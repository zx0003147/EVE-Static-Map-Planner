package dev.evestaticmapplanner.webpack

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

data class WebPackExportRequest(
    val outputDirectory: Path,
    val desktopAppVersion: String,
    val sdeBuild: Long,
) {
    init {
        require(desktopAppVersion.isNotBlank()) { "Desktop application version must not be blank" }
        require(sdeBuild > 0) { "SDE build must be positive" }
    }
}

data class WebPackExportReport(
    val outputDirectory: Path,
    val manifestPath: Path,
    val packPath: Path,
    val schemaVersion: Int,
    val packVersion: String,
    val generatedAt: String,
    val desktopAppVersion: String,
    val sdeBuild: Long,
    val counts: WebPackCounts,
    val packSizeBytes: Long,
    val packSha256: String,
)

class WebPackExportException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class WebPackExporter(
    private val staticMapRepository: StaticMapRepository,
    private val ansiblexRepository: AnsiblexRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun export(request: WebPackExportRequest): WebPackExportReport {
        val staticData = try {
            staticMapRepository.load()
        } catch (error: Throwable) {
            throw WebPackExportException("Static data unavailable: ${error.message ?: error::class.simpleName}", error)
        }
        val activeAnsiblex = try {
            ansiblexRepository.getAll().filter(AnsiblexConnection::enabled)
        } catch (error: Throwable) {
            throw WebPackExportException("Ansiblex data unavailable: ${error.message ?: error::class.simpleName}", error)
        }
        val payload = staticData.toWebPackPayload(activeAnsiblex)
        try {
            WebPackValidator.validate(payload)
        } catch (error: WebPackValidationException) {
            throw WebPackExportException("Web Pack data validation failed: ${error.message}", error)
        }

        val generatedAt = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)
        val payloadBytes = try {
            WebPackCodec.encodePayload(payload)
        } catch (error: Throwable) {
            throw WebPackExportException("Web Pack serialization failed: ${error.message ?: error::class.simpleName}", error)
        }
        val packVersion = buildPackVersion(request, generatedAt, payloadBytes)
        val counts = payload.counts()
        val document = WebPackDocument(
            schemaVersion = WebPackSchema.VERSION,
            packVersion = packVersion,
            generatedAt = generatedAt.toString(),
            desktopAppVersion = request.desktopAppVersion,
            sdeBuild = request.sdeBuild,
            counts = counts,
            payload = payload,
        )
        val packBytes = try {
            WebPackCodec.encodePack(document)
        } catch (error: Throwable) {
            throw WebPackExportException("Web Pack serialization failed: ${error.message ?: error::class.simpleName}", error)
        }
        val checksum = sha256(packBytes)
        val packFileName = WebPackSchema.packFileName(packVersion)
        val manifest = WebPackManifest(
            schemaVersion = WebPackSchema.VERSION,
            packVersion = packVersion,
            generatedAt = generatedAt.toString(),
            desktopAppVersion = request.desktopAppVersion,
            sdeBuild = request.sdeBuild,
            fileName = packFileName,
            sizeBytes = packBytes.size.toLong(),
            sha256 = checksum,
            counts = counts,
        )
        val manifestBytes = try {
            WebPackCodec.encodeManifest(manifest)
        } catch (error: Throwable) {
            throw WebPackExportException("Web Pack manifest serialization failed: ${error.message ?: error::class.simpleName}", error)
        }

        val outputDirectory = request.outputDirectory.toAbsolutePath().normalize()
        val packPath = outputDirectory.resolve(packFileName)
        val manifestPath = outputDirectory.resolve(WebPackSchema.MANIFEST_FILE_NAME)
        writePublishableFiles(outputDirectory, packPath, packBytes, manifestPath, manifestBytes)
        return WebPackExportReport(
            outputDirectory = outputDirectory,
            manifestPath = manifestPath,
            packPath = packPath,
            schemaVersion = WebPackSchema.VERSION,
            packVersion = packVersion,
            generatedAt = generatedAt.toString(),
            desktopAppVersion = request.desktopAppVersion,
            sdeBuild = request.sdeBuild,
            counts = counts,
            packSizeBytes = packBytes.size.toLong(),
            packSha256 = checksum,
        )
    }

    private fun writePublishableFiles(
        outputDirectory: Path,
        packPath: Path,
        packBytes: ByteArray,
        manifestPath: Path,
        manifestBytes: ByteArray,
    ) {
        val packTemp = outputDirectory.resolve(".${packPath.fileName}.${UUID.randomUUID()}.tmp")
        val manifestTemp = outputDirectory.resolve(".${manifestPath.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.createDirectories(outputDirectory)
            Files.write(packTemp, packBytes)
            moveReplacingAtomically(packTemp, packPath)
            Files.write(manifestTemp, manifestBytes)
            // Publish the manifest last so a server never advertises a pack that has not been uploaded yet.
            moveReplacingAtomically(manifestTemp, manifestPath)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(packTemp) }
            runCatching { Files.deleteIfExists(manifestTemp) }
            throw WebPackExportException(
                "Filesystem failure while exporting Web Pack to $outputDirectory: " +
                    (error.message ?: error::class.simpleName),
                error,
            )
        }
    }

    private fun buildPackVersion(
        request: WebPackExportRequest,
        generatedAt: Instant,
        payloadBytes: ByteArray,
    ): String {
        val seed = buildList {
            add(WebPackSchema.VERSION.toString().encodeToByteArray())
            add(request.sdeBuild.toString().encodeToByteArray())
            add(request.desktopAppVersion.encodeToByteArray())
            add(generatedAt.toString().encodeToByteArray())
            add(payloadBytes)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        seed.forEach(digest::update)
        val suffix = digest.digest().toHex().take(12)
        return "${request.sdeBuild}-${PACK_VERSION_TIME_FORMATTER.format(generatedAt)}-$suffix"
    }
}

private fun StaticMapData.toWebPackPayload(activeAnsiblex: List<AnsiblexConnection>): WebPackPayload = WebPackPayload(
    systems = systems.sortedBy { it.id }.map { system ->
        WebPackSolarSystem(
            id = system.id,
            name = system.name,
            regionId = system.regionId,
            constellationId = system.constellationId,
            securityStatus = system.securityStatus,
            universePosition = WebPackPosition3D(
                x = system.position.x,
                y = system.position.y,
                z = system.position.z,
            ),
            officialPosition = system.schematicPosition?.let { WebPackPosition2D(it.x, it.y) },
            effectiveWormholeClassId = system.effectiveWormholeClassId,
        )
    },
    stargateLinks = connections.sortedWith(compareBy({ it.firstSystemId }, { it.secondSystemId })).map { link ->
        WebPackStargateLink(link.firstSystemId, link.secondSystemId)
    },
    regions = regions.sortedBy { it.id }.map { WebPackRegion(it.id, it.name) },
    constellations = constellations.sortedBy { it.id }.map {
        WebPackConstellation(it.id, it.regionId, it.name)
    },
    ansiblexLinks = activeAnsiblex.sortedWith(compareBy({ it.firstSystemId }, { it.secondSystemId }, { it.id })).map {
        WebPackAnsiblexLink(
            id = it.id,
            firstSystemId = it.firstSystemId,
            secondSystemId = it.secondSystemId,
            direction = WebPackAnsiblexDirection.valueOf(it.direction.name),
            displayName = it.displayName,
            enabled = true,
        )
    },
)

private fun moveReplacingAtomically(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private val PACK_VERSION_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
