package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.db.StaticDatabaseValidator
import dev.evestaticmapplanner.sde.SdeImportRequest
import dev.evestaticmapplanner.sde.SdeImporter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object UpdateTestFixtures {
    private val clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC)

    fun sourceDirectory(): Path = Path.of(
        checkNotNull(javaClass.classLoader.getResource("sde/valid-minimal")) { "Missing SDE fixture" }.toURI(),
    )

    fun buildDatabase(path: Path, build: Long) {
        SdeImporter(clock).import(SdeImportRequest(sourceDirectory(), path, build.toString(), "test"))
    }

    fun preparePending(
        paths: ManagedStaticDataPaths,
        targetBuild: Long,
        currentBuild: Long? = null,
        stagingId: String = "stage-$targetBuild",
    ): PendingUpdateManifest {
        paths.initialize()
        val candidate = paths.stagedDatabase(stagingId)
        Files.createDirectories(candidate.parent)
        buildDatabase(candidate, targetBuild)
        val report = StaticDatabaseValidator.validate(candidate)
        val metadata = StaticDatabaseMetadataReader.read(candidate)
        val manifest = PendingUpdateManifest(
            targetBuild = targetBuild,
            officialBuildIdentity = "tranquility/$targetBuild/jsonl",
            sourceUrl = "https://developers.eveonline.com/static-data/tranquility/eve-online-static-data-$targetBuild-jsonl.zip",
            archiveSha256 = "a".repeat(64),
            archiveSize = 123,
            generatedDatabaseSha256 = FileIntegrity.sha256(candidate),
            generatedDatabaseSize = Files.size(candidate),
            createdAt = "2026-08-18T00:00:00Z",
            downloadedAt = "2026-08-18T00:00:00Z",
            stagingId = stagingId,
            stagedDatabaseRelativePath = "staging/$stagingId/static.db",
            expectedSchemaVersion = metadata.schemaVersion,
            generatorVersion = "test",
            currentBuildAtPreparation = currentBuild,
            counts = CandidateCounts.from(report),
        )
        PendingUpdateStore(paths).write(manifest)
        return manifest
    }

    fun installOld(paths: ManagedStaticDataPaths, build: Long) {
        paths.initialize()
        val temporary = paths.dataDirectory.resolve("old-$build.db")
        buildDatabase(temporary, build)
        Files.move(temporary, paths.activeDatabase, StandardCopyOption.REPLACE_EXISTING)
    }

    fun assertBuild(path: Path, build: Long) {
        check(StaticDatabaseMetadataReader.read(path).sdeBuild == build)
        StaticDatabaseValidator.validate(path)
    }

    const val OLD_BUILD = 100L
    const val NEW_BUILD = 200L
    const val SCHEMA_VERSION = StaticDatabaseSchema.VERSION
}
