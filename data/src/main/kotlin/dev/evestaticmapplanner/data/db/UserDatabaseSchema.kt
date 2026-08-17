package dev.evestaticmapplanner.data.db

import java.sql.Connection

object UserDatabaseSchema {
    const val VERSION = 1

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
    )

    fun create(connection: Connection) {
        connection.createStatement().use { statement ->
            createStatements.forEach(statement::execute)
            statement.execute("PRAGMA user_version = $VERSION")
        }
    }
}
