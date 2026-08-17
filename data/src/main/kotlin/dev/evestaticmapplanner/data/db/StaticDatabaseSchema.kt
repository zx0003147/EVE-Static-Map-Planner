package dev.evestaticmapplanner.data.db

import java.sql.Connection

object StaticDatabaseSchema {
    const val VERSION = 1

    private val createStatements = listOf(
        """
        CREATE TABLE metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        ) STRICT
        """.trimIndent(),
        """
        CREATE TABLE source_files (
            file_name TEXT PRIMARY KEY,
            sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
            row_count INTEGER NOT NULL CHECK(row_count >= 0)
        ) STRICT
        """.trimIndent(),
        """
        CREATE TABLE regions (
            region_id INTEGER PRIMARY KEY,
            name_en TEXT NOT NULL CHECK(length(trim(name_en)) > 0),
            position_x REAL NOT NULL,
            position_y REAL NOT NULL,
            position_z REAL NOT NULL,
            wormhole_class_id INTEGER
        ) STRICT
        """.trimIndent(),
        """
        CREATE TABLE constellations (
            constellation_id INTEGER PRIMARY KEY,
            region_id INTEGER NOT NULL,
            name_en TEXT NOT NULL CHECK(length(trim(name_en)) > 0),
            position_x REAL NOT NULL,
            position_y REAL NOT NULL,
            position_z REAL NOT NULL,
            wormhole_class_id INTEGER,
            CONSTRAINT uq_constellations_id_region UNIQUE(constellation_id, region_id),
            CONSTRAINT fk_constellations_region
                FOREIGN KEY(region_id) REFERENCES regions(region_id)
        ) STRICT
        """.trimIndent(),
        """
        CREATE TABLE systems (
            system_id INTEGER PRIMARY KEY,
            constellation_id INTEGER NOT NULL,
            region_id INTEGER NOT NULL,
            name_en TEXT NOT NULL CHECK(length(trim(name_en)) > 0),
            security_status REAL NOT NULL CHECK(security_status >= -1.0 AND security_status <= 1.0),
            security_class TEXT,
            position_x REAL NOT NULL,
            position_y REAL NOT NULL,
            position_z REAL NOT NULL,
            position_2d_x REAL,
            position_2d_y REAL,
            radius REAL NOT NULL CHECK(radius >= 0.0),
            faction_id INTEGER,
            wormhole_class_id INTEGER,
            CONSTRAINT ck_systems_position_2d_pair CHECK(
                (position_2d_x IS NULL AND position_2d_y IS NULL) OR
                (position_2d_x IS NOT NULL AND position_2d_y IS NOT NULL)
            ),
            CONSTRAINT fk_systems_constellation_region
                FOREIGN KEY(constellation_id, region_id)
                REFERENCES constellations(constellation_id, region_id),
            CONSTRAINT fk_systems_region
                FOREIGN KEY(region_id) REFERENCES regions(region_id)
        ) STRICT
        """.trimIndent(),
        """
        CREATE TABLE stargates (
            gate_id INTEGER PRIMARY KEY,
            from_system_id INTEGER NOT NULL,
            to_system_id INTEGER NOT NULL,
            destination_gate_id INTEGER NOT NULL UNIQUE,
            type_id INTEGER NOT NULL,
            position_x REAL NOT NULL,
            position_y REAL NOT NULL,
            position_z REAL NOT NULL,
            CONSTRAINT ck_stargates_not_self CHECK(gate_id <> destination_gate_id),
            CONSTRAINT ck_stargates_system_not_self CHECK(from_system_id <> to_system_id),
            CONSTRAINT fk_stargates_from_system
                FOREIGN KEY(from_system_id) REFERENCES systems(system_id),
            CONSTRAINT fk_stargates_to_system
                FOREIGN KEY(to_system_id) REFERENCES systems(system_id),
            CONSTRAINT fk_stargates_destination_gate
                FOREIGN KEY(destination_gate_id) REFERENCES stargates(gate_id)
                DEFERRABLE INITIALLY DEFERRED
        ) STRICT
        """.trimIndent(),
        "CREATE INDEX idx_constellations_region ON constellations(region_id)",
        "CREATE INDEX idx_systems_region ON systems(region_id)",
        "CREATE INDEX idx_systems_constellation ON systems(constellation_id)",
        "CREATE INDEX idx_systems_name_nocase ON systems(name_en COLLATE NOCASE)",
        "CREATE INDEX idx_stargates_from_system ON stargates(from_system_id)",
        "CREATE INDEX idx_stargates_to_system ON stargates(to_system_id)",
    )

    fun create(connection: Connection) {
        connection.createStatement().use { statement ->
            createStatements.forEach(statement::execute)
            statement.execute("PRAGMA user_version = $VERSION")
        }
    }
}
