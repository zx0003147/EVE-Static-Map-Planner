package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.Stargate
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import dev.evestaticmapplanner.data.db.StaticDatabaseBuildSession
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import java.nio.file.Files
import java.sql.SQLException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaticDatabaseSchemaTest {
    @Test
    fun `system search supports exact prefix numeric ID and limit`() = withTempDatabase { database ->
        StaticDatabaseBuildSession.create(database).use { session ->
            session.insert(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null))
            session.insert(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null))
            listOf(
                100 to "Alpha",
                101 to "Alpine",
                102 to "Alpheratz",
                200 to "Beta",
            ).forEach { (id, name) ->
                session.insert(
                    SolarSystem(
                        id = id,
                        constellationId = 10,
                        regionId = 1,
                        name = name,
                        securityStatus = 0.0,
                        securityClass = null,
                        position = UniversePosition(id.toDouble(), 0.0, 0.0),
                        schematicPosition = null,
                        radius = 1.0,
                        factionId = null,
                        wormholeClassId = null,
                    ),
                )
            }
            session.commit()
        }
        val repository = SqliteSystemSearchRepository(database)

        assertEquals(listOf("Alpha"), repository.searchSystems("alpha").take(1).map { it.name })
        assertEquals(listOf("Alpha", "Alpheratz"), repository.searchSystems("Al", limit = 2).map { it.name })
        assertEquals(listOf(200), repository.searchSystems("200").map { it.id })
        assertEquals(emptyList(), repository.searchSystems("pha"))
    }

    @Test
    fun `composite system parent is enforced by SQLite`() = withTempDatabase { database ->
        SqliteConnectionFactory.open(database).use { connection ->
            StaticDatabaseSchema.create(connection)
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO regions VALUES (1, 'One', 0.0, 0.0, 0.0, NULL)")
                statement.execute("INSERT INTO regions VALUES (2, 'Two', 0.0, 0.0, 0.0, NULL)")
                statement.execute("INSERT INTO constellations VALUES (10, 1, 'C', 0.0, 0.0, 0.0, NULL)")
                assertFailsWith<SQLException> {
                    statement.execute(
                        """
                        INSERT INTO systems VALUES (
                            100, 10, 2, 'Mismatch', 0.0, NULL,
                            0.0, 0.0, 0.0, NULL, NULL, 1.0, NULL, NULL
                        )
                        """.trimIndent(),
                    )
                }
            }

            val hasUniqueCandidateKey = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_list('constellations')").use { result ->
                    var found = false
                    while (result.next()) {
                        if (result.getInt("unique") == 1) found = true
                    }
                    found
                }
            }
            assertTrue(hasUniqueCandidateKey)
        }
    }

    @Test
    fun `repository reads canonical system details`() = withTempDatabase { database ->
        StaticDatabaseBuildSession.create(database).use { session ->
            session.insert(Region(1, "Region", UniversePosition(1.0, 2.0, 3.0), null))
            session.insert(Constellation(10, 1, "Constellation", UniversePosition(4.0, 5.0, 6.0), null))
            session.insert(
                SolarSystem(
                    id = 100,
                    constellationId = 10,
                    regionId = 1,
                    name = "System",
                    securityStatus = 0.25,
                    securityClass = null,
                    position = UniversePosition(7.0, 8.0, 9.0),
                    schematicPosition = null,
                    radius = 10.0,
                    factionId = null,
                    wormholeClassId = null,
                ),
            )
            session.commit()
        }

        val details = assertNotNull(SqliteUniverseRepository(database).getSystemDetails(100))
        assertEquals("System", details.system.name)
        assertEquals("Region", details.region.name)
        assertEquals("Constellation", details.constellation.name)
        assertEquals(0, details.stargateCount)
    }

    @Test
    fun `static map repository loads systems and deduplicates reciprocal stargates`() = withTempDatabase { database ->
        StaticDatabaseBuildSession.create(database).use { session ->
            session.insert(Region(1, "Region", UniversePosition(0.0, 0.0, 0.0), null))
            session.insert(Constellation(10, 1, "Constellation", UniversePosition(0.0, 0.0, 0.0), null))
            listOf(100, 200).forEach { id ->
                session.insert(
                    SolarSystem(
                        id = id,
                        constellationId = 10,
                        regionId = 1,
                        name = "System $id",
                        securityStatus = 0.0,
                        securityClass = null,
                        position = UniversePosition(id.toDouble(), 0.0, id.toDouble()),
                        schematicPosition = null,
                        radius = 1.0,
                        factionId = null,
                        wormholeClassId = null,
                    ),
                )
            }
            session.insert(Stargate(1000, 100, 200, 2000, 1, UniversePosition(0.0, 0.0, 0.0)))
            session.insert(Stargate(2000, 200, 100, 1000, 1, UniversePosition(0.0, 0.0, 0.0)))
            session.commit()
        }

        val map = SqliteStaticMapRepository(database).load()

        assertEquals(listOf(100, 200), map.systems.map { it.id })
        assertEquals(1, map.connections.size)
        assertEquals(100, map.connections.single().firstSystemId)
        assertEquals(200, map.connections.single().secondSystemId)
    }

    @Test
    fun `query-only connection does not create a missing database`() = withTempDatabase { database ->
        val error = assertFailsWith<IllegalArgumentException> {
            SqliteConnectionFactory.open(database, queryOnly = true)
        }

        assertTrue(error.message.orEmpty().contains(database.toAbsolutePath().toString()))
        assertTrue(!Files.exists(database))
    }
}

private inline fun withTempDatabase(block: (java.nio.file.Path) -> Unit) {
    val directory = createTempDirectory("static-db-test-")
    try {
        block(directory.resolve("static.db"))
    } finally {
        directory.toFile().deleteRecursively()
        assertTrue(!Files.exists(directory))
    }
}
