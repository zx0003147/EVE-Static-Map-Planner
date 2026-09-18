package dev.evestaticmapplanner.data.db

import java.nio.file.Path
import java.sql.Connection

sealed interface StaticDatabaseSchemaCompatibility {
    val actualVersion: Int?

    data class Compatible(override val actualVersion: Int) : StaticDatabaseSchemaCompatibility
    data class Older(override val actualVersion: Int) : StaticDatabaseSchemaCompatibility
    data class Newer(override val actualVersion: Int) : StaticDatabaseSchemaCompatibility
    data class Invalid(val reason: String) : StaticDatabaseSchemaCompatibility {
        override val actualVersion: Int? = null
    }
}

object StaticDatabaseSchemaCompatibilityInspector {
    fun inspect(databasePath: Path): StaticDatabaseSchemaCompatibility = runCatching {
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use(::inspect)
    }.getOrElse { error ->
        StaticDatabaseSchemaCompatibility.Invalid(error.message ?: "Unable to read static database schema metadata")
    }

    fun inspect(connection: Connection): StaticDatabaseSchemaCompatibility {
        val raw = runCatching {
            connection.prepareStatement("SELECT value FROM metadata WHERE key = 'schema_version'").use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next()) result.getString(1) else null
                }
            }
        }.getOrElse { error ->
            return StaticDatabaseSchemaCompatibility.Invalid(
                error.message ?: "Static database metadata table is unavailable",
            )
        }
        val actual = raw?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: return StaticDatabaseSchemaCompatibility.Invalid(
                "Static database metadata.schema_version is missing or is not a positive integer",
            )
        return when {
            actual == StaticDatabaseSchema.VERSION -> StaticDatabaseSchemaCompatibility.Compatible(actual)
            actual < StaticDatabaseSchema.VERSION -> StaticDatabaseSchemaCompatibility.Older(actual)
            else -> StaticDatabaseSchemaCompatibility.Newer(actual)
        }
    }
}
