package dev.evestaticmapplanner.data.repository

import dev.evestaticmapplanner.core.model.Stargate
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.StargateRepository
import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import java.nio.file.Path
import java.sql.ResultSet

class SqliteStargateRepository(
    private val databasePath: Path,
) : StargateRepository {
    override fun getByOriginSystem(systemId: Int): List<Stargate> =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            connection.prepareStatement(
                """
                SELECT gate_id, from_system_id, to_system_id, destination_gate_id, type_id,
                       position_x, position_y, position_z
                FROM stargates
                WHERE from_system_id = ?
                ORDER BY gate_id
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, systemId)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.toStargate()) }
                }
            }
        }

    override fun countByOriginSystem(systemId: Int): Int =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM stargates WHERE from_system_id = ?",
            ).use { statement ->
                statement.setInt(1, systemId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }
}

internal fun ResultSet.toStargate(): Stargate = Stargate(
    id = getInt("gate_id"),
    fromSystemId = getInt("from_system_id"),
    toSystemId = getInt("to_system_id"),
    destinationGateId = getInt("destination_gate_id"),
    typeId = getInt("type_id"),
    position = UniversePosition(
        x = getDouble("position_x"),
        y = getDouble("position_y"),
        z = getDouble("position_z"),
    ),
)
