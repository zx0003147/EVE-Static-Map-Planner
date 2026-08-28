package dev.evestaticmapplanner.data.repository

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import dev.evestaticmapplanner.core.repository.SavedMarkerRepository
import dev.evestaticmapplanner.data.db.UserDatabase
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SqliteSavedMarkerRepository(
    private val databasePath: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
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

    override fun create(systemId: Int, draft: MarkerDraft, createdBy: SavedMarkerCreatedBy): Marker {
        require(systemId > 0) { "Marker solar system ID must be positive" }
        val now = clock.instant()
        val marker = Marker.saved(systemId, draft, createdAt = now, updatedAt = now, createdBy = createdBy)
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

    override fun getChildren(parentSystemId: Int): List<SavedMarkerChild> {
        require(parentSystemId > 0) { "Saved marker parent system ID must be positive" }
        return UserDatabase.open(databasePath).use { connection ->
            connection.prepareStatement(
                """
                SELECT id, parent_system_id, type_key, order_index
                FROM saved_marker_children
                WHERE parent_system_id = ?
                ORDER BY order_index, id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, parentSystemId)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.toSavedMarkerChild()) }
                }
            }
        }
    }

    override fun getAllChildren(): Map<Int, List<SavedMarkerChild>> {
        return UserDatabase.open(databasePath).use { connection ->
            connection.prepareStatement(
                """
                SELECT id, parent_system_id, type_key, order_index
                FROM saved_marker_children
                ORDER BY parent_system_id, order_index, id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val childrenByParent = linkedMapOf<Int, MutableList<SavedMarkerChild>>()
                    while (result.next()) {
                        val child = result.toSavedMarkerChild()
                        childrenByParent.getOrPut(child.parentSystemId, ::mutableListOf).add(child)
                    }
                    childrenByParent.mapValues { (_, children) -> children.toList() }
                }
            }
        }
    }

    override fun addChild(parentSystemId: Int, type: SavedMarkerChildType): SavedMarkerChild {
        require(parentSystemId > 0) { "Saved marker parent system ID must be positive" }
        return UserDatabase.open(databasePath).use { connection ->
            connection.autoCommit = false
            try {
                val orderIndex = connection.nextChildOrderIndex(parentSystemId)
                val child = SavedMarkerChild.create(
                    id = idGenerator(),
                    parentSystemId = parentSystemId,
                    type = type,
                    orderIndex = orderIndex,
                )
                connection.insertSavedMarkerChild(child)
                connection.commit()
                child
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun removeChild(parentSystemId: Int, childId: String): Boolean {
        require(parentSystemId > 0) { "Saved marker parent system ID must be positive" }
        require(childId.isNotBlank()) { "Saved marker child ID must not be blank" }
        return UserDatabase.open(databasePath).use { connection ->
            connection.prepareStatement(
                "DELETE FROM saved_marker_children WHERE parent_system_id = ? AND id = ?",
            ).use { statement ->
                statement.setInt(1, parentSystemId)
                statement.setString(2, childId)
                statement.executeUpdate() == 1
            }
        }
    }
}

private fun Connection.nextChildOrderIndex(parentSystemId: Int): Int = prepareStatement(
    "SELECT COALESCE(MAX(order_index), -1) + 1 FROM saved_marker_children WHERE parent_system_id = ?",
).use { statement ->
    statement.setInt(1, parentSystemId)
    statement.executeQuery().use { result ->
        check(result.next())
        result.getInt(1)
    }
}

private fun Connection.insertSavedMarkerChild(child: SavedMarkerChild) {
    prepareStatement(
        """
        INSERT INTO saved_marker_children(id, parent_system_id, type_key, order_index)
        VALUES (?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, child.id)
        statement.setInt(2, child.parentSystemId)
        statement.setString(3, child.type.key)
        statement.setInt(4, child.orderIndex)
        check(statement.executeUpdate() == 1) {
            "Unable to create child ${child.type.key} for saved marker ${child.parentSystemId}"
        }
    }
}

private fun Connection.insertSavedMarker(marker: Marker) {
    prepareStatement(
        """
        INSERT INTO saved_markers(system_id, name, notes, color, created_at, updated_at, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, marker.systemId)
        statement.setString(2, marker.name)
        statement.setString(3, marker.notes)
        statement.setString(4, marker.color.name)
        statement.setString(5, checkNotNull(marker.createdAt).toString())
        statement.setString(6, checkNotNull(marker.updatedAt).toString())
        statement.setString(7, checkNotNull(marker.createdBy).name)
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
    createdBy = decodeSavedMarkerCreatedBy(getString("created_by")),
)

private fun decodeSavedMarkerCreatedBy(value: String): SavedMarkerCreatedBy =
    runCatching { SavedMarkerCreatedBy.valueOf(value) }
        .getOrElse { error -> throw IllegalStateException("Unknown saved marker created_by value: $value", error) }

private fun ResultSet.toSavedMarkerChild(): SavedMarkerChild = SavedMarkerChild.create(
    id = getString("id"),
    parentSystemId = getInt("parent_system_id"),
    type = SavedMarkerChildType.of(getString("type_key")),
    orderIndex = getInt("order_index"),
)
