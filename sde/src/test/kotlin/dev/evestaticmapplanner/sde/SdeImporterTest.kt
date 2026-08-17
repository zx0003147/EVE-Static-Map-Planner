package dev.evestaticmapplanner.sde

import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.sde.io.SdeParseException
import dev.evestaticmapplanner.sde.io.SdeSourceLocator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SdeImporterTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)

    @Test
    fun `valid fixture imports and is queryable`() = withFixture { fixture, output ->
        val report = SdeImporter(fixedClock).import(
            SdeImportRequest(fixture, output, sdeBuild = "fixture-build-42"),
        )

        assertEquals(2, report.references.regionCount)
        assertEquals(2, report.references.constellationCount)
        assertEquals(3, report.references.systemCount)
        assertEquals(2, report.references.stargateCount)
        assertEquals("ok", report.database.integrityCheck)
        assertTrue(report.database.foreignKeyViolations.isEmpty())

        val repository = SqliteUniverseRepository(output)
        val details = assertNotNull(repository.getSystemDetails(30000001))
        assertEquals("Fixture Alpha", details.system.name)
        assertEquals("Fixture Region One", details.region.name)
        assertEquals("Fixture Constellation One", details.constellation.name)
        assertEquals(1, details.stargateCount)
        assertEquals(30000002, details.stargates.single().toSystemId)
        assertEquals(50000002, details.stargates.single().destinationGateId)
        assertEquals(30000001, repository.findSystemByName("fixture alpha")?.id)

        SqliteConnectionFactory.open(output, queryOnly = true).use { connection ->
            connection.prepareStatement("SELECT value FROM metadata WHERE key = ?").use { statement ->
                statement.setString(1, "sde_build")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("fixture-build-42", result.getString(1))
                }
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM source_files").use { result ->
                    assertTrue(result.next())
                    assertEquals(4, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `malformed json reports exact file and line`() = withFixture { fixture, _ ->
        val regions = fixture.resolve("mapRegions.jsonl")
        val lines = Files.readAllLines(regions).toMutableList()
        lines[1] = "{"
        Files.write(regions, lines)

        val error = assertFailsWith<SdeParseException> {
            SdeDataSet.load(SdeSourceLocator.locate(fixture))
        }
        assertEquals("mapRegions.jsonl", error.sourceFile.fileName.toString())
        assertEquals(2, error.lineNumber)
    }

    @Test
    fun `duplicate source ids are rejected`() = withFixture { fixture, _ ->
        val regions = fixture.resolve("mapRegions.jsonl")
        val first = Files.readAllLines(regions).first()
        Files.writeString(regions, Files.readString(regions) + first + System.lineSeparator())

        val error = assertFails { SdeDataSet.load(SdeSourceLocator.locate(fixture)) }
        assertContains(error.message.orEmpty(), "Duplicate region ID")
    }

    @Test
    fun `system region must match constellation region`() = withFixture { fixture, output ->
        val systems = fixture.resolve("mapSolarSystems.jsonl")
        Files.writeString(
            systems,
            Files.readString(systems).replace(
                "\"_key\":30000003,\"constellationID\":20000002,\"regionID\":10000002",
                "\"_key\":30000003,\"constellationID\":20000002,\"regionID\":10000001",
            ),
        )

        val error = assertFails {
            SdeImporter(fixedClock).import(SdeImportRequest(fixture, output, "fixture-build"))
        }
        assertContains(error.message.orEmpty(), "belongs to region")
        assertTrue(!Files.exists(output))
    }

    @Test
    fun `missing destination gate is rejected`() = withFixture { fixture, output ->
        val stargates = fixture.resolve("mapStargates.jsonl")
        val text = Files.readString(stargates)
            .replace("\"stargateID\":50000002", "\"stargateID\":50000003")
        Files.writeString(stargates, text)

        val error = assertFails {
            SdeImporter(fixedClock).import(SdeImportRequest(fixture, output, "fixture-build"))
        }
        assertContains(error.message.orEmpty(), "missing destination gate 50000003")
    }

    @Test
    fun `missing required source file is rejected`() = withFixture { fixture, output ->
        Files.delete(fixture.resolve("mapStargates.jsonl"))
        val error = assertFails {
            SdeImporter(fixedClock).import(SdeImportRequest(fixture, output, "fixture-build"))
        }
        assertContains(error.message.orEmpty(), "Expected exactly one mapStargates.jsonl")
    }

    @Test
    fun `existing output database is never overwritten`() = withFixture { fixture, output ->
        Files.writeString(output, "keep")
        val error = assertFails {
            SdeImporter(fixedClock).import(SdeImportRequest(fixture, output, "fixture-build"))
        }
        assertContains(error.message.orEmpty(), "already exists")
        assertEquals("keep", Files.readString(output))
    }

    private inline fun withFixture(block: (fixture: Path, output: Path) -> Unit) {
        val directory = createTempDirectory("sde-import-test-")
        try {
            val fixture = directory.resolve("input")
            copyFixture(fixture)
            block(fixture, directory.resolve("static.db"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun copyFixture(target: Path) {
        val source = Path.of(checkNotNull(javaClass.getResource("/sde/valid-minimal")).toURI())
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
