package dev.evestaticmapplanner.sde.cli

import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import dev.evestaticmapplanner.data.db.StaticDatabaseValidator
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.sde.SdeImportRequest
import dev.evestaticmapplanner.sde.SdeImporter
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { usage() }
    val options = CliOptions.parse(args.drop(1))
    when (args.first()) {
        "import" -> importSde(options)
        "query" -> querySystem(options)
        "verify" -> verifyDatabase(options)
        else -> error("Unknown command '${args.first()}'.\n${usage()}")
    }
}

private fun importSde(options: CliOptions) {
    val report = SdeImporter().import(
        SdeImportRequest(
            sourceDirectory = options.path("input"),
            outputDatabase = options.path("output"),
            sdeBuild = options.required("build"),
        ),
    )
    println("Imported SDE build ${report.sdeBuild} to ${report.outputDatabase.toAbsolutePath()}")
    println(
        "Counts: regions=${report.references.regionCount}, " +
            "constellations=${report.references.constellationCount}, " +
            "systems=${report.references.systemCount}, stargates=${report.references.stargateCount}",
    )
    printValidation(report.database.integrityCheck, report.database.foreignKeyViolations)
}

private fun querySystem(options: CliOptions) {
    val database = options.path("database")
    val repository = SqliteUniverseRepository(database)
    val details = when {
        options["system-id"] != null -> repository.getSystemDetails(options.required("system-id").toInt())
        options["system-name"] != null -> repository.findSystemByName(options.required("system-name"))
            ?.let { repository.getSystemDetails(it.id) }
        else -> error("query requires --system-id or --system-name")
    } ?: error("Solar system was not found")
    printSystem(details)
}

private fun verifyDatabase(options: CliOptions) {
    val database = options.path("database")
    val metadata = readMetadata(database)
    println("SDE build: ${metadata["sde_build"] ?: "missing"}")
    val report = StaticDatabaseValidator.validate(database)
    println(
        "Counts: regions=${report.counts.regions}, constellations=${report.counts.constellations}, " +
            "systems=${report.counts.systems}, stargates=${report.counts.stargates}",
    )
    printValidation(report.integrityCheck, report.foreignKeyViolations)

    val ids = options.required("systems")
        .split(',')
        .map { token -> token.trim().toInt() }
    val repository = SqliteUniverseRepository(database)
    ids.forEach { id ->
        val details = repository.getSystemDetails(id) ?: error("Solar system $id was not found")
        printSystem(details)
    }
}

private fun printSystem(details: SolarSystemDetails) {
    val system = details.system
    println("System: ${system.name} / ${system.id}")
    println("  Region: ${details.region.name} / ${details.region.id}")
    println("  Constellation: ${details.constellation.name} / ${details.constellation.id}")
    println("  Security: ${system.securityStatus}")
    println("  Position (m): x=${system.position.x}, y=${system.position.y}, z=${system.position.z}")
    println("  Stargates: ${details.stargateCount}")
    details.stargates.forEach { gate ->
        println(
            "    gate=${gate.id}, destinationSystem=${gate.toSystemId}, " +
                "destinationGate=${gate.destinationGateId}",
        )
    }
}

private fun readMetadata(database: Path): Map<String, String> =
    SqliteConnectionFactory.open(database, queryOnly = true).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT key, value FROM metadata ORDER BY key").use { result ->
                buildMap { while (result.next()) put(result.getString(1), result.getString(2)) }
            }
        }
    }

private fun printValidation(integrity: String, foreignKeyViolations: List<String>) {
    println("PRAGMA integrity_check: $integrity")
    println(
        if (foreignKeyViolations.isEmpty()) {
            "PRAGMA foreign_key_check: no violations"
        } else {
            "PRAGMA foreign_key_check: ${foreignKeyViolations.joinToString()}"
        },
    )
}

private data class CliOptions(
    private val values: Map<String, String>,
) {
    operator fun get(name: String): String? = values[name]

    fun required(name: String): String = values[name] ?: error("Missing --$name")

    fun path(name: String): Path = Path(required(name))

    companion object {
        fun parse(arguments: List<String>): CliOptions {
            require(arguments.size % 2 == 0) { "Options must be supplied as --name value pairs" }
            val values = arguments.chunked(2).associate { (option, value) ->
                require(option.startsWith("--")) { "Invalid option: $option" }
                option.removePrefix("--") to value
            }
            return CliOptions(values)
        }
    }
}

private fun usage(): String = """
    Usage:
      import --input <extracted-sde> --output <static.db> --build <build-number>
      query --database <static.db> (--system-id <id> | --system-name <name>)
      verify --database <static.db> --systems <id,id,...>
""".trimIndent()
