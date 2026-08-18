package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PendingUpdateActivatorTest {
    @Test
    fun `first install validates activates and opens through repository`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("first-install"))
        UpdateTestFixtures.preparePending(paths, UpdateTestFixtures.NEW_BUILD)

        val outcome = PendingUpdateActivator(paths).activatePending()

        val activated = assertIs<ActivationOutcome.Activated>(outcome)
        assertTrue(activated.firstInstall)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.NEW_BUILD)
        assertNotNull(SqliteUniverseRepository(paths.activeDatabase).getSystem(30_000_001))
    }

    @Test
    fun `normal update retains exactly one validated previous backup`() {
        val paths = preparedUpdate()
        val outcome = PendingUpdateActivator(paths).activatePending()

        assertIs<ActivationOutcome.Activated>(outcome)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.NEW_BUILD)
        val backups = Files.list(paths.backupsDirectory).use { it.toList() }
        assertEquals(1, backups.size)
        UpdateTestFixtures.assertBuild(backups.single(), UpdateTestFixtures.OLD_BUILD)
        assertTrue(Files.isRegularFile(paths.auditDirectory.resolve("installed.json")))
    }

    @Test
    fun `journaled move fallback is reported and still activates`() {
        val paths = preparedUpdate()
        val outcome = PendingUpdateActivator(paths, forceJournaledMoveFallback = true).activatePending()
        assertTrue(assertIs<ActivationOutcome.Activated>(outcome).usedJournaledFallback)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.NEW_BUILD)
    }

    @Test
    fun `candidate hash mismatch leaves old database unchanged`() {
        val paths = preparedUpdate()
        val candidate = paths.stagedDatabase("stage-${UpdateTestFixtures.NEW_BUILD}")
        Files.write(candidate, byteArrayOf(1), java.nio.file.StandardOpenOption.APPEND)

        val outcome = PendingUpdateActivator(paths).activatePending()

        assertIs<ActivationOutcome.Failed>(outcome)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
    }

    @Test
    fun `backup publication failure leaves active database unchanged`() {
        val paths = preparedUpdate()
        val hash = FileIntegrity.sha256(paths.activeDatabase)
        Files.createDirectories(paths.backupPath(UpdateTestFixtures.OLD_BUILD, hash))

        val outcome = PendingUpdateActivator(paths).activatePending()

        assertIs<ActivationOutcome.Failed>(outcome)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
    }

    @Test
    fun `activation move failure rolls back from validated backup`() {
        val paths = preparedUpdate()
        val transactionId = "fixed-transaction"
        Files.writeString(paths.dataDirectory.resolve("static.db.previous-$transactionId"), "occupied")

        val outcome = PendingUpdateActivator(
            paths,
            transactionIdGenerator = { transactionId },
        ).activatePending()

        assertIs<ActivationOutcome.RolledBack>(outcome)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
    }

    @Test
    fun `corrupt activation journal enters fatal state without guessing from files`() {
        val paths = preparedUpdate()
        val journal = paths.activationRoot("corrupt")
        Files.createDirectories(journal)
        Files.writeString(journal.resolve("000-prepared.json"), "not-json")

        val outcome = PendingUpdateActivator(paths).recoverAndApplyPending()

        assertIs<ActivationOutcome.Fatal>(outcome)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
    }

    @Test
    fun `candidate symbolic link is rejected when platform permits link creation`() {
        val paths = preparedUpdate()
        val candidate = paths.stagedDatabase("stage-${UpdateTestFixtures.NEW_BUILD}")
        val outside = paths.root.resolve("outside.db")
        Files.copy(candidate, outside)
        Files.delete(candidate)
        if (runCatching { Files.createSymbolicLink(candidate, outside) }.isFailure) return

        val outcome = PendingUpdateActivator(paths).activatePending()

        assertIs<ActivationOutcome.Failed>(outcome)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
    }

    @Test
    fun `static schema authority remains metadata schema version`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("schema-authority"))
        UpdateTestFixtures.installOld(paths, UpdateTestFixtures.OLD_BUILD)
        SqliteConnectionFactory.open(paths.activeDatabase).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 99") }
        }
        assertEquals(UpdateTestFixtures.SCHEMA_VERSION, StaticDatabaseMetadataReader.read(paths.activeDatabase).schemaVersion)
    }

    @Test
    fun `activation failure rolls back old database`() {
        val paths = preparedUpdate()
        val injector = ActivationFaultInjector { point ->
            if (point == ActivationFaultPoint.AFTER_NEW_INSTALLED_BEFORE_VALIDATION) throw IOException("injected validation failure")
        }
        val outcome = PendingUpdateActivator(paths, faultInjector = injector).activatePending()

        assertIs<ActivationOutcome.RolledBack>(outcome)
        UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
    }

    @Test
    fun `rollback failure enters fatal state without creating empty database`() {
        val paths = preparedUpdate()
        val injector = ActivationFaultInjector { point ->
            when (point) {
                ActivationFaultPoint.AFTER_NEW_INSTALLED_BEFORE_VALIDATION -> throw IOException("activate failed")
                ActivationFaultPoint.DURING_ROLLBACK -> throw IOException("rollback failed")
                else -> Unit
            }
        }
        val outcome = PendingUpdateActivator(paths, faultInjector = injector).activatePending()

        assertIs<ActivationOutcome.Fatal>(outcome)
        assertTrue(Files.isRegularFile(paths.backupsDirectory.resolve(
            Files.list(paths.backupsDirectory).use { it.findFirst().orElseThrow().fileName },
        )))
    }

    @Test
    fun `crash recovery covers every activation checkpoint`() {
        val expectations = mapOf(
            ActivationFaultPoint.AFTER_BACKUP_CREATED to UpdateTestFixtures.NEW_BUILD,
            ActivationFaultPoint.AFTER_SWAP_INTENT to UpdateTestFixtures.NEW_BUILD,
            ActivationFaultPoint.AFTER_ACTIVE_RETIRED to UpdateTestFixtures.OLD_BUILD,
            ActivationFaultPoint.AFTER_NEW_INSTALLED_BEFORE_VALIDATION to UpdateTestFixtures.NEW_BUILD,
            ActivationFaultPoint.AFTER_ACTIVATION_VALID to UpdateTestFixtures.NEW_BUILD,
        )
        expectations.forEach { (point, expectedBuild) ->
            val paths = preparedUpdate("crash-${point.name.lowercase()}")
            val crashing = PendingUpdateActivator(
                paths,
                faultInjector = ActivationFaultInjector { current -> if (current == point) throw SimulatedActivationCrash(point) },
            )
            assertFailsWith<SimulatedActivationCrash> { crashing.activatePending() }

            val recovered = PendingUpdateActivator(paths).recoverAndApplyPending()
            if (expectedBuild == UpdateTestFixtures.NEW_BUILD) assertIs<ActivationOutcome.Activated>(recovered)
            else assertIs<ActivationOutcome.RolledBack>(recovered)
            UpdateTestFixtures.assertBuild(paths.activeDatabase, expectedBuild)
        }
    }

    @Test
    fun `crash recovery resumes rollback after rollback intent and midpoint`() {
        listOf(ActivationFaultPoint.AFTER_ROLLBACK_STARTED, ActivationFaultPoint.DURING_ROLLBACK).forEach { crashPoint ->
            val paths = preparedUpdate("rollback-${crashPoint.name.lowercase()}")
            val injector = ActivationFaultInjector { point ->
                when {
                    point == ActivationFaultPoint.AFTER_NEW_INSTALLED_BEFORE_VALIDATION -> throw IOException("activate failed")
                    point == crashPoint -> throw SimulatedActivationCrash(point)
                }
            }
            assertFailsWith<SimulatedActivationCrash> {
                PendingUpdateActivator(paths, faultInjector = injector).activatePending()
            }

            val recovered = PendingUpdateActivator(paths).recoverAndApplyPending()
            assertIs<ActivationOutcome.RolledBack>(recovered)
            UpdateTestFixtures.assertBuild(paths.activeDatabase, UpdateTestFixtures.OLD_BUILD)
        }
    }

    private fun preparedUpdate(prefix: String = "update"): ManagedStaticDataPaths {
        val paths = ManagedStaticDataPaths(createTempDirectory(prefix))
        UpdateTestFixtures.installOld(paths, UpdateTestFixtures.OLD_BUILD)
        UpdateTestFixtures.preparePending(paths, UpdateTestFixtures.NEW_BUILD, UpdateTestFixtures.OLD_BUILD)
        return paths
    }
}
