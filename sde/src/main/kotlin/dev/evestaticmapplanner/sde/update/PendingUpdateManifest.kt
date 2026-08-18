package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.db.DatabaseValidationReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Serializable
data class CandidateCounts(
    val regions: Int,
    val constellations: Int,
    val systems: Int,
    val stargates: Int,
) {
    companion object {
        fun from(report: DatabaseValidationReport) = CandidateCounts(
            report.counts.regions,
            report.counts.constellations,
            report.counts.systems,
            report.counts.stargates,
        )
    }
}

@Serializable
data class PendingUpdateManifest(
    val manifestVersion: Int = VERSION,
    val targetBuild: Long,
    val officialBuildIdentity: String,
    val sourceUrl: String,
    val archiveSha256: String,
    val archiveSize: Long,
    val generatedDatabaseSha256: String,
    val generatedDatabaseSize: Long,
    val createdAt: String,
    val downloadedAt: String,
    val stagingId: String,
    val stagedDatabaseRelativePath: String,
    val expectedSchemaVersion: Int,
    val generatorVersion: String,
    val currentBuildAtPreparation: Long? = null,
    val counts: CandidateCounts,
) {
    init {
        require(manifestVersion == VERSION) { "Unsupported pending manifest version: $manifestVersion" }
        require(targetBuild > 0)
        require(archiveSha256.matches(ManagedStaticDataPaths.HEX_64))
        require(generatedDatabaseSha256.matches(ManagedStaticDataPaths.HEX_64))
        require(archiveSize > 0 && generatedDatabaseSize > 0)
        require(expectedSchemaVersion > 0)
        require(sourceUrl.startsWith("https://"))
        require(stagedDatabaseRelativePath == "staging/$stagingId/static.db")
    }

    companion object { const val VERSION = 1 }
}

class PendingUpdateStore(
    private val paths: ManagedStaticDataPaths,
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun read(): PendingUpdateManifest? {
        if (!Files.isRegularFile(paths.pendingManifest)) return null
        return json.decodeFromString<PendingUpdateManifest>(
            Files.readString(paths.pendingManifest, StandardCharsets.UTF_8),
        ).also(::validatePath)
    }

    fun write(manifest: PendingUpdateManifest) {
        validatePath(manifest)
        Files.createDirectories(paths.pendingDirectory)
        val part = paths.pendingManifest.resolveSibling("${paths.pendingManifest.fileName}.part")
        Files.deleteIfExists(part)
        FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val bytes = json.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        movePublished(part, paths.pendingManifest)
    }

    fun delete() {
        Files.deleteIfExists(paths.pendingManifest)
        Files.deleteIfExists(paths.pendingManifest.resolveSibling("${paths.pendingManifest.fileName}.part"))
    }

    fun candidatePath(manifest: PendingUpdateManifest): Path {
        validatePath(manifest)
        return paths.stagedDatabase(manifest.stagingId)
    }

    private fun validatePath(manifest: PendingUpdateManifest) {
        val candidate = paths.stagedDatabase(manifest.stagingId)
        val expected = paths.updatesDirectory.resolve(manifest.stagedDatabaseRelativePath).normalize()
        require(candidate == expected && candidate.startsWith(paths.stagingDirectory)) {
            "Pending manifest candidate path escapes managed staging"
        }
    }
}

internal fun movePublished(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
