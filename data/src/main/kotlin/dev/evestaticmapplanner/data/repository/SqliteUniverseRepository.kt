package dev.evestaticmapplanner.data.repository

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import java.nio.file.Path
import java.sql.ResultSet

class SqliteUniverseRepository(
    private val databasePath: Path,
) : UniverseRepository {
    private val stargates = SqliteStargateRepository(databasePath)

    override fun getRegion(id: Int): Region? = queryRegion("region_id = ?", id)

    override fun getConstellation(id: Int): Constellation? = queryConstellation("constellation_id = ?", id)

    override fun getSystem(id: Int): SolarSystem? = querySystem("system_id = ?", id)

    override fun findSystemByName(name: String): SolarSystem? =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            connection.prepareStatement(
                """
                $SYSTEM_SELECT
                WHERE s.name_en = ? COLLATE NOCASE
                ORDER BY s.system_id
                LIMIT 2
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@use null
                    val system = result.toSolarSystem()
                    check(!result.next()) { "Solar system name is not unique: $name" }
                    system
                }
            }
        }

    override fun getSystemDetails(id: Int): SolarSystemDetails? {
        val system = getSystem(id) ?: return null
        val region = getRegion(system.regionId)
            ?: error("System ${system.id} references missing region ${system.regionId}")
        val constellation = getConstellation(system.constellationId)
            ?: error("System ${system.id} references missing constellation ${system.constellationId}")
        return SolarSystemDetails(system, region, constellation, stargates.getByOriginSystem(id))
    }

    private fun queryRegion(predicate: String, id: Int): Region? =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            connection.prepareStatement("SELECT * FROM regions WHERE $predicate").use { statement ->
                statement.setInt(1, id)
                statement.executeQuery().use { result -> if (result.next()) result.toRegion() else null }
            }
        }

    private fun queryConstellation(predicate: String, id: Int): Constellation? =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            connection.prepareStatement("SELECT * FROM constellations WHERE $predicate").use { statement ->
                statement.setInt(1, id)
                statement.executeQuery().use { result -> if (result.next()) result.toConstellation() else null }
            }
        }

    private fun querySystem(predicate: String, id: Int): SolarSystem? =
        SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            connection.prepareStatement("$SYSTEM_SELECT WHERE s.$predicate").use { statement ->
                statement.setInt(1, id)
                statement.executeQuery().use { result -> if (result.next()) result.toSolarSystem() else null }
            }
        }
}

private fun ResultSet.toRegion(): Region = Region(
    id = getInt("region_id"),
    name = getString("name_en"),
    position = position(),
    wormholeClassId = nullableInt("wormhole_class_id"),
)

private fun ResultSet.toConstellation(): Constellation = Constellation(
    id = getInt("constellation_id"),
    regionId = getInt("region_id"),
    name = getString("name_en"),
    position = position(),
    wormholeClassId = nullableInt("wormhole_class_id"),
)

internal fun ResultSet.toSolarSystem(): SolarSystem = SolarSystem(
    id = getInt("system_id"),
    constellationId = getInt("constellation_id"),
    regionId = getInt("region_id"),
    name = getString("name_en"),
    securityStatus = getDouble("security_status"),
    securityClass = getString("security_class"),
    position = position(),
    schematicPosition = nullableDouble("position_2d_x")?.let { x ->
        SchematicPosition(x, checkNotNull(nullableDouble("position_2d_y")))
    },
    radius = getDouble("radius"),
    factionId = nullableInt("faction_id"),
    wormholeClassId = nullableInt("wormhole_class_id"),
    effectiveWormholeClassId = nullableInt("effective_wormhole_class_id"),
)

internal const val SYSTEM_SELECT = """
    SELECT s.*,
           COALESCE(s.wormhole_class_id, c.wormhole_class_id, r.wormhole_class_id)
               AS effective_wormhole_class_id
    FROM systems s
    JOIN constellations c ON c.constellation_id = s.constellation_id
    JOIN regions r ON r.region_id = s.region_id
"""

private fun ResultSet.position(): UniversePosition = UniversePosition(
    x = getDouble("position_x"),
    y = getDouble("position_y"),
    z = getDouble("position_z"),
)

private fun ResultSet.nullableInt(column: String): Int? =
    getObject(column)?.let { getInt(column) }

private fun ResultSet.nullableDouble(column: String): Double? =
    getObject(column)?.let { getDouble(column) }
