package dev.evestaticmapplanner.sde.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant

@Serializable
data class UpdaterAuditEvent(
    val event: String,
    val recordedAt: String,
    val oldBuild: Long? = null,
    val newBuild: Long? = null,
    val sourceUrl: String? = null,
    val archiveSha256: String? = null,
    val message: String? = null,
)

@Serializable
data class InstalledStaticDataRecord(
    val oldBuild: Long? = null,
    val activatedAt: String,
    val manifest: PendingUpdateManifest,
)

class UpdaterAuditStore(
    private val paths: ManagedStaticDataPaths,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { encodeDefaults = true },
) {
    fun append(
        event: String,
        oldBuild: Long? = null,
        newBuild: Long? = null,
        sourceUrl: String? = null,
        archiveSha256: String? = null,
        message: String? = null,
    ) {
        runCatching {
            Files.createDirectories(paths.auditDirectory)
            val line = json.encodeToString(
                UpdaterAuditEvent(
                    event,
                    Instant.now(clock).toString(),
                    oldBuild,
                    newBuild,
                    sourceUrl,
                    archiveSha256,
                    message,
                ),
            ) + System.lineSeparator()
            Files.writeString(
                paths.auditDirectory.resolve("update-history.jsonl"),
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    fun recordInstalled(oldBuild: Long?, manifest: PendingUpdateManifest) {
        runCatching {
            Files.createDirectories(paths.auditDirectory)
            val target = paths.auditDirectory.resolve("installed.json")
            val part = target.resolveSibling("installed.json.part")
            Files.writeString(
                part,
                json.encodeToString(InstalledStaticDataRecord(oldBuild, Instant.now(clock).toString(), manifest)),
                StandardCharsets.UTF_8,
            )
            movePublished(part, target)
        }
        append(
            event = "ACTIVATED",
            oldBuild = oldBuild,
            newBuild = manifest.targetBuild,
            sourceUrl = manifest.sourceUrl,
            archiveSha256 = manifest.archiveSha256,
        )
    }
}
