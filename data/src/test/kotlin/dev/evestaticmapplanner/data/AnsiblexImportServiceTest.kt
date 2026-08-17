package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportService
import dev.evestaticmapplanner.data.ansiblex.ImportDiagnosticSeverity
import dev.evestaticmapplanner.data.ansiblex.StaleImportPreviewException
import dev.evestaticmapplanner.data.db.UserDatabase
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnsiblexImportServiceTest {
    @Test
    fun `empty import cannot be used as an implicit clear operation`() {
        val preview = Fixture().service().previewText(
            "empty.csv",
            "from_system_id,to_system_id\n",
            AnsiblexImportMode.REPLACE,
        )

        assertFalse(preview.canApply)
        assertTrue(preview.diagnostics.any { it.code == "EMPTY_IMPORT" })
    }

    @Test
    fun `valid CSV resolves IDs and names and defaults to bidirectional enabled`() {
        val fixture = Fixture()
        val preview = fixture.service().previewText(
            "qa.csv",
            """
            from_system_id,from_system_name,to_system_id,to_system_name,connection_name,note
            1,Alpha,,Bravo,QA Link,Synthetic
            """.trimIndent(),
            AnsiblexImportMode.MERGE,
        )

        assertTrue(preview.canApply)
        assertEquals(1, preview.validRowCount)
        assertEquals(1, preview.additions.size)
        assertEquals(0, preview.invalidRowCount)
    }

    @Test
    fun `valid strict JSON parses while unknown fields and malformed JSON fail`() {
        val fixture = Fixture()
        val valid = fixture.service().previewText(
            "qa.json",
            """{"format_version":1,"connections":[{"from":{"system_name":"Alpha"},"to":{"system_id":2},"direction":"FORWARD"}]}""",
            AnsiblexImportMode.MERGE,
        )
        val unknown = fixture.service().previewText(
            "qa.json",
            """{"format_version":1,"connections":[],"surprise":true}""",
            AnsiblexImportMode.MERGE,
        )
        val malformed = fixture.service().previewText("qa.json", "{", AnsiblexImportMode.MERGE)

        assertTrue(valid.canApply)
        assertFalse(unknown.canApply)
        assertFalse(malformed.canApply)
        assertTrue(unknown.diagnostics.any { it.code == "BAD_JSON" })
    }

    @Test
    fun `bad headers unknown systems and self loops block apply`() {
        val fixture = Fixture()
        val badHeaders = fixture.service().previewText(
            "qa.csv",
            "from,to,extra\nAlpha,Bravo,value",
            AnsiblexImportMode.MERGE,
        )
        val invalidRows = fixture.service().previewText(
            "qa.csv",
            """
            from_system_name,to_system_name
            Missing,Bravo
            Alpha,Alpha
            """.trimIndent(),
            AnsiblexImportMode.MERGE,
        )

        assertFalse(badHeaders.canApply)
        assertTrue(badHeaders.diagnostics.any { it.code == "UNKNOWN_CSV_COLUMN" })
        assertFalse(invalidRows.canApply)
        assertTrue(invalidRows.diagnostics.any { it.code == "UNKNOWN_SYSTEM_NAME" })
        assertTrue(invalidRows.diagnostics.any { it.code == "SELF_LOOP" })
    }

    @Test
    fun `identical and reverse bidirectional duplicates warn but conflicting duplicates fail`() {
        val fixture = Fixture()
        val duplicate = fixture.service().previewText(
            "qa.csv",
            """
            from_system_id,to_system_id,connection_name,direction
            1,2,Same,BIDIRECTIONAL
            2,1,Same,BIDIRECTIONAL
            """.trimIndent(),
            AnsiblexImportMode.MERGE,
        )
        val conflict = fixture.service().previewText(
            "qa.csv",
            """
            from_system_id,to_system_id,direction
            1,2,FORWARD
            2,1,FORWARD
            """.trimIndent(),
            AnsiblexImportMode.MERGE,
        )

        assertTrue(duplicate.canApply)
        assertEquals(1, duplicate.duplicateCount)
        assertTrue(duplicate.diagnostics.any { it.severity == ImportDiagnosticSeverity.WARNING })
        assertFalse(conflict.canApply)
        assertTrue(conflict.diagnostics.any { it.code == "CONFLICTING_DUPLICATE" })
    }

    @Test
    fun `MERGE adds updates and preserves connections not in the file`() {
        val fixture = Fixture()
        val service = fixture.service()
        service.apply(
            service.previewText(
                "first.csv",
                "from_system_id,to_system_id,note\n1,2,old\n3,4,keep",
                AnsiblexImportMode.MERGE,
            ),
        )

        val preview = service.previewText(
            "second.csv",
            "from_system_id,to_system_id,note\n1,2,new\n2,3,added",
            AnsiblexImportMode.MERGE,
        )
        val result = service.apply(preview)

        assertEquals(1, preview.additions.size)
        assertEquals(1, preview.updates.size)
        assertEquals(0, preview.removals.size)
        assertEquals(1, result.addedCount)
        assertEquals(3, fixture.repository().getAll().size)
    }

    @Test
    fun `REPLACE removes only imported connections and never manual data`() {
        val fixture = Fixture()
        val repository = fixture.repository()
        repository.addManual(AnsiblexDraft(3, 4, displayName = "Manual"))
        val service = fixture.service()
        service.apply(
            service.previewText(
                "first.csv",
                "from_system_id,to_system_id\n1,2",
                AnsiblexImportMode.MERGE,
            ),
        )

        val preview = service.previewText(
            "replace.csv",
            "from_system_id,to_system_id\n2,3",
            AnsiblexImportMode.REPLACE,
        )
        service.apply(preview)

        assertEquals(1, preview.removals.size)
        val remaining = repository.getAll()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.source == AnsiblexSource.MANUAL && it.firstSystemId == 3 && it.secondSystemId == 4 })
        assertTrue(remaining.any { it.source == AnsiblexSource.IMPORT && it.firstSystemId == 2 && it.secondSystemId == 3 })
    }

    @Test
    fun `stale preview is rejected before writes`() {
        val fixture = Fixture()
        val service = fixture.service()
        val preview = service.previewText(
            "qa.csv",
            "from_system_id,to_system_id\n1,2",
            AnsiblexImportMode.MERGE,
        )
        fixture.repository().addManual(AnsiblexDraft(3, 4))

        assertFailsWith<StaleImportPreviewException> { service.apply(preview) }
        assertEquals(1, fixture.repository().getAll().size)
    }

    @Test
    fun `transaction failure rolls back connections and batch`() {
        val fixture = Fixture()
        val service = fixture.service(transactionHook = { error("forced rollback") })
        val preview = service.previewText(
            "qa.csv",
            "from_system_id,to_system_id\n1,2\n2,3",
            AnsiblexImportMode.MERGE,
        )

        assertFailsWith<IllegalStateException> { service.apply(preview) }
        assertEquals(emptyList(), fixture.repository().getAll())
        UserDatabase.open(fixture.userDb).use { connection ->
            connection.createStatement().executeQuery("SELECT COUNT(*) FROM ansiblex_import_batches").use { result ->
                result.next()
                assertEquals(0, result.getInt(1))
            }
        }
    }
}

private class Fixture {
    val userDb = createTempDirectory("ansiblex-import").resolve("user.db")
    private val systems = listOf(
        system(1, "Alpha"),
        system(2, "Bravo"),
        system(3, "Charlie"),
        system(4, "Delta"),
    )
    private val clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC)
    private val ids = AtomicInteger()
    private val universe = FakeUniverseRepository(systems)
    private val search = FakeSystemSearchRepository(systems)

    fun repository() = SqliteAnsiblexRepository(userDb, clock) { "manual-${ids.incrementAndGet()}" }

    fun service(transactionHook: (java.sql.Connection) -> Unit = {}) = AnsiblexImportService(
        userDatabasePath = userDb,
        universeRepository = universe,
        searchRepository = search,
        clock = clock,
        idGenerator = { "generated-${ids.incrementAndGet()}" },
        transactionHook = transactionHook,
    )
}

private class FakeUniverseRepository(systems: List<SolarSystem>) : UniverseRepository {
    private val systems = systems.associateBy(SolarSystem::id)
    override fun getRegion(id: Int): Region? = null
    override fun getConstellation(id: Int): Constellation? = null
    override fun getSystem(id: Int): SolarSystem? = systems[id]
    override fun findSystemByName(name: String): SolarSystem? = systems.values.singleOrNull { it.name.equals(name, true) }
    override fun getSystemDetails(id: Int): SolarSystemDetails? = null
}

private class FakeSystemSearchRepository(private val systems: List<SolarSystem>) : SystemSearchRepository {
    override fun searchSystems(query: String, limit: Int): List<SolarSystem> = systems
        .filter { it.name.startsWith(query, ignoreCase = true) }
        .sortedBy(SolarSystem::name)
        .take(limit)
}

private fun system(id: Int, name: String) = SolarSystem(
    id = id,
    constellationId = 10,
    regionId = 1,
    name = name,
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, id.toDouble()),
    schematicPosition = null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)
