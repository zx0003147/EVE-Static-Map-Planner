package dev.evestaticmapplanner.webpack

import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.Stargate
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.CachingStaticMapRepository
import dev.evestaticmapplanner.data.db.StaticDatabaseBuildSession
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WebPackSqliteIntegrationTest {
    @Test
    fun `export consumes Desktop SQLite repositories without changing either database`() {
        val root = createTempDirectory("web-pack-sqlite")
        val staticDatabase = root.resolve("static.db")
        val userDatabase = root.resolve("user.db")
        createStaticDatabase(staticDatabase)
        val ids = ArrayDeque(listOf("enabled", "disabled"))
        val ansiblex = SqliteAnsiblexRepository(
            databasePath = userDatabase,
            clock = Clock.fixed(Instant.parse("2026-09-08T01:02:03Z"), ZoneOffset.UTC),
            idGenerator = ids::removeFirst,
        )
        ansiblex.addManual(AnsiblexDraft(1, 3, displayName = "Exported"))
        ansiblex.addManual(AnsiblexDraft(1, 2, displayName = "Not exported"))
        ansiblex.setEnabled("disabled", false)
        val staticBefore = Files.readAllBytes(staticDatabase)
        val userBefore = Files.readAllBytes(userDatabase)

        val report = WebPackExporter(
            staticMapRepository = CachingStaticMapRepository(SqliteStaticMapRepository(staticDatabase)),
            ansiblexRepository = ansiblex,
            clock = Clock.fixed(Instant.parse("2026-09-08T01:02:03Z"), ZoneOffset.UTC),
        ).export(
            WebPackExportRequest(root.resolve("EVE-Web-Pack"), "1.7.0", 3_466_501L),
        )

        val document = WebPackCodec.decodePack(Files.readAllBytes(report.packPath))
        assertEquals(WebPackCounts(3, 2, 1, 1, 1), document.counts)
        assertEquals(listOf("enabled"), document.payload.ansiblexLinks.map(WebPackAnsiblexLink::id))
        assertContentEquals(staticBefore, Files.readAllBytes(staticDatabase))
        assertContentEquals(userBefore, Files.readAllBytes(userDatabase))
    }

    private fun createStaticDatabase(path: java.nio.file.Path) {
        StaticDatabaseBuildSession.create(path).use { database ->
            database.insert(Region(100, "Region", UniversePosition(0.0, 0.0, 0.0), null))
            database.insert(Constellation(10, 100, "Constellation", UniversePosition(0.0, 0.0, 0.0), null))
            (1..3).forEach { id ->
                database.insert(
                    SolarSystem(
                        id = id,
                        constellationId = 10,
                        regionId = 100,
                        name = "System $id",
                        securityStatus = 0.1,
                        securityClass = null,
                        position = UniversePosition(id * 1.0e15, id * 2.0e15, id * 3.0e15),
                        schematicPosition = SchematicPosition(id * 10.0, id * 20.0),
                        radius = 1.0,
                        factionId = null,
                        wormholeClassId = null,
                    ),
                )
            }
            database.insert(Stargate(1_001, 1, 2, 2_001, 1, UniversePosition(0.0, 0.0, 0.0)))
            database.insert(Stargate(2_001, 2, 1, 1_001, 1, UniversePosition(0.0, 0.0, 0.0)))
            database.insert(Stargate(2_002, 2, 3, 3_002, 1, UniversePosition(0.0, 0.0, 0.0)))
            database.insert(Stargate(3_002, 3, 2, 2_002, 1, UniversePosition(0.0, 0.0, 0.0)))
            database.commit()
        }
    }
}
