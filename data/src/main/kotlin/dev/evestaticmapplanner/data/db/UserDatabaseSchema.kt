package dev.evestaticmapplanner.data.db

import java.sql.Connection

object UserDatabaseSchema {
    const val VERSION = 3

    internal val savedMarkersCreateStatement =
        """
        CREATE TABLE saved_markers (
            system_id INTEGER PRIMARY KEY CHECK(system_id > 0),
            name TEXT CHECK(name IS NULL OR length(trim(name)) > 0),
            notes TEXT CHECK(notes IS NULL OR length(trim(notes)) > 0),
            color TEXT NOT NULL CHECK(color IN ('RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE', 'PURPLE', 'WHITE')),
            created_at TEXT NOT NULL CHECK(length(trim(created_at)) > 0),
            updated_at TEXT NOT NULL CHECK(length(trim(updated_at)) > 0)
        ) STRICT
        """.trimIndent()

    internal val savedMarkerChildrenCreateStatements = listOf(
        """
        CREATE TABLE saved_marker_children (
            id TEXT NOT NULL PRIMARY KEY CHECK(length(trim(id)) > 0),
            parent_system_id INTEGER NOT NULL CHECK(parent_system_id > 0),
            type_key TEXT NOT NULL CHECK(
                length(type_key) BETWEEN 1 AND 64 AND
                type_key = lower(type_key) AND
                type_key NOT GLOB '*[^a-z0-9._-]*' AND
                substr(type_key, 1, 1) GLOB '[a-z0-9]'
            ),
            order_index INTEGER NOT NULL CHECK(order_index >= 0),
            CONSTRAINT uq_saved_marker_child_type UNIQUE(parent_system_id, type_key),
            CONSTRAINT uq_saved_marker_child_order UNIQUE(parent_system_id, order_index),
            CONSTRAINT fk_saved_marker_child_parent
                FOREIGN KEY(parent_system_id) REFERENCES saved_markers(system_id) ON DELETE CASCADE
        ) STRICT
        """.trimIndent(),
    )

    private val createStatements = listOf(
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
        savedMarkersCreateStatement,
    ) + savedMarkerChildrenCreateStatements

    fun create(connection: Connection) {
        connection.createStatement().use { statement ->
            createStatements.forEach(statement::execute)
            statement.execute("PRAGMA user_version = $VERSION")
        }
    }

    internal fun addSavedMarkers(connection: Connection) {
        connection.createStatement().use { it.execute(savedMarkersCreateStatement) }
    }

    internal fun addSavedMarkerChildren(connection: Connection) {
        connection.createStatement().use { statement ->
            savedMarkerChildrenCreateStatements.forEach(statement::execute)
        }
    }
}
