package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.data.db.UserDatabase
import dev.evestaticmapplanner.data.repository.SqliteSavedMarkerRepository
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteSavedMarkerRepositoryTest {
    @Test
    fun `create normalizes text persists all colors and reopens cleanly`() {
        val path = createTempDirectory("saved-marker-create").resolve("user.db")
        val instant = Instant.parse("2026-08-21T03:04:05Z")
        val repository = SqliteSavedMarkerRepository(path, fixedClock(instant))

        MarkerColor.entries.forEachIndexed { index, color ->
            repository.create(
                systemId = index + 1,
                draft = MarkerDraft.create(
                    name = if (index == 0) "  Staging  " else null,
                    notes = if (index == 0) "   " else "notes-$index",
                    color = color,
                ),
            )
        }

        val loaded = SqliteSavedMarkerRepository(path).getAll()
        assertEquals(MarkerColor.entries, loaded.map { it.color })
        assertTrue(loaded.all { it.persistence == MarkerPersistence.SAVED })
        assertTrue(loaded.all { it.createdAt == instant && it.updatedAt == instant })
        assertEquals("Staging", loaded.first().name)
        assertNull(loaded.first().notes)
    }

    @Test
    fun `update preserves created timestamp and delete requires exactly one row`() {
        val path = createTempDirectory("saved-marker-update").resolve("user.db")
        val createdAt = Instant.parse("2026-08-21T01:00:00Z")
        val updatedAt = Instant.parse("2026-08-21T02:00:00Z")
        SqliteSavedMarkerRepository(path, fixedClock(createdAt)).create(
            42,
            MarkerDraft.create(name = "Old", color = MarkerColor.RED),
        )
        val repository = SqliteSavedMarkerRepository(path, fixedClock(updatedAt))

        val updated = repository.update(
            42,
            MarkerDraft.create(name = " New ", notes = " Updated ", color = MarkerColor.BLUE),
        )

        assertEquals(createdAt, updated.createdAt)
        assertEquals(updatedAt, updated.updatedAt)
        assertEquals("New", updated.name)
        assertEquals("Updated", updated.notes)
        assertEquals(MarkerColor.BLUE, updated.color)
        assertTrue(repository.delete(42))
        assertEquals(emptyList(), repository.getAll())
        assertFailsWith<IllegalStateException> { repository.update(42, MarkerDraft.create()) }
        assertFailsWith<IllegalStateException> { repository.delete(42) }
    }

    @Test
    fun `duplicate system and SQL trigger failures are explicit and preserve prior data`() {
        val path = createTempDirectory("saved-marker-sql-failure").resolve("user.db")
        val repository = SqliteSavedMarkerRepository(path)
        val original = repository.create(7, MarkerDraft.create(name = "Keep"))

        assertFailsWith<SQLException> { repository.create(7, MarkerDraft.create(name = "Duplicate")) }
        UserDatabase.open(path).use { connection ->
            connection.createStatement().execute(
                """
                CREATE TRIGGER reject_marker_update
                BEFORE UPDATE ON saved_markers
                BEGIN SELECT RAISE(ABORT, 'forced marker update failure'); END
                """.trimIndent(),
            )
        }

        assertFailsWith<SQLException> {
            repository.update(7, MarkerDraft.create(name = "Must not persist", color = MarkerColor.GREEN))
        }
        assertEquals(original, repository.getAll().single())
    }

    @Test
    fun `load rejects unknown color without fallback`() {
        val path = createTempDirectory("saved-marker-invalid-color").resolve("user.db")
        val repository = SqliteSavedMarkerRepository(path)
        UserDatabase.open(path).use { connection ->
            connection.createStatement().execute("PRAGMA ignore_check_constraints = ON")
            connection.createStatement().execute(
                "INSERT INTO saved_markers VALUES(1, NULL, NULL, 'CYAN', '2026-08-21T00:00:00Z', '2026-08-21T00:00:00Z')",
            )
        }

        assertFails { repository.getAll() }
    }

    @Test
    fun `load rejects invalid timestamp`() {
        val path = createTempDirectory("saved-marker-invalid-time").resolve("user.db")
        val repository = SqliteSavedMarkerRepository(path)
        UserDatabase.open(path).use { connection ->
            connection.createStatement().execute(
                "INSERT INTO saved_markers VALUES(1, NULL, NULL, 'YELLOW', 'not-an-instant', '2026-08-21T00:00:00Z')",
            )
        }

        assertFails { repository.getAll() }
    }
}

private fun fixedClock(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)
