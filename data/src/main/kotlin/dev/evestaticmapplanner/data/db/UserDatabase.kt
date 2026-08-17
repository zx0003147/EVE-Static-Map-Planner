package dev.evestaticmapplanner.data.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

class UserDatabaseException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

object UserDatabase {
    fun initialize(databasePath: Path) {
        val absolute = databasePath.toAbsolutePath().normalize()
        val existed = Files.exists(absolute)
        if (!existed) {
            absolute.parent?.let(Files::createDirectories)
        }
        try {
            SqliteConnectionFactory.open(absolute).use { connection ->
                val version = connection.userVersion()
                when {
                    version > UserDatabaseSchema.VERSION -> throw UserDatabaseException(
                        "User database schema version $version is newer than supported version ${UserDatabaseSchema.VERSION}: $absolute",
                    )
                    version == 0 && existed && connection.hasApplicationTables() -> throw UserDatabaseException(
                        "User database has application tables but no recognized schema version: $absolute",
                    )
                    version == 0 -> createSchema(connection)
                    version < UserDatabaseSchema.VERSION -> migrate(connection, version)
                }
                validate(connection, absolute)
            }
        } catch (error: UserDatabaseException) {
            throw error
        } catch (error: Throwable) {
            throw UserDatabaseException("Unable to open user database: $absolute", error)
        }
    }

    fun open(databasePath: Path): Connection =
        SqliteConnectionFactory.open(databasePath.toAbsolutePath().normalize())

    private fun createSchema(connection: Connection) {
        connection.autoCommit = false
        try {
            UserDatabaseSchema.create(connection)
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun migrate(connection: Connection, fromVersion: Int) {
        throw UserDatabaseException(
            "No migration is available from user database schema $fromVersion to ${UserDatabaseSchema.VERSION}",
        )
    }

    private fun validate(connection: Connection, path: Path) {
        val integrity = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA integrity_check").use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }
        if (integrity != listOf("ok")) {
            throw UserDatabaseException("User database integrity check failed for $path: ${integrity.joinToString()}")
        }
        val required = setOf("ansiblex_connections", "ansiblex_import_batches")
        val actual = connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { result ->
            buildSet { while (result.next()) add(result.getString("TABLE_NAME")) }
        }
        if (!actual.containsAll(required)) {
            throw UserDatabaseException("User database schema is incomplete: missing ${required - actual}")
        }
        val foreignKeyErrors = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { result -> result.next() }
        }
        if (foreignKeyErrors) throw UserDatabaseException("User database contains invalid foreign key references: $path")
    }

    private fun Connection.userVersion(): Int = createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { result ->
            check(result.next())
            result.getInt(1)
        }
    }

    private fun Connection.hasApplicationTables(): Boolean = metaData
        .getTables(null, null, "ansiblex_%", arrayOf("TABLE"))
        .use { it.next() }
}
