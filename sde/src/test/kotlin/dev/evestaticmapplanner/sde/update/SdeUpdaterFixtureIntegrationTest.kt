package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class SdeUpdaterFixtureIntegrationTest {
    @Test
    fun `fixture ZIP flows through download extract existing importer pending activation and repository`() {
        val root = createTempDirectory("updater-integration")
        val sourceZip = SafeSdeArchiveExtractorTest().zip(
            root.resolve("source.zip"),
            SafeSdeArchiveExtractorTest().validEntries(),
        )
        val bytes = Files.readAllBytes(sourceZip)
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        val uri = URI.create("https://example.test/eve-online-static-data-200-jsonl.zip")
        val downloader = SdeArchiveDownloader(
            transport = SdeHttpTransport {
                SdeHttpResponse(200, mapOf("Content-Length" to listOf(bytes.size.toString())), ByteArrayInputStream(bytes))
            },
            paths = paths,
            diskSpacePreflight = DiskSpacePreflight({ Long.MAX_VALUE }),
        )
        val archive = downloader.download(uri, UpdateTestFixtures.NEW_BUILD)
        val stages = mutableListOf<CandidatePreparationProgress>()
        val manifest = SdeCandidatePreparer(paths, stagingIdGenerator = { "fixture" }).prepare(
            archive,
            SdeBuildInfo(UpdateTestFixtures.NEW_BUILD),
            currentBuild = null,
            onProgress = stages::add,
        )

        assertEquals(UpdateTestFixtures.NEW_BUILD, manifest.targetBuild)
        assertIs<ActivationOutcome.Activated>(PendingUpdateActivator(paths).activatePending())
        assertNotNull(SqliteUniverseRepository(paths.activeDatabase).getSystem(30_000_001))
        assertEquals(UpdateTestFixtures.NEW_BUILD, dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader.read(paths.activeDatabase).sdeBuild)
    }

    @Test
    fun `import failure and unwritable staging leave old active database unchanged`() {
        val root = createTempDirectory("updater-failure")
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        UpdateTestFixtures.installOld(paths, UpdateTestFixtures.OLD_BUILD)
        val entries = SafeSdeArchiveExtractorTest().validEntries().map { (name, content) ->
            if (name == "mapSolarSystems.jsonl") name to "not-json\n" else name to content
        }
        val brokenZip = SafeSdeArchiveExtractorTest().zip(root.resolve("broken.zip"), entries)
        val brokenArchive = DownloadedArchive(
            brokenZip,
            URI.create("https://example.test/broken.zip"),
            Files.size(brokenZip),
            FileIntegrity.sha256(brokenZip),
            Instant.parse("2026-08-18T00:00:00Z"),
        )
        assertFailsWith<Exception> {
            SdeCandidatePreparer(paths, stagingIdGenerator = { "broken" }).prepare(
                brokenArchive,
                SdeBuildInfo(UpdateTestFixtures.NEW_BUILD),
                UpdateTestFixtures.OLD_BUILD,
            )
        }
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)

        val blocked = paths.stagingRoot("${UpdateTestFixtures.NEW_BUILD}-blocked")
        Files.writeString(blocked, "not a directory")
        val validZip = SafeSdeArchiveExtractorTest().zip(
            root.resolve("valid.zip"),
            SafeSdeArchiveExtractorTest().validEntries(),
        )
        val validArchive = brokenArchive.copy(
            path = validZip,
            sourceUri = URI.create("https://example.test/valid.zip"),
            size = Files.size(validZip),
            sha256 = FileIntegrity.sha256(validZip),
        )
        assertFailsWith<Exception> {
            SdeCandidatePreparer(paths, stagingIdGenerator = { "blocked" }).prepare(
                validArchive,
                SdeBuildInfo(UpdateTestFixtures.NEW_BUILD),
                UpdateTestFixtures.OLD_BUILD,
            )
        }
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
    }
}
