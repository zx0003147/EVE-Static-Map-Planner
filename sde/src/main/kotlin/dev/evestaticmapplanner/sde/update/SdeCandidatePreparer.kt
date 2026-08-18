package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.db.StaticDatabaseValidator
import dev.evestaticmapplanner.sde.SdeImportProgressListener
import dev.evestaticmapplanner.sde.SdeImportRequest
import dev.evestaticmapplanner.sde.SdeImportStage
import dev.evestaticmapplanner.sde.SdeImporter
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.util.UUID

sealed interface CandidatePreparationProgress {
    data object Extracting : CandidatePreparationProgress
    data class Importing(val stage: SdeImportStage) : CandidatePreparationProgress
    data object Validating : CandidatePreparationProgress
}

class SdeCandidatePreparer(
    private val paths: ManagedStaticDataPaths,
    private val extractor: SafeSdeArchiveExtractor = SafeSdeArchiveExtractor(),
    private val importer: SdeImporter = SdeImporter(),
    private val pendingStore: PendingUpdateStore = PendingUpdateStore(paths),
    private val clock: Clock = Clock.systemUTC(),
    private val stagingIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val generatorVersion: String = "0.1.0-SNAPSHOT",
) {
    fun prepare(
        archive: DownloadedArchive,
        buildInfo: SdeBuildInfo,
        currentBuild: Long?,
        onProgress: (CandidatePreparationProgress) -> Unit = {},
    ): PendingUpdateManifest {
        check(pendingStore.read() == null) { "A pending static-data update already exists" }
        paths.initialize()
        val stagingId = "${buildInfo.buildNumber}-${stagingIdGenerator()}"
        val stagingRoot = paths.stagingRoot(stagingId)
        val sourceDirectory = stagingRoot.resolve("source")
        val candidate = paths.stagedDatabase(stagingId)
        try {
            onProgress(CandidatePreparationProgress.Extracting)
            extractor.extract(archive.path, sourceDirectory)
            importer.import(
                SdeImportRequest(sourceDirectory, candidate, buildInfo.buildNumber.toString(), generatorVersion),
                SdeImportProgressListener { onProgress(CandidatePreparationProgress.Importing(it)) },
            )
            onProgress(CandidatePreparationProgress.Validating)
            val report = StaticDatabaseValidator.validate(candidate)
            val metadata = StaticDatabaseMetadataReader.read(candidate)
            check(metadata.sdeBuild == buildInfo.buildNumber) { "Candidate build metadata mismatch" }
            check(metadata.schemaVersion == StaticDatabaseSchema.VERSION) { "Candidate schema metadata mismatch" }
            val now = Instant.now(clock).toString()
            val manifest = PendingUpdateManifest(
                targetBuild = buildInfo.buildNumber,
                officialBuildIdentity = "tranquility/${buildInfo.buildNumber}/jsonl",
                sourceUrl = archive.sourceUri.toString(),
                archiveSha256 = archive.sha256,
                archiveSize = archive.size,
                generatedDatabaseSha256 = FileIntegrity.sha256(candidate),
                generatedDatabaseSize = Files.size(candidate),
                createdAt = now,
                downloadedAt = archive.downloadedAt.toString(),
                stagingId = stagingId,
                stagedDatabaseRelativePath = "staging/$stagingId/static.db",
                expectedSchemaVersion = metadata.schemaVersion,
                generatorVersion = generatorVersion,
                currentBuildAtPreparation = currentBuild,
                counts = CandidateCounts.from(report),
            )
            pendingStore.write(manifest)
            return manifest
        } catch (error: Throwable) {
            deleteStaging(stagingRoot)
            throw error
        }
    }

    private fun deleteStaging(root: java.nio.file.Path) {
        if (!Files.exists(root)) return
        paths.requireManaged(root)
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
