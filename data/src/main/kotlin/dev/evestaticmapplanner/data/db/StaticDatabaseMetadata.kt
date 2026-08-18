package dev.evestaticmapplanner.data.db

import java.nio.file.Path

data class StaticDatabaseMetadata(
    val schemaVersion: Int,
    val sdeBuild: Long,
    val generatedAt: String,
    val values: Map<String, String>,
)

object StaticDatabaseMetadataReader {
    fun read(databasePath: Path): StaticDatabaseMetadata =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            val values = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT key, value FROM metadata").use { result ->
                    buildMap { while (result.next()) put(result.getString(1), result.getString(2)) }
                }
            }
            StaticDatabaseMetadata(
                schemaVersion = values.requiredPositiveInt("schema_version"),
                sdeBuild = values.requiredPositiveLong("sde_build"),
                generatedAt = values.requiredNonBlank("generated_at"),
                values = values,
            )
        }
}

private fun Map<String, String>.requiredNonBlank(key: String): String =
    get(key)?.takeIf(String::isNotBlank) ?: error("Static database metadata.$key is missing or blank")

private fun Map<String, String>.requiredPositiveInt(key: String): Int =
    requiredNonBlank(key).toIntOrNull()?.takeIf { it > 0 }
        ?: error("Static database metadata.$key must be a positive integer")

private fun Map<String, String>.requiredPositiveLong(key: String): Long =
    requiredNonBlank(key).toLongOrNull()?.takeIf { it > 0L }
        ?: error("Static database metadata.$key must be a positive integer")
