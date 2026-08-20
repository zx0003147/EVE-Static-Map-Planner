package dev.evestaticmapplanner.data.repository

import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import java.nio.file.Path

class SqliteStaticMapRepository(
    private val databasePath: Path,
) : StaticMapRepository {
    override fun load(): StaticMapData =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            val systems = connection.prepareStatement(
                "$SYSTEM_SELECT ORDER BY s.system_id",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.toSolarSystem()) }
                }
            }
            val connections = connection.prepareStatement(
                """
                SELECT
                    MIN(from_system_id, to_system_id) AS first_system_id,
                    MAX(from_system_id, to_system_id) AS second_system_id
                FROM stargates
                GROUP BY first_system_id, second_system_id
                ORDER BY first_system_id, second_system_id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                StargateConnection.between(
                                    result.getInt("first_system_id"),
                                    result.getInt("second_system_id"),
                                ),
                            )
                        }
                    }
                }
            }
            val regions = connection.prepareStatement(
                "SELECT * FROM regions ORDER BY region_id",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.toRegion()) }
                }
            }
            val constellations = connection.prepareStatement(
                "SELECT * FROM constellations ORDER BY constellation_id",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.toConstellation()) }
                }
            }
            StaticMapData(
                systems = systems,
                connections = connections,
                regions = regions,
                constellations = constellations,
            )
        }
}
