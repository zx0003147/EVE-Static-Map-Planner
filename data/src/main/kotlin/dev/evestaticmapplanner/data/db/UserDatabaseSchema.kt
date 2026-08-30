package dev.evestaticmapplanner.data.db

import java.sql.Connection

object UserDatabaseSchema {
    const val VERSION = 5

    internal val planningViewsCreateStatements = listOf(
        """
        CREATE TABLE planning_views (
            id TEXT NOT NULL PRIMARY KEY CHECK(length(trim(id)) BETWEEN 1 AND 120),
            label TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(label)) BETWEEN 1 AND 80),
            order_index INTEGER NOT NULL UNIQUE CHECK(order_index >= 0),
            is_current INTEGER NOT NULL CHECK(is_current IN (0, 1)),
            normal_from_system_id INTEGER CHECK(normal_from_system_id IS NULL OR normal_from_system_id > 0),
            normal_to_system_id INTEGER CHECK(normal_to_system_id IS NULL OR normal_to_system_id > 0),
            normal_use_ansiblex INTEGER NOT NULL CHECK(normal_use_ansiblex IN (0, 1)),
            normal_calculated INTEGER NOT NULL CHECK(normal_calculated IN (0, 1)),
            capital_from_system_id INTEGER CHECK(capital_from_system_id IS NULL OR capital_from_system_id > 0),
            capital_to_system_id INTEGER CHECK(capital_to_system_id IS NULL OR capital_to_system_id > 0),
            capital_range_text TEXT NOT NULL CHECK(length(trim(capital_range_text)) BETWEEN 1 AND 32),
            capital_calculated INTEGER NOT NULL CHECK(capital_calculated IN (0, 1)),
            selected_route_action_targets_json TEXT NOT NULL DEFAULT '{}'
                CHECK(json_valid(selected_route_action_targets_json))
        ) STRICT
        """.trimIndent(),
        "CREATE UNIQUE INDEX idx_planning_views_current ON planning_views(is_current) WHERE is_current = 1",
    )

    internal val aiMissionsCreateStatements = listOf(
        """
        CREATE TABLE ai_missions (
            mission_id TEXT NOT NULL PRIMARY KEY CHECK(length(trim(mission_id)) BETWEEN 1 AND 120),
            view_id TEXT NOT NULL CHECK(length(trim(view_id)) BETWEEN 1 AND 120),
            order_index INTEGER NOT NULL UNIQUE CHECK(order_index >= 0),
            payload_json TEXT NOT NULL CHECK(json_valid(payload_json))
        ) STRICT
        """.trimIndent(),
        "CREATE INDEX idx_ai_missions_view ON ai_missions(view_id, order_index)",
    )

    private val savedMarkersVersionTwoCreateStatement =
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

    internal val savedMarkersCreateStatement =
        """
        CREATE TABLE saved_markers (
            system_id INTEGER PRIMARY KEY CHECK(system_id > 0),
            name TEXT CHECK(name IS NULL OR length(trim(name)) > 0),
            notes TEXT CHECK(notes IS NULL OR length(trim(notes)) > 0),
            color TEXT NOT NULL CHECK(color IN ('RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE', 'PURPLE', 'WHITE')),
            created_at TEXT NOT NULL CHECK(length(trim(created_at)) > 0),
            updated_at TEXT NOT NULL CHECK(length(trim(updated_at)) > 0),
            created_by TEXT NOT NULL DEFAULT 'USER' CHECK(created_by IN ('USER', 'AI'))
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
    ) + savedMarkerChildrenCreateStatements + planningViewsCreateStatements + aiMissionsCreateStatements

    fun create(connection: Connection) {
        connection.createStatement().use { statement ->
            createStatements.forEach(statement::execute)
            statement.execute("PRAGMA user_version = $VERSION")
        }
    }

    internal fun addVersionTwoSavedMarkers(connection: Connection) {
        connection.createStatement().use { it.execute(savedMarkersVersionTwoCreateStatement) }
    }

    internal fun addSavedMarkerChildren(connection: Connection) {
        connection.createStatement().use { statement ->
            savedMarkerChildrenCreateStatements.forEach(statement::execute)
        }
    }

    internal fun addSavedMarkerProvenance(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                ALTER TABLE saved_markers
                ADD COLUMN created_by TEXT NOT NULL DEFAULT 'USER'
                    CHECK(created_by IN ('USER', 'AI'))
                """.trimIndent(),
            )
        }
    }

    internal fun addPlanningViews(connection: Connection) {
        connection.createStatement().use { statement ->
            (planningViewsCreateStatements + aiMissionsCreateStatements).forEach(statement::execute)
        }
    }
}
