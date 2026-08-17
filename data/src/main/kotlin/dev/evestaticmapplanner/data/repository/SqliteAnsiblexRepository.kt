package dev.evestaticmapplanner.data.repository

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.data.db.UserDatabase
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SqliteAnsiblexRepository(
    private val databasePath: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : AnsiblexRepository {
    init {
        UserDatabase.initialize(databasePath)
    }

    override fun getAll(): List<AnsiblexConnection> = UserDatabase.open(databasePath).use { connection ->
        connection.selectAllAnsiblex()
    }

    override fun addManual(draft: AnsiblexDraft): AnsiblexConnection {
        val now = clock.instant()
        val connection = AnsiblexConnection(
            id = idGenerator(),
            firstSystemId = draft.firstSystemId,
            secondSystemId = draft.secondSystemId,
            direction = draft.direction,
            displayName = draft.displayName?.trim()?.takeIf(String::isNotEmpty),
            notes = draft.notes?.trim()?.takeIf(String::isNotEmpty),
            source = AnsiblexSource.MANUAL,
            sourceBatchId = null,
            enabled = draft.enabled,
            createdAt = now,
            updatedAt = now,
        )
        UserDatabase.open(databasePath).use { database ->
            database.insertAnsiblex(connection)
        }
        return connection
    }

    override fun setEnabled(id: String, enabled: Boolean): Boolean = UserDatabase.open(databasePath).use { connection ->
        connection.prepareStatement(
            "UPDATE ansiblex_connections SET enabled = ?, updated_at = ? WHERE id = ?",
        ).use { statement ->
            statement.setInt(1, if (enabled) 1 else 0)
            statement.setString(2, clock.instant().toString())
            statement.setString(3, id)
            statement.executeUpdate() == 1
        }
    }

    override fun delete(id: String): Boolean = UserDatabase.open(databasePath).use { connection ->
        connection.prepareStatement("DELETE FROM ansiblex_connections WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeUpdate() == 1
        }
    }

    override fun clearImported(): Int = clear("source = 'IMPORT'")

    override fun clearAll(): Int = clear("1 = 1")

    private fun clear(predicate: String): Int = UserDatabase.open(databasePath).use { connection ->
        connection.createStatement().use { it.executeUpdate("DELETE FROM ansiblex_connections WHERE $predicate") }
    }
}

internal fun Connection.insertAnsiblex(connection: AnsiblexConnection) {
    prepareStatement(
        """
        INSERT INTO ansiblex_connections(
            id, first_system_id, second_system_id, direction, display_name, notes,
            source, source_batch_id, enabled, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, connection.id)
        statement.setInt(2, connection.firstSystemId)
        statement.setInt(3, connection.secondSystemId)
        statement.setString(4, connection.direction.name)
        statement.setString(5, connection.displayName)
        statement.setString(6, connection.notes)
        statement.setString(7, connection.source.name)
        statement.setString(8, connection.sourceBatchId)
        statement.setInt(9, if (connection.enabled) 1 else 0)
        statement.setString(10, connection.createdAt.toString())
        statement.setString(11, connection.updatedAt.toString())
        statement.executeUpdate()
    }
}

internal fun Connection.selectAllAnsiblex(): List<AnsiblexConnection> = prepareStatement(
    "SELECT * FROM ansiblex_connections ORDER BY first_system_id, second_system_id",
).use { statement ->
    statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toAnsiblex()) } }
}

internal fun ResultSet.toAnsiblex(): AnsiblexConnection = AnsiblexConnection(
    id = getString("id"),
    firstSystemId = getInt("first_system_id"),
    secondSystemId = getInt("second_system_id"),
    direction = AnsiblexDirection.valueOf(getString("direction")),
    displayName = getString("display_name"),
    notes = getString("notes"),
    source = AnsiblexSource.valueOf(getString("source")),
    sourceBatchId = getString("source_batch_id"),
    enabled = getInt("enabled") == 1,
    createdAt = Instant.parse(getString("created_at")),
    updatedAt = Instant.parse(getString("updated_at")),
)
