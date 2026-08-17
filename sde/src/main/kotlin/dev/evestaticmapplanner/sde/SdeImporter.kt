package dev.evestaticmapplanner.sde

import dev.evestaticmapplanner.data.db.DatabaseValidationReport
import dev.evestaticmapplanner.data.db.StaticDatabaseBuildSession
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.db.StaticDatabaseValidator
import dev.evestaticmapplanner.sde.io.SdeSourceLocator
import dev.evestaticmapplanner.sde.validation.SdeReferenceValidationReport
import dev.evestaticmapplanner.sde.validation.SdeReferenceValidator
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

data class SdeImportRequest(
    val sourceDirectory: Path,
    val outputDatabase: Path,
    val sdeBuild: String,
    val generatorVersion: String = "0.1.0-SNAPSHOT",
) {
    init {
        require(sdeBuild.isNotBlank()) { "SDE build must be supplied by the caller" }
        require(generatorVersion.isNotBlank()) { "Generator version must not be blank" }
    }
}

data class SdeImportReport(
    val outputDatabase: Path,
    val sdeBuild: String,
    val references: SdeReferenceValidationReport,
    val database: DatabaseValidationReport,
)

class SdeImporter(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun import(request: SdeImportRequest): SdeImportReport {
        require(!Files.exists(request.outputDatabase)) {
            "Output database already exists: ${request.outputDatabase}"
        }
        val parent = request.outputDatabase.toAbsolutePath().parent
            ?: error("Output database must have a parent directory")
        Files.createDirectories(parent)
        val tempDatabase = parent.resolve("${request.outputDatabase.fileName}.tmp")
        require(!Files.exists(tempDatabase)) { "Temporary database already exists: $tempDatabase" }

        try {
            val files = SdeSourceLocator.locate(request.sourceDirectory)
            val dataSet = SdeDataSet.load(files)
            val referenceReport = SdeReferenceValidator.validate(dataSet)

            StaticDatabaseBuildSession.create(tempDatabase).use { database ->
                dataSet.regions.values.sortedBy { it.id }.forEach { database.insert(it.toDomain()) }
                dataSet.constellations.values.sortedBy { it.id }.forEach { database.insert(it.toDomain()) }
                dataSet.solarSystems.values.sortedBy { it.id }.forEach { database.insert(it.toDomain()) }
                dataSet.stargates.values.sortedBy { it.id }.forEach { database.insert(it.toDomain()) }
                dataSet.sourceFiles.forEach(database::insert)

                val metadata = linkedMapOf(
                    "schema_version" to StaticDatabaseSchema.VERSION.toString(),
                    "sde_build" to request.sdeBuild,
                    "generated_at" to Instant.now(clock).toString(),
                    "source_format" to "jsonl",
                    "generator_version" to request.generatorVersion,
                    "region_count" to referenceReport.regionCount.toString(),
                    "constellation_count" to referenceReport.constellationCount.toString(),
                    "system_count" to referenceReport.systemCount.toString(),
                    "stargate_count" to referenceReport.stargateCount.toString(),
                )
                metadata.forEach(database::putMetadata)
                database.validationReport()
                database.commit()
            }

            val databaseReport = StaticDatabaseValidator.validate(tempDatabase)
            moveCompletedDatabase(tempDatabase, request.outputDatabase)
            return SdeImportReport(
                outputDatabase = request.outputDatabase,
                sdeBuild = request.sdeBuild,
                references = referenceReport,
                database = databaseReport,
            )
        } catch (error: Throwable) {
            Files.deleteIfExists(tempDatabase)
            throw error
        }
    }
}

private fun moveCompletedDatabase(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }
}
