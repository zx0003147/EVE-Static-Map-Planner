package dev.evestaticmapplanner

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.Stargate
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.data.db.SourceFileAudit
import dev.evestaticmapplanner.data.db.StaticDatabaseBuildSession
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StartupCoordinatorTest {
    @Test
    fun `missing managed database starts Bootstrap without creating empty database`() {
        val local = createTempDirectory("bootstrap")
        val resolution = resolveManaged(local)

        val bootstrap = assertIs<StartupResolution.Bootstrap>(resolution)
        assertEquals(StaticDatabaseMode.MANAGED, bootstrap.configuration.database.mode)
        assertFalse(Files.exists(bootstrap.configuration.database.path))
    }

    @Test
    fun `missing explicit database is external error and never falls back to managed`() {
        val missing = createTempDirectory("external").resolve("missing.db")
        val resolution = StartupCoordinator().resolve(AppArguments(databasePath = missing))

        val error = assertIs<StartupResolution.ExternalPathError>(resolution)
        assertEquals(missing.toAbsolutePath().normalize(), error.path)
        assertFalse(Files.exists(missing))
    }

    @Test
    fun `valid explicit database starts ready in external mode`() {
        val database = createTempDirectory("external").resolve("static.db")
        buildFixtureDatabase(database, 42)

        val ready = assertIs<StartupResolution.Ready>(
            StartupCoordinator().resolve(AppArguments(databasePath = database)),
        )
        assertEquals(StaticDatabaseMode.EXTERNAL, ready.configuration.database.mode)
        assertEquals(42, dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader.read(database).sdeBuild)
    }

    @Test
    fun `managed database starts ready after validation`() {
        val local = createTempDirectory("managed-ready")
        val database = local.resolve("EVE Static Map Planner/data/static.db")
        buildFixtureDatabase(database, 77)

        val ready = assertIs<StartupResolution.Ready>(resolveManaged(local))
        assertEquals(database.toAbsolutePath().normalize(), ready.configuration.database.path)
    }

    private fun resolveManaged(local: Path): StartupResolution = StartupCoordinator().resolve(
        AppArguments(),
        systemProperties = emptyMap(),
        environment = mapOf("LOCALAPPDATA" to local.toString()),
        osName = "Windows 11",
        userHome = local,
    )
}

private fun buildFixtureDatabase(path: Path, build: Long) {
    Files.createDirectories(path.parent)
    StaticDatabaseBuildSession.create(path).use { database ->
        val origin = UniversePosition(0.0, 0.0, 0.0)
        database.insert(Region(1, "Region", origin, null))
        database.insert(Constellation(2, 1, "Constellation", origin, null))
        database.insert(SolarSystem(3, 2, 1, "Alpha", 0.0, null, origin, null, 1.0, null, null, null))
        database.insert(SolarSystem(4, 2, 1, "Beta", 0.0, null, origin, null, 1.0, null, null, null))
        database.insert(Stargate(10, 3, 4, 11, 1, origin))
        database.insert(Stargate(11, 4, 3, 10, 1, origin))
        database.insert(SourceFileAudit("fixture", "a".repeat(64), 2))
        mapOf(
            "schema_version" to StaticDatabaseSchema.VERSION.toString(),
            "sde_build" to build.toString(),
            "generated_at" to "2026-08-18T00:00:00Z",
            "source_format" to "jsonl",
            "generator_version" to "test",
        ).forEach(database::putMetadata)
        database.validationReport()
        database.commit()
    }
}
