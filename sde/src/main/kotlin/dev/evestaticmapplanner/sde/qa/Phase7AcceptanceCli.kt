package dev.evestaticmapplanner.sde.qa

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseValidator
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.sde.update.ActivationOutcome
import dev.evestaticmapplanner.sde.update.DiskSpacePreflight
import dev.evestaticmapplanner.sde.update.JdkSdeHttpTransport
import dev.evestaticmapplanner.sde.update.LatestBuildCacheStore
import dev.evestaticmapplanner.sde.update.ManagedStaticDataPaths
import dev.evestaticmapplanner.sde.update.PendingUpdateActivator
import dev.evestaticmapplanner.sde.update.SafeSdeArchiveExtractor
import dev.evestaticmapplanner.sde.update.SdeArchiveDownloader
import dev.evestaticmapplanner.sde.update.SdeCandidatePreparer
import dev.evestaticmapplanner.sde.update.SdeUpdateClient
import java.nio.file.Files
import kotlin.io.path.Path

fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "Usage: phase7Acceptance <isolated-managed-root>" }
    val root = Path(arguments.single()).toAbsolutePath().normalize()
    require(!root.toString().contains(".sde-work", ignoreCase = true)) {
        "Acceptance root must not be .sde-work"
    }
    require(!Files.exists(root) || Files.list(root).use { it.findAny().isEmpty }) {
        "Acceptance root must be absent or empty: $root"
    }
    val paths = ManagedStaticDataPaths(root)
    paths.initialize()
    val transport = JdkSdeHttpTransport()
    val client = SdeUpdateClient(transport, LatestBuildCacheStore(paths))
    val latest = client.checkLatest()
    println("LATEST_BUILD ${latest.buildInfo.buildNumber}")
    println("LATEST_RELEASE ${latest.buildInfo.releaseDate ?: "unknown"}")
    println("LATEST_NOT_MODIFIED ${latest.notModified}")

    val sourceUri = client.fixedBuildUri(latest.buildInfo.buildNumber)
    val archive = SdeArchiveDownloader(transport, paths, DiskSpacePreflight()).download(
        sourceUri,
        latest.buildInfo.buildNumber,
        onProgress = { progress ->
            if (progress.downloadedBytes % (10L * 1024 * 1024) < 64 * 1024) {
                println("DOWNLOAD_BYTES ${progress.downloadedBytes}/${progress.totalBytes ?: -1}")
            }
        },
    )
    println("ARCHIVE_URL ${archive.sourceUri}")
    println("ARCHIVE_SIZE ${archive.size}")
    println("ARCHIVE_LOCAL_SHA256 ${archive.sha256}")
    println("ARCHIVE_SHA256_NOTE locally calculated audit value; not a CCP publisher checksum")

    val manifest = SdeCandidatePreparer(paths, SafeSdeArchiveExtractor()).prepare(
        archive,
        latest.buildInfo,
        currentBuild = null,
    ) { println("PREPARE_STAGE $it") }
    println("PENDING_BUILD ${manifest.targetBuild}")
    println("CANDIDATE_SHA256 ${manifest.generatedDatabaseSha256}")
    val activation = PendingUpdateActivator(paths).activatePending()
    check(activation is ActivationOutcome.Activated) { "Acceptance activation failed: $activation" }
    println("ACTIVATION $activation")

    val metadata = StaticDatabaseMetadataReader.read(paths.activeDatabase)
    val validation = StaticDatabaseValidator.validate(paths.activeDatabase)
    println("ACTIVE_BUILD ${metadata.sdeBuild}")
    println("INTEGRITY_CHECK ${validation.integrityCheck}")
    println("FOREIGN_KEY_VIOLATIONS ${validation.foreignKeyViolations.size}")
    println(
        "COUNTS regions=${validation.counts.regions} constellations=${validation.counts.constellations} " +
            "systems=${validation.counts.systems} stargates=${validation.counts.stargates}",
    )
    val repository = SqliteUniverseRepository(paths.activeDatabase)
    listOf("Jita", "1DQ1-A", "T5ZI-S").forEach { name ->
        val system = repository.findSystemByName(name) ?: error("Acceptance system not found: $name")
        val details = repository.getSystemDetails(system.id) ?: error("Acceptance details not found: $name")
        println(
            "SYSTEM ${details.system.name} id=${details.system.id} region=${details.region.name} " +
                "constellation=${details.constellation.name} gates=${details.stargateCount}",
        )
    }
    println("ACCEPTANCE_ROOT $root")
}
