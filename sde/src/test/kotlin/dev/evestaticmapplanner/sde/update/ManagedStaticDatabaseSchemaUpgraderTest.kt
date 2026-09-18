package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.db.StaticDatabaseSchemaCompatibility
import dev.evestaticmapplanner.data.db.StaticDatabaseSchemaCompatibilityInspector
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManagedStaticDatabaseSchemaUpgraderTest {
    @Test
    fun `managed v1 with cached SDE rebuilds same build to v2 and retains v1 backup`() {
        val root = createTempDirectory("schema-upgrade-cached")
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        UpdateTestFixtures.installLegacyV1(paths, UpdateTestFixtures.OLD_BUILD)
        val oldHash = FileIntegrity.sha256(paths.activeDatabase)
        val archive = fixtureArchive(root.resolve("fixture.zip"))
        var downloadCalled = false

        val outcome = upgrader(
            paths,
            object : ManagedSdeArchiveSource {
                override fun cached(build: Long) = archive
                override fun downloadLatest(): Pair<SdeBuildInfo, DownloadedArchive> {
                    downloadCalled = true
                    error("download should not be called")
                }
            },
        ).upgradeIfRequired()

        val upgraded = assertIs<ManagedSchemaUpgradeOutcome.Upgraded>(outcome)
        assertTrue(upgraded.usedCachedArchive)
        assertEquals(UpdateTestFixtures.OLD_BUILD, upgraded.build)
        assertEquals(StaticDatabaseSchema.VERSION, StaticDatabaseMetadataReader.read(paths.activeDatabase).schemaVersion)
        assertEquals("测试星域一", assertNotNull(SqliteUniverseRepository(paths.activeDatabase).getRegion(10000001)).nameZh)
        assertTrue(!downloadCalled)
        val backup = Files.list(paths.backupsDirectory).use { it.toList().single() }
        assertEquals(oldHash, FileIntegrity.sha256(backup))
        assertIs<StaticDatabaseSchemaCompatibility.Older>(StaticDatabaseSchemaCompatibilityInspector.inspect(backup))
    }

    @Test
    fun `managed v1 without cache invokes download path and activates v2`() {
        val root = createTempDirectory("schema-upgrade-download")
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        UpdateTestFixtures.installLegacyV1(paths, UpdateTestFixtures.OLD_BUILD)
        val archive = fixtureArchive(root.resolve("fixture.zip"))
        var downloadCalls = 0

        val outcome = upgrader(
            paths,
            object : ManagedSdeArchiveSource {
                override fun cached(build: Long): DownloadedArchive? = null
                override fun downloadLatest(): Pair<SdeBuildInfo, DownloadedArchive> {
                    downloadCalls++
                    return SdeBuildInfo(UpdateTestFixtures.NEW_BUILD) to archive.copy(
                        sourceUri = URI.create(
                            "https://example.test/eve-online-static-data-${UpdateTestFixtures.NEW_BUILD}-jsonl.zip",
                        ),
                    )
                }
            },
        ).upgradeIfRequired()

        val upgraded = assertIs<ManagedSchemaUpgradeOutcome.Upgraded>(outcome)
        assertTrue(!upgraded.usedCachedArchive)
        assertEquals(UpdateTestFixtures.NEW_BUILD, upgraded.build)
        assertEquals(1, downloadCalls)
        assertEquals(StaticDatabaseSchema.VERSION, StaticDatabaseMetadataReader.read(paths.activeDatabase).schemaVersion)
    }

    @Test
    fun `candidate preparation failure preserves legacy active database`() {
        val root = createTempDirectory("schema-upgrade-build-failure")
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        UpdateTestFixtures.installLegacyV1(paths, UpdateTestFixtures.OLD_BUILD)
        val before = FileIntegrity.sha256(paths.activeDatabase)
        val invalid = root.resolve("invalid.zip")
        Files.writeString(invalid, "not a zip")
        val invalidArchive = DownloadedArchive(
            invalid,
            URI.create("https://example.test/invalid.zip"),
            Files.size(invalid),
            FileIntegrity.sha256(invalid),
            Instant.EPOCH,
        )

        val outcome = upgrader(
            paths,
            object : ManagedSdeArchiveSource {
                override fun cached(build: Long) = invalidArchive
                override fun downloadLatest(): Pair<SdeBuildInfo, DownloadedArchive> =
                    throw IOException("download unavailable")
            },
        ).upgradeIfRequired()

        val failed = assertIs<ManagedSchemaUpgradeOutcome.Failed>(outcome)
        assertTrue(failed.oldDatabasePreserved)
        assertEquals(before, FileIntegrity.sha256(paths.activeDatabase))
        assertIs<StaticDatabaseSchemaCompatibility.Older>(
            StaticDatabaseSchemaCompatibilityInspector.inspect(paths.activeDatabase),
        )
    }

    @Test
    fun `candidate reference validation failure preserves legacy active database`() {
        val root = createTempDirectory("schema-upgrade-validation-failure")
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        UpdateTestFixtures.installLegacyV1(paths, UpdateTestFixtures.OLD_BUILD)
        val before = FileIntegrity.sha256(paths.activeDatabase)
        val invalidEntries = SafeSdeArchiveExtractorTest().validEntries().map { (name, content) ->
            name to if (name == "mapStargates.jsonl") {
                content.replace("\"stargateID\":50000002", "\"stargateID\":59999999")
            } else {
                content
            }
        }
        val zip = SafeSdeArchiveExtractorTest().zip(root.resolve("invalid-references.zip"), invalidEntries)
        val invalidArchive = DownloadedArchive(
            zip,
            URI.create("https://example.test/invalid-references.zip"),
            Files.size(zip),
            FileIntegrity.sha256(zip),
            Instant.EPOCH,
        )

        val outcome = upgrader(
            paths,
            object : ManagedSdeArchiveSource {
                override fun cached(build: Long) = invalidArchive
                override fun downloadLatest(): Pair<SdeBuildInfo, DownloadedArchive> =
                    throw IOException("download unavailable")
            },
        ).upgradeIfRequired()

        assertIs<ManagedSchemaUpgradeOutcome.Failed>(outcome)
        assertEquals(before, FileIntegrity.sha256(paths.activeDatabase))
        assertIs<StaticDatabaseSchemaCompatibility.Older>(
            StaticDatabaseSchemaCompatibilityInspector.inspect(paths.activeDatabase),
        )
    }

    @Test
    fun `activation failure rolls back and preserves legacy active database`() {
        val root = createTempDirectory("schema-upgrade-activation-failure")
        val paths = ManagedStaticDataPaths(root.resolve("managed"))
        UpdateTestFixtures.installLegacyV1(paths, UpdateTestFixtures.OLD_BUILD)
        val before = FileIntegrity.sha256(paths.activeDatabase)
        val archive = fixtureArchive(root.resolve("fixture.zip"))
        val activator = PendingUpdateActivator(
            paths,
            faultInjector = ActivationFaultInjector { point ->
                if (point == ActivationFaultPoint.AFTER_NEW_INSTALLED_BEFORE_VALIDATION) {
                    throw IOException("injected activation failure")
                }
            },
        )

        val outcome = upgrader(
            paths,
            object : ManagedSdeArchiveSource {
                override fun cached(build: Long) = archive
                override fun downloadLatest() = error("download should not be called")
            },
            activator,
        ).upgradeIfRequired()

        assertIs<ManagedSchemaUpgradeOutcome.Failed>(outcome)
        assertEquals(before, FileIntegrity.sha256(paths.activeDatabase))
        assertIs<StaticDatabaseSchemaCompatibility.Older>(
            StaticDatabaseSchemaCompatibilityInspector.inspect(paths.activeDatabase),
        )
    }

    private fun upgrader(
        paths: ManagedStaticDataPaths,
        source: ManagedSdeArchiveSource,
        activator: PendingUpdateActivator = PendingUpdateActivator(paths),
    ) = ManagedStaticDatabaseSchemaUpgrader(
        paths = paths,
        archiveSource = source,
        preparer = SdeCandidatePreparer(paths, stagingIdGenerator = { "schema-upgrade" }),
        activator = activator,
    )

    private fun fixtureArchive(path: java.nio.file.Path): DownloadedArchive {
        val zip = SafeSdeArchiveExtractorTest().zip(path, SafeSdeArchiveExtractorTest().validEntries())
        return DownloadedArchive(
            path = zip,
            sourceUri = URI.create(
                "https://example.test/eve-online-static-data-${UpdateTestFixtures.OLD_BUILD}-jsonl.zip",
            ),
            size = Files.size(zip),
            sha256 = FileIntegrity.sha256(zip),
            downloadedAt = Instant.EPOCH,
        )
    }
}
