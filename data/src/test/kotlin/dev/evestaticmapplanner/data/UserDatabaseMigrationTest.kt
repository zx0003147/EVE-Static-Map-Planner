package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import dev.evestaticmapplanner.data.db.UserDatabase
import dev.evestaticmapplanner.data.db.UserDatabaseException
import dev.evestaticmapplanner.data.db.UserDatabaseSchema
import java.nio.file.Files
import java.sql.Connection
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserDatabaseMigrationTest {
    @Test
    fun `fresh database creates complete strict version two schema`() {
        val path = createTempDirectory("user-db-v2-fresh").resolve("user.db")

        UserDatabase.initialize(path)

        UserDatabase.open(path).use { connection ->
            assertEquals(2, connection.userVersion())
            assertEquals(
                setOf("ansiblex_import_batches", "ansiblex_connections", "saved_markers"),
                connection.applicationTables(),
            )
            val columns = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(saved_markers)").use { result ->
                    buildList { while (result.next()) add(result.getString("name") to result.getString("type")) }
                }
            }
            assertEquals(
                listOf(
                    "system_id" to "INTEGER",
                    "name" to "TEXT",
                    "notes" to "TEXT",
                    "color" to "TEXT",
                    "created_at" to "TEXT",
                    "updated_at" to "TEXT",
                ),
                columns,
            )
            val createSql = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'saved_markers'").use {
                    assertTrue(it.next())
                    it.getString(1)
                }
            }
            assertTrue(createSql.contains("STRICT"))
            assertTrue(createSql.contains("system_id > 0"))
            MarkerColorNames.forEach { assertTrue(createSql.contains("'$it'")) }
            assertTrue(connection.foreignKeys("saved_markers").isEmpty())
        }
    }

    @Test
    fun `version one migration preserves every Ansiblex value and creates empty marker table`() {
        val path = createTempDirectory("user-db-v1-migrate").resolve("user.db")
        SqliteConnectionFactory.open(path).use { connection ->
            createVersionOneFixture(connection)
            insertVersionOneRows(connection)
        }
        val before = snapshotAnsiblex(path)

        UserDatabase.initialize(path)

        UserDatabase.open(path).use { connection ->
            assertEquals(2, connection.userVersion())
            assertTrue("saved_markers" in connection.applicationTables())
            assertEquals(0, connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM saved_markers").use { it.next(); it.getInt(1) }
            })
        }
        assertEquals(before, snapshotAnsiblex(path))
    }

    @Test
    fun `migration failure rolls back marker table version and all Ansiblex values`() {
        val path = createTempDirectory("user-db-v1-rollback").resolve("user.db")
        SqliteConnectionFactory.open(path).use { connection ->
            createVersionOneFixture(connection)
            insertVersionOneRows(connection)
        }
        val before = snapshotAnsiblex(path)

        assertFailsWith<UserDatabaseException> {
            UserDatabase.initialize(path) { error("forced migration failure") }
        }

        UserDatabase.open(path).use { connection ->
            assertEquals(1, connection.userVersion())
            assertFalse("saved_markers" in connection.applicationTables())
        }
        assertEquals(before, snapshotAnsiblex(path))
    }

    @Test
    fun `too-new database is rejected byte-for-byte without mutation`() {
        val path = createTempDirectory("user-db-too-new").resolve("user.db")
        SqliteConnectionFactory.open(path).use { connection ->
            connection.createStatement().execute("CREATE TABLE future_data(value TEXT) STRICT")
            connection.createStatement().execute("INSERT INTO future_data VALUES('keep')")
            connection.createStatement().execute("PRAGMA user_version = 99")
        }
        val before = Files.readAllBytes(path)

        assertFailsWith<UserDatabaseException> { UserDatabase.initialize(path) }

        assertContentEquals(before, Files.readAllBytes(path))
    }

    @Test
    fun `version zero database with known application table is not treated as fresh`() {
        val path = createTempDirectory("user-db-version-zero").resolve("user.db")
        SqliteConnectionFactory.open(path).use(UserDatabaseSchema::addSavedMarkers)

        assertFailsWith<UserDatabaseException> { UserDatabase.initialize(path) }

        UserDatabase.open(path).use { connection ->
            assertEquals(0, connection.userVersion())
            assertEquals(setOf("saved_markers"), connection.applicationTables())
        }
    }

    @Test
    fun `malformed database is never deleted or rebuilt`() {
        val path = createTempDirectory("user-db-malformed").resolve("user.db")
        val malformed = "not a sqlite database\nuser content".toByteArray()
        Files.write(path, malformed)

        assertFailsWith<UserDatabaseException> { UserDatabase.initialize(path) }

        assertTrue(Files.exists(path))
        assertContentEquals(malformed, Files.readAllBytes(path))
    }
}

private data class AnsiblexSnapshot(
    val batches: List<List<String?>>,
    val connections: List<List<String?>>,
)

private fun snapshotAnsiblex(path: java.nio.file.Path): AnsiblexSnapshot = SqliteConnectionFactory.open(path).use { connection ->
    AnsiblexSnapshot(
        batches = connection.rows("SELECT * FROM ansiblex_import_batches ORDER BY batch_id"),
        connections = connection.rows("SELECT * FROM ansiblex_connections ORDER BY id"),
    )
}

private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { statement ->
    statement.executeQuery(sql).use { result ->
        val count = result.metaData.columnCount
        buildList {
            while (result.next()) add((1..count).map { column -> result.getString(column) })
        }
    }
}

private fun Connection.userVersion(): Int = createStatement().use { statement ->
    statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
}

private fun Connection.applicationTables(): Set<String> = metaData
    .getTables(null, null, "%", arrayOf("TABLE"))
    .use { result ->
        buildSet {
            while (result.next()) {
                result.getString("TABLE_NAME").takeUnless { it.startsWith("sqlite_") }?.let(::add)
            }
        }
    }

private fun Connection.foreignKeys(table: String): List<String> = metaData
    .getImportedKeys(null, null, table)
    .use { result -> buildList { while (result.next()) add(result.getString("FK_NAME")) } }

private fun createVersionOneFixture(connection: Connection) {
    val statements = listOf(
        """
        CREATE TABLE ansiblex_import_batches (
            batch_id TEXT PRIMARY KEY CHECK(length(trim(batch_id)) > 0),
            source_file_name TEXT NOT NULL CHECK(length(trim(source_file_name)) > 0),
            source_file_sha256 TEXT NOT NULL CHECK(length(source_file_sha256) = 64),
            imported_at TEXT NOT NULL CHECK(length(trim(imported_at)) > 0),
            mode TEXT NOT NULL CHECK(mode IN ('MERGE', 'REPLACE')),
            added_count INTEGER NOT NULL CHECK(added_count >= 0),
            updated_count INTEGER NOT NULL CHECK(updated_count >= 0),
            unchanged_count INTEGER NOT NULL CHECK(unchanged_count >= 0),
            removed_count INTEGER NOT NULL CHECK(removed_count >= 0),
            error_count INTEGER NOT NULL CHECK(error_count >= 0)
        ) STRICT
        """.trimIndent(),
        """
        CREATE TABLE ansiblex_connections (
            id TEXT PRIMARY KEY CHECK(length(trim(id)) > 0),
            first_system_id INTEGER NOT NULL CHECK(first_system_id > 0),
            second_system_id INTEGER NOT NULL CHECK(second_system_id > 0),
            direction TEXT NOT NULL CHECK(direction IN ('BIDIRECTIONAL', 'FIRST_TO_SECOND', 'SECOND_TO_FIRST')),
            display_name TEXT,
            notes TEXT,
            source TEXT NOT NULL CHECK(source IN ('IMPORT', 'MANUAL')),
            source_batch_id TEXT,
            enabled INTEGER NOT NULL CHECK(enabled IN (0, 1)),
            created_at TEXT NOT NULL CHECK(length(trim(created_at)) > 0),
            updated_at TEXT NOT NULL CHECK(length(trim(updated_at)) > 0),
            CONSTRAINT ck_ansiblex_ordered_pair CHECK(first_system_id < second_system_id),
            CONSTRAINT ck_ansiblex_source_batch CHECK(
                (source = 'IMPORT' AND source_batch_id IS NOT NULL) OR
                (source = 'MANUAL' AND source_batch_id IS NULL)
            ),
            CONSTRAINT uq_ansiblex_logical_pair UNIQUE(first_system_id, second_system_id),
            CONSTRAINT fk_ansiblex_source_batch
                FOREIGN KEY(source_batch_id) REFERENCES ansiblex_import_batches(batch_id)
        ) STRICT
        """.trimIndent(),
        "CREATE INDEX idx_ansiblex_enabled ON ansiblex_connections(enabled)",
        "CREATE INDEX idx_ansiblex_source ON ansiblex_connections(source)",
        "PRAGMA user_version = 1",
    )
    connection.createStatement().use { statement -> statements.forEach(statement::execute) }
}

private fun insertVersionOneRows(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute(
            """
            INSERT INTO ansiblex_import_batches VALUES(
                'batch-1', 'network.csv', '${"a".repeat(64)}', '2026-08-20T00:00:00Z',
                'MERGE', 1, 2, 3, 4, 0
            )
            """.trimIndent(),
        )
        statement.execute(
            """
            INSERT INTO ansiblex_connections VALUES(
                'imported-1', 10, 20, 'FIRST_TO_SECOND', 'Imported', 'keep imported notes',
                'IMPORT', 'batch-1', 1, '2026-08-20T00:00:00Z', '2026-08-20T01:00:00Z'
            )
            """.trimIndent(),
        )
        statement.execute(
            """
            INSERT INTO ansiblex_connections VALUES(
                'manual-1', 30, 40, 'BIDIRECTIONAL', NULL, 'keep manual notes',
                'MANUAL', NULL, 0, '2026-08-19T00:00:00Z', '2026-08-19T02:00:00Z'
            )
            """.trimIndent(),
        )
    }
}

private val MarkerColorNames = listOf("RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE", "WHITE")
