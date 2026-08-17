package dev.evestaticmapplanner.data.db

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.Stargate
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement

data class SourceFileAudit(
    val fileName: String,
    val sha256: String,
    val rowCount: Int,
)

class StaticDatabaseBuildSession private constructor(
    private val connection: Connection,
) : AutoCloseable {
    private var committed = false

    private val insertRegion = connection.prepareStatement(
        """
        INSERT INTO regions(region_id, name_en, position_x, position_y, position_z, wormhole_class_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    )
    private val insertConstellation = connection.prepareStatement(
        """
        INSERT INTO constellations(
            constellation_id, region_id, name_en, position_x, position_y, position_z, wormhole_class_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    )
    private val insertSystem = connection.prepareStatement(
        """
        INSERT INTO systems(
            system_id, constellation_id, region_id, name_en, security_status, security_class,
            position_x, position_y, position_z, position_2d_x, position_2d_y, radius,
            faction_id, wormhole_class_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    )
    private val insertStargate = connection.prepareStatement(
        """
        INSERT INTO stargates(
            gate_id, from_system_id, to_system_id, destination_gate_id, type_id,
            position_x, position_y, position_z
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    )
    private val insertMetadata = connection.prepareStatement(
        "INSERT INTO metadata(key, value) VALUES (?, ?)",
    )
    private val insertSourceFile = connection.prepareStatement(
        "INSERT INTO source_files(file_name, sha256, row_count) VALUES (?, ?, ?)",
    )

    fun insert(region: Region) {
        insertRegion.bindInt(1, region.id)
        insertRegion.setString(2, region.name)
        insertRegion.setDouble(3, region.position.x)
        insertRegion.setDouble(4, region.position.y)
        insertRegion.setDouble(5, region.position.z)
        insertRegion.bindNullableInt(6, region.wormholeClassId)
        insertRegion.executeUpdate()
    }

    fun insert(constellation: Constellation) {
        insertConstellation.bindInt(1, constellation.id)
        insertConstellation.bindInt(2, constellation.regionId)
        insertConstellation.setString(3, constellation.name)
        insertConstellation.setDouble(4, constellation.position.x)
        insertConstellation.setDouble(5, constellation.position.y)
        insertConstellation.setDouble(6, constellation.position.z)
        insertConstellation.bindNullableInt(7, constellation.wormholeClassId)
        insertConstellation.executeUpdate()
    }

    fun insert(system: SolarSystem) {
        insertSystem.bindInt(1, system.id)
        insertSystem.bindInt(2, system.constellationId)
        insertSystem.bindInt(3, system.regionId)
        insertSystem.setString(4, system.name)
        insertSystem.setDouble(5, system.securityStatus)
        insertSystem.setString(6, system.securityClass)
        insertSystem.setDouble(7, system.position.x)
        insertSystem.setDouble(8, system.position.y)
        insertSystem.setDouble(9, system.position.z)
        insertSystem.bindNullableDouble(10, system.schematicPosition?.x)
        insertSystem.bindNullableDouble(11, system.schematicPosition?.y)
        insertSystem.setDouble(12, system.radius)
        insertSystem.bindNullableInt(13, system.factionId)
        insertSystem.bindNullableInt(14, system.wormholeClassId)
        insertSystem.executeUpdate()
    }

    fun insert(stargate: Stargate) {
        insertStargate.bindInt(1, stargate.id)
        insertStargate.bindInt(2, stargate.fromSystemId)
        insertStargate.bindInt(3, stargate.toSystemId)
        insertStargate.bindInt(4, stargate.destinationGateId)
        insertStargate.bindInt(5, stargate.typeId)
        insertStargate.setDouble(6, stargate.position.x)
        insertStargate.setDouble(7, stargate.position.y)
        insertStargate.setDouble(8, stargate.position.z)
        insertStargate.executeUpdate()
    }

    fun insert(audit: SourceFileAudit) {
        insertSourceFile.setString(1, audit.fileName)
        insertSourceFile.setString(2, audit.sha256)
        insertSourceFile.setInt(3, audit.rowCount)
        insertSourceFile.executeUpdate()
    }

    fun putMetadata(key: String, value: String) {
        insertMetadata.setString(1, key)
        insertMetadata.setString(2, value)
        insertMetadata.executeUpdate()
    }

    fun validationReport(): DatabaseValidationReport = StaticDatabaseValidator.validate(connection)

    fun commit() {
        connection.commit()
        committed = true
    }

    override fun close() {
        listOf(
            insertRegion,
            insertConstellation,
            insertSystem,
            insertStargate,
            insertMetadata,
            insertSourceFile,
        ).forEach(PreparedStatement::close)
        if (!committed) connection.rollback()
        connection.close()
    }

    companion object {
        fun create(databasePath: Path): StaticDatabaseBuildSession {
            val connection = SqliteConnectionFactory.open(databasePath)
            connection.autoCommit = false
            return try {
                StaticDatabaseSchema.create(connection)
                StaticDatabaseBuildSession(connection)
            } catch (error: Throwable) {
                connection.rollback()
                connection.close()
                throw error
            }
        }
    }
}

private fun PreparedStatement.bindInt(index: Int, value: Int) = setInt(index, value)

private fun PreparedStatement.bindNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
}

private fun PreparedStatement.bindNullableDouble(index: Int, value: Double?) {
    if (value == null) setNull(index, java.sql.Types.REAL) else setDouble(index, value)
}
