package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import dev.evestaticmapplanner.data.db.UserDatabase
import dev.evestaticmapplanner.data.db.UserDatabaseException
import dev.evestaticmapplanner.data.db.UserDatabaseSchema
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserDatabaseTest {
    @Test
    fun `missing user database is created with schema version one`() {
        val path = createTempDirectory("user-db-create").resolve("nested").resolve("user.db")

        UserDatabase.initialize(path)

        SqliteConnectionFactory.open(path).use { connection ->
            val version = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result -> result.next(); result.getInt(1) }
            }
            assertEquals(UserDatabaseSchema.VERSION, version)
        }
    }

    @Test
    fun `newer schema is rejected without changing its version`() {
        val path = createTempDirectory("user-db-newer").resolve("user.db")
        SqliteConnectionFactory.open(path).use { it.createStatement().execute("PRAGMA user_version = 99") }

        assertFailsWith<UserDatabaseException> { UserDatabase.initialize(path) }

        SqliteConnectionFactory.open(path).use { connection ->
            connection.createStatement().executeQuery("PRAGMA user_version").use { result ->
                result.next()
                assertEquals(99, result.getInt(1))
            }
        }
    }

    @Test
    fun `repository persists manual enabled state and deletion`() {
        val path = createTempDirectory("ansiblex-repository").resolve("user.db")
        val repository = SqliteAnsiblexRepository(
            path,
            clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC),
            idGenerator = { "manual-1" },
        )

        val added = repository.addManual(AnsiblexDraft(20, 10, bidirectional = false, displayName = "QA"))
        assertEquals(10, added.firstSystemId)
        assertEquals(20, added.secondSystemId)
        assertEquals(20, added.logicalFromSystemId())
        assertTrue(repository.setEnabled("manual-1", false))
        assertFalse(repository.getAll().single().enabled)
        assertTrue(repository.delete("manual-1"))
        assertEquals(emptyList(), repository.getAll())
    }

    @Test
    fun `schema rejects self loops reverse duplicate and invalid enabled`() {
        val path = createTempDirectory("ansiblex-constraints").resolve("user.db")
        UserDatabase.initialize(path)
        UserDatabase.open(path).use { connection ->
            connection.createStatement().execute(
                """
                INSERT INTO ansiblex_connections VALUES(
                    'one', 10, 20, 'BIDIRECTIONAL', NULL, NULL, 'MANUAL', NULL, 1,
                    '2026-08-17T00:00:00Z', '2026-08-17T00:00:00Z'
                )
                """.trimIndent(),
            )
            assertFailsWith<SQLException> {
                connection.createStatement().execute(
                    """
                    INSERT INTO ansiblex_connections VALUES(
                        'reverse', 10, 20, 'SECOND_TO_FIRST', NULL, NULL, 'MANUAL', NULL, 1,
                        '2026-08-17T00:00:00Z', '2026-08-17T00:00:00Z'
                    )
                    """.trimIndent(),
                )
            }
            assertFailsWith<SQLException> {
                connection.createStatement().execute(
                    """
                    INSERT INTO ansiblex_connections VALUES(
                        'loop', 10, 10, 'BIDIRECTIONAL', NULL, NULL, 'MANUAL', NULL, 1,
                        '2026-08-17T00:00:00Z', '2026-08-17T00:00:00Z'
                    )
                    """.trimIndent(),
                )
            }
            assertFailsWith<SQLException> {
                connection.createStatement().execute(
                    """
                    INSERT INTO ansiblex_connections VALUES(
                        'enabled', 30, 40, 'BIDIRECTIONAL', NULL, NULL, 'MANUAL', NULL, 2,
                        '2026-08-17T00:00:00Z', '2026-08-17T00:00:00Z'
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
