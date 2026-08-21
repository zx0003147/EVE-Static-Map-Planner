package dev.evestaticmapplanner.data.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

class UserDatabaseException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

object UserDatabase {
    fun initialize(databasePath: Path) = initialize(databasePath, migrationHook = {})

    internal fun initialize(
        databasePath: Path,
        migrationHook: (Connection) -> Unit,
    ) {
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
                    version == 0 -> createSchema(connection, absolute)
                    version < UserDatabaseSchema.VERSION -> {
                        validate(connection, absolute, version)
                        migrate(connection, absolute, version, migrationHook)
                    }
                }
                validate(connection, absolute, UserDatabaseSchema.VERSION)
            }
        } catch (error: UserDatabaseException) {
            throw error
        } catch (error: Throwable) {
            throw UserDatabaseException("Unable to open user database: $absolute", error)
        }
    }

    fun open(databasePath: Path): Connection =
        SqliteConnectionFactory.open(databasePath.toAbsolutePath().normalize())

    private fun createSchema(connection: Connection, path: Path) {
        connection.autoCommit = false
        try {
            UserDatabaseSchema.create(connection)
            validate(connection, path, UserDatabaseSchema.VERSION)
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun migrate(
        connection: Connection,
        path: Path,
        fromVersion: Int,
        migrationHook: (Connection) -> Unit,
    ) {
        var version = fromVersion
        while (version < UserDatabaseSchema.VERSION) {
            when (version) {
                1 -> migrateVersionOneToTwo(connection, path, migrationHook)
                else -> throw UserDatabaseException(
                    "No migration is available from user database schema $version to ${UserDatabaseSchema.VERSION}",
                )
            }
            version++
        }
    }

    private fun migrateVersionOneToTwo(
        connection: Connection,
        path: Path,
        migrationHook: (Connection) -> Unit,
    ) {
        connection.autoCommit = false
        try {
            UserDatabaseSchema.addSavedMarkers(connection)
            validateContents(connection, path, requiredTables(version = 2), requiredIndexes(version = 2))
            migrationHook(connection)
            connection.createStatement().use { it.execute("PRAGMA user_version = 2") }
            validate(connection, path, expectedVersion = 2)
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun validate(connection: Connection, path: Path, expectedVersion: Int) {
        val actualVersion = connection.userVersion()
        if (actualVersion != expectedVersion) {
            throw UserDatabaseException(
                "User database schema version changed unexpectedly: expected $expectedVersion, found $actualVersion: $path",
            )
        }
        validateContents(
            connection,
            path,
            requiredTables(expectedVersion),
            requiredIndexes(expectedVersion),
        )
    }

    private fun validateContents(
        connection: Connection,
        path: Path,
        requiredTables: Set<String>,
        requiredIndexes: Set<String>,
    ) {
        val integrity = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA integrity_check").use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }
        if (integrity != listOf("ok")) {
            throw UserDatabaseException("User database integrity check failed for $path: ${integrity.joinToString()}")
        }
        val actual = connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { result ->
            buildSet { while (result.next()) add(result.getString("TABLE_NAME")) }
        }
        if (!actual.containsAll(requiredTables)) {
            throw UserDatabaseException("User database schema is incomplete: missing ${requiredTables - actual}")
        }
        val actualIndexes = connection.metaData.getIndexInfo(null, null, "ansiblex_connections", false, false).use { result ->
            buildSet {
                while (result.next()) result.getString("INDEX_NAME")?.let(::add)
            }
        }
        if (!actualIndexes.containsAll(requiredIndexes)) {
            throw UserDatabaseException("User database schema is incomplete: missing indexes ${requiredIndexes - actualIndexes}")
        }
        if ("saved_markers" in requiredTables) validateSavedMarkersSchema(connection)
        val foreignKeyErrors = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { result -> result.next() }
        }
        if (foreignKeyErrors) throw UserDatabaseException("User database contains invalid foreign key references: $path")
    }

    private fun validateSavedMarkersSchema(connection: Connection) {
        data class Column(val name: String, val type: String, val notNull: Boolean, val primaryKey: Boolean)

        val columns = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(saved_markers)").use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            Column(
                                name = result.getString("name"),
                                type = result.getString("type"),
                                notNull = result.getInt("notnull") == 1,
                                primaryKey = result.getInt("pk") == 1,
                            ),
                        )
                    }
                }
            }
        }
        val expected = listOf(
            Column("system_id", "INTEGER", notNull = false, primaryKey = true),
            Column("name", "TEXT", notNull = false, primaryKey = false),
            Column("notes", "TEXT", notNull = false, primaryKey = false),
            Column("color", "TEXT", notNull = true, primaryKey = false),
            Column("created_at", "TEXT", notNull = true, primaryKey = false),
            Column("updated_at", "TEXT", notNull = true, primaryKey = false),
        )
        if (columns != expected) throw UserDatabaseException("User database saved_markers schema is invalid")

        val createSql = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'saved_markers'",
            ).use { result ->
                if (!result.next()) throw UserDatabaseException("User database saved_markers table has no schema SQL")
                result.getString(1).uppercase()
            }
        }
        val requiredFragments = setOf(
            "STRICT",
            "CHECK(SYSTEM_ID > 0)",
            "'RED'",
            "'ORANGE'",
            "'YELLOW'",
            "'GREEN'",
            "'BLUE'",
            "'PURPLE'",
            "'WHITE'",
        )
        val missing = requiredFragments.filterNot(createSql::contains)
        if (missing.isNotEmpty()) {
            throw UserDatabaseException("User database saved_markers constraints are incomplete: missing $missing")
        }
    }

    private fun Connection.userVersion(): Int = createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { result ->
            check(result.next())
            result.getInt(1)
        }
    }

    private fun Connection.hasApplicationTables(): Boolean = metaData
        .getTables(null, null, "%", arrayOf("TABLE"))
        .use { result ->
            while (result.next()) {
                if (!result.getString("TABLE_NAME").startsWith("sqlite_")) return@use true
            }
            false
        }

    private fun requiredTables(version: Int): Set<String> = buildSet {
        if (version >= 1) addAll(setOf("ansiblex_connections", "ansiblex_import_batches"))
        if (version >= 2) add("saved_markers")
    }

    private fun requiredIndexes(version: Int): Set<String> = if (version >= 1) {
        setOf("idx_ansiblex_enabled", "idx_ansiblex_source")
    } else {
        emptySet()
    }
}
