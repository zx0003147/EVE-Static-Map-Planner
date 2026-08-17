package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import dev.evestaticmapplanner.data.db.StaticDatabaseBuildSession
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
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
