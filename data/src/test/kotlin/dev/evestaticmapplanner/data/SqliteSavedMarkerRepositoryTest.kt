package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
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
import kotlin.test.assertFalse
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
        assertTrue(loaded.all { it.createdBy == SavedMarkerCreatedBy.USER })
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
        assertEquals(SavedMarkerCreatedBy.USER, updated.createdBy)
        assertTrue(repository.delete(42))
        assertEquals(emptyList(), repository.getAll())
        assertFailsWith<IllegalStateException> { repository.update(42, MarkerDraft.create()) }
        assertFailsWith<IllegalStateException> { repository.delete(42) }
    }

    @Test
    fun `AI provenance persists across reload update and child mutations`() {
        val path = createTempDirectory("saved-marker-ai-provenance").resolve("user.db")
        val createdAt = Instant.parse("2026-08-21T01:00:00Z")
        val updatedAt = Instant.parse("2026-08-21T02:00:00Z")
        val repository = SqliteSavedMarkerRepository(
            path,
            clock = fixedClock(createdAt),
            idGenerator = { "ai-child" },
        )

        val created = repository.create(
            systemId = 84,
            draft = MarkerDraft.create(name = "AI origin", notes = "Keep source", color = MarkerColor.PURPLE),
            createdBy = SavedMarkerCreatedBy.AI,
        )
        assertEquals(SavedMarkerCreatedBy.AI, created.createdBy)
        assertEquals(SavedMarkerCreatedBy.AI, SqliteSavedMarkerRepository(path).getAll().single().createdBy)

        val child = repository.addChild(84, SavedMarkerChildType.of("staging"))
        assertEquals(SavedMarkerCreatedBy.AI, repository.getAll().single().createdBy)
        assertTrue(repository.removeChild(84, child.id))
        assertEquals(SavedMarkerCreatedBy.AI, repository.getAll().single().createdBy)

        val updated = SqliteSavedMarkerRepository(path, fixedClock(updatedAt)).update(
            84,
            MarkerDraft.create(name = "User edited", notes = "Still AI origin", color = MarkerColor.BLUE),
        )
        assertEquals(SavedMarkerCreatedBy.AI, updated.createdBy)
        assertEquals(createdAt, updated.createdAt)
        assertEquals(SavedMarkerCreatedBy.AI, SqliteSavedMarkerRepository(path).getAll().single().createdBy)
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
                "INSERT INTO saved_markers VALUES(1, NULL, NULL, 'CYAN', '2026-08-21T00:00:00Z', '2026-08-21T00:00:00Z', 'USER')",
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
                "INSERT INTO saved_markers VALUES(1, NULL, NULL, 'YELLOW', 'not-an-instant', '2026-08-21T00:00:00Z', 'USER')",
            )
        }

        assertFails { repository.getAll() }
    }

    @Test
    fun `load rejects unknown provenance without fallback`() {
        val path = createTempDirectory("saved-marker-invalid-provenance").resolve("user.db")
        val repository = SqliteSavedMarkerRepository(path)
        UserDatabase.open(path).use { connection ->
            connection.createStatement().execute("PRAGMA ignore_check_constraints = ON")
            connection.createStatement().execute(
                "INSERT INTO saved_markers VALUES(1, NULL, NULL, 'YELLOW', '2026-08-21T00:00:00Z', '2026-08-21T00:00:00Z', 'INVALID')",
            )
        }

        val error = assertFailsWith<IllegalStateException> { repository.getAll() }
        assertTrue(error.message.orEmpty().contains("Unknown saved marker created_by value: INVALID"))
    }

    @Test
    fun `parent without children remains valid and children persist in deterministic order`() {
        val path = createTempDirectory("saved-marker-children").resolve("user.db")
        val ids = ArrayDeque(listOf("child-a", "child-b", "child-c"))
        val repository = SqliteSavedMarkerRepository(path, idGenerator = ids::removeFirst)
        val parent = repository.create(42, MarkerDraft.create(name = "Parent", notes = "Keep"))

        assertEquals(emptyList(), repository.getChildren(42))
        val first = repository.addChild(42, SavedMarkerChildType.of("staging"))
        val second = repository.addChild(42, SavedMarkerChildType.of("danger"))
        val third = repository.addChild(42, SavedMarkerChildType.of("logistics"))

        assertEquals(listOf(0, 1, 2), listOf(first, second, third).map { it.orderIndex })
        val reopened = SqliteSavedMarkerRepository(path)
        assertEquals(parent, reopened.getAll().single())
        assertEquals(listOf("child-a", "child-b", "child-c"), reopened.getChildren(42).map { it.id })
        assertEquals(listOf("staging", "danger", "logistics"), reopened.getChildren(42).map { it.type.key })
    }

    @Test
    fun `removing one child preserves sibling identity and stable sparse ordering`() {
        val path = createTempDirectory("saved-marker-child-remove").resolve("user.db")
        val ids = ArrayDeque(listOf("a", "b", "c", "d"))
        val repository = SqliteSavedMarkerRepository(path, idGenerator = ids::removeFirst)
        repository.create(7, MarkerDraft.create())
        val a = repository.addChild(7, SavedMarkerChildType.of("a"))
        val b = repository.addChild(7, SavedMarkerChildType.of("b"))
        val c = repository.addChild(7, SavedMarkerChildType.of("c"))

        assertTrue(repository.removeChild(7, b.id))
        assertFalse(repository.removeChild(7, b.id))
        assertEquals(listOf(a, c), repository.getChildren(7))
        val d = repository.addChild(7, SavedMarkerChildType.of("d"))
        assertEquals(3, d.orderIndex)
        assertEquals(listOf(0, 2, 3), repository.getChildren(7).map { it.orderIndex })
    }

    @Test
    fun `parent deletion cascades children while other parents stay isolated`() {
        val path = createTempDirectory("saved-marker-child-cascade").resolve("user.db")
        val ids = ArrayDeque(listOf("child-x", "child-y"))
        val repository = SqliteSavedMarkerRepository(path, idGenerator = ids::removeFirst)
        repository.create(10, MarkerDraft.create(name = "A"))
        repository.create(20, MarkerDraft.create(name = "B"))
        val childA = repository.addChild(10, SavedMarkerChildType.of("danger"))
        val childB = repository.addChild(20, SavedMarkerChildType.of("home"))

        assertFalse(repository.removeChild(20, childA.id))
        assertTrue(repository.delete(10))
        assertEquals(emptyList(), repository.getChildren(10))
        assertEquals(listOf(childB), repository.getChildren(20))
        UserDatabase.open(path).use { connection ->
            val orphanCount = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM saved_marker_children child
                    LEFT JOIN saved_markers parent ON parent.system_id = child.parent_system_id
                    WHERE parent.system_id IS NULL
                    """.trimIndent(),
                ).use { result -> result.next(); result.getInt(1) }
            }
            assertEquals(0, orphanCount)
        }
    }

    @Test
    fun `duplicate type is rejected per parent but remains valid on another parent`() {
        val path = createTempDirectory("saved-marker-child-duplicate").resolve("user.db")
        val ids = ArrayDeque(listOf("first", "duplicate", "other-parent"))
        val repository = SqliteSavedMarkerRepository(path, idGenerator = ids::removeFirst)
        repository.create(1, MarkerDraft.create())
        repository.create(2, MarkerDraft.create())
        repository.addChild(1, SavedMarkerChildType.of("Danger"))

        assertFailsWith<SQLException> {
            repository.addChild(1, SavedMarkerChildType.of(" danger "))
        }
        repository.addChild(2, SavedMarkerChildType.of("danger"))
        assertEquals(1, repository.getChildren(1).size)
        assertEquals(1, repository.getChildren(2).size)
    }

    @Test
    fun `bulk child load returns all populated parents in deterministic order without n plus one queries`() {
        val path = createTempDirectory("saved-marker-child-bulk").resolve("user.db")
        val ids = ArrayDeque(listOf("a", "b", "c"))
        val repository = SqliteSavedMarkerRepository(path, idGenerator = ids::removeFirst)
        repository.create(10, MarkerDraft.create())
        repository.create(20, MarkerDraft.create())
        repository.create(30, MarkerDraft.create())
        repository.addChild(20, SavedMarkerChildType.of("danger"))
        repository.addChild(10, SavedMarkerChildType.of("staging"))
        repository.addChild(10, SavedMarkerChildType.of("keepstar"))

        val loaded = repository.getAllChildren()

        assertEquals(listOf(10, 20), loaded.keys.toList())
        assertEquals(listOf("staging", "keepstar"), loaded.getValue(10).map { it.type.key })
        assertEquals(listOf("danger"), loaded.getValue(20).map { it.type.key })
        assertFalse(30 in loaded)
    }
}

private fun fixedClock(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)
