package dev.evestaticmapplanner.data.repository

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.repository.SavedMarkerRepository
import dev.evestaticmapplanner.data.db.UserDatabase
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

class SqliteSavedMarkerRepository(
    private val databasePath: Path,
    private val clock: Clock = Clock.systemUTC(),
    initializeDatabase: Boolean = true,
) : SavedMarkerRepository {
    init {
        if (initializeDatabase) UserDatabase.initialize(databasePath)
    }

    override fun getAll(): List<Marker> = UserDatabase.open(databasePath).use { connection ->
        connection.prepareStatement("SELECT * FROM saved_markers ORDER BY system_id").use { statement ->
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.toSavedMarker()) }
            }
        }
    }

    override fun create(systemId: Int, draft: MarkerDraft): Marker {
        require(systemId > 0) { "Marker solar system ID must be positive" }
        val now = clock.instant()
        val marker = Marker.saved(systemId, draft, createdAt = now, updatedAt = now)
        UserDatabase.open(databasePath).use { connection -> connection.insertSavedMarker(marker) }
        return marker
    }

    override fun update(systemId: Int, draft: MarkerDraft): Marker {
        require(systemId > 0) { "Marker solar system ID must be positive" }
        return UserDatabase.open(databasePath).use { connection ->
            connection.autoCommit = false
            try {
                val updatedAt = clock.instant()
                val affected = connection.prepareStatement(
                    """
                    UPDATE saved_markers
                    SET name = ?, notes = ?, color = ?, updated_at = ?
                    WHERE system_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, draft.name)
                    statement.setString(2, draft.notes)
                    statement.setString(3, draft.color.name)
                    statement.setString(4, updatedAt.toString())
                    statement.setInt(5, systemId)
                    statement.executeUpdate()
                }
                check(affected == 1) { "Saved marker no longer exists for solar system $systemId" }
                val marker = connection.selectSavedMarker(systemId)
                    ?: error("Saved marker disappeared after update for solar system $systemId")
                connection.commit()
                marker
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun delete(systemId: Int): Boolean {
        require(systemId > 0) { "Marker solar system ID must be positive" }
        return UserDatabase.open(databasePath).use { connection ->
            val affected = connection.prepareStatement("DELETE FROM saved_markers WHERE system_id = ?").use { statement ->
                statement.setInt(1, systemId)
                statement.executeUpdate()
            }
            check(affected == 1) { "Saved marker no longer exists for solar system $systemId" }
            true
        }
    }
}

private fun Connection.insertSavedMarker(marker: Marker) {
    prepareStatement(
        """
        INSERT INTO saved_markers(system_id, name, notes, color, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, marker.systemId)
        statement.setString(2, marker.name)
        statement.setString(3, marker.notes)
        statement.setString(4, marker.color.name)
        statement.setString(5, checkNotNull(marker.createdAt).toString())
        statement.setString(6, checkNotNull(marker.updatedAt).toString())
        check(statement.executeUpdate() == 1) {
            "Unable to create saved marker for solar system ${marker.systemId}"
        }
    }
}

private fun Connection.selectSavedMarker(systemId: Int): Marker? = prepareStatement(
    "SELECT * FROM saved_markers WHERE system_id = ?",
).use { statement ->
    statement.setInt(1, systemId)
    statement.executeQuery().use { result -> if (result.next()) result.toSavedMarker() else null }
}

private fun ResultSet.toSavedMarker(): Marker = Marker.saved(
    systemId = getInt("system_id"),
    draft = MarkerDraft.create(
        name = getString("name"),
        notes = getString("notes"),
        color = MarkerColor.valueOf(getString("color")),
    ),
    createdAt = Instant.parse(getString("created_at")),
    updatedAt = Instant.parse(getString("updated_at")),
)
