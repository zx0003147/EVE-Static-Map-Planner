package dev.evestaticmapplanner.data.db

import java.nio.file.Path
import java.sql.Connection

data class StaticDataCounts(
    val regions: Int,
    val constellations: Int,
    val systems: Int,
    val stargates: Int,
)

data class DatabaseValidationReport(
    val integrityCheck: String,
    val foreignKeyViolations: List<String>,
    val counts: StaticDataCounts,
)

object StaticDatabaseValidator {
    private val requiredMetadata = setOf(
        "schema_version",
        "sde_build",
        "generated_at",
        "source_format",
        "generator_version",
    )
    private val requiredIndexes = setOf(
        "idx_constellations_region",
        "idx_systems_region",
        "idx_systems_constellation",
        "idx_systems_name_nocase",
        "idx_stargates_from_system",
        "idx_stargates_to_system",
    )

    fun validate(databasePath: Path): DatabaseValidationReport =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use(::validate)

    fun validate(connection: Connection): DatabaseValidationReport {
        val integrity = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA integrity_check").use { result ->
                buildList {
                    while (result.next()) add(result.getString(1))
                }.joinToString("; ")
            }
        }
        check(integrity == "ok") { "SQLite integrity_check failed: $integrity" }

        val foreignKeyViolations = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { result ->
                buildList {
                    while (result.next()) {
                        add("table=${result.getString(1)}, rowid=${result.getString(2)}, parent=${result.getString(3)}")
                    }
                }
            }
        }
        check(foreignKeyViolations.isEmpty()) {
            "SQLite foreign_key_check failed: ${foreignKeyViolations.joinToString()}"
        }

        val counts = StaticDataCounts(
            regions = connection.count("regions"),
            constellations = connection.count("constellations"),
            systems = connection.count("systems"),
            stargates = connection.count("stargates"),
        )
        check(counts.regions > 0) { "No regions were imported" }
        check(counts.constellations > 0) { "No constellations were imported" }
        check(counts.systems > 0) { "No solar systems were imported" }
        check(counts.stargates > 0) { "No stargates were imported" }

        val metadataKeys = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT key FROM metadata").use { result ->
                buildSet { while (result.next()) add(result.getString(1)) }
            }
        }
        check(metadataKeys.containsAll(requiredMetadata)) {
            "Missing metadata keys: ${requiredMetadata - metadataKeys}"
        }

        val indexes = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { result ->
                buildSet { while (result.next()) add(result.getString(1)) }
            }
        }
        check(indexes.containsAll(requiredIndexes)) {
            "Missing required indexes: ${requiredIndexes - indexes}"
        }

        val brokenGatePairs = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT g.gate_id
                FROM stargates g
                LEFT JOIN stargates destination ON destination.gate_id = g.destination_gate_id
                WHERE destination.gate_id IS NULL
                   OR destination.destination_gate_id <> g.gate_id
                   OR destination.from_system_id <> g.to_system_id
                   OR destination.to_system_id <> g.from_system_id
                LIMIT 10
                """.trimIndent(),
            ).use { result -> buildList { while (result.next()) add(result.getInt(1)) } }
        }
        check(brokenGatePairs.isEmpty()) { "Broken reciprocal stargate pairs: $brokenGatePairs" }

        return DatabaseValidationReport(integrity, foreignKeyViolations, counts)
    }
}

private fun Connection.count(table: String): Int = createStatement().use { statement ->
    statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
        check(result.next())
        result.getInt(1)
    }
}
