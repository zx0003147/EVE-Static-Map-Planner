package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadata
import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseValidator
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class ActivationFaultPoint {
    AFTER_BACKUP_CREATED,
    AFTER_SWAP_INTENT,
    AFTER_ACTIVE_RETIRED,
    AFTER_NEW_INSTALLED_BEFORE_VALIDATION,
    AFTER_ACTIVATION_VALID,
    AFTER_ROLLBACK_STARTED,
    DURING_ROLLBACK,
}

fun interface ActivationFaultInjector {
    fun trigger(point: ActivationFaultPoint)

    companion object {
        val NONE = ActivationFaultInjector { }
    }
}

class SimulatedActivationCrash(val point: ActivationFaultPoint) : IOException("Simulated crash at $point")

sealed interface ActivationOutcome {
    data object NoPendingUpdate : ActivationOutcome
    data class Activated(val build: Long, val firstInstall: Boolean, val usedJournaledFallback: Boolean) : ActivationOutcome
    data class RolledBack(val activeBuild: Long, val failedBuild: Long, val reason: String) : ActivationOutcome
    data class Failed(val message: String, val currentDatabaseRemainsActive: Boolean) : ActivationOutcome
    data class Fatal(val message: String) : ActivationOutcome
}

class PendingUpdateActivator(
    private val paths: ManagedStaticDataPaths,
    private val pendingStore: PendingUpdateStore = PendingUpdateStore(paths),
    private val journalStore: ActivationJournalStore = ActivationJournalStore(paths),
    private val clock: Clock = Clock.systemUTC(),
    private val transactionIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val faultInjector: ActivationFaultInjector = ActivationFaultInjector.NONE,
    private val forceJournaledMoveFallback: Boolean = false,
    private val auditStore: UpdaterAuditStore = UpdaterAuditStore(paths),
) {
    fun recoverAndApplyPending(): ActivationOutcome {
        paths.initialize()
        val recovery = try {
            recoverIncompleteTransaction()
        } catch (error: Throwable) {
            return ActivationOutcome.Fatal("Unable to read activation journal: ${error.message}")
        }
        if (recovery != null) return recovery
        return activatePending()
    }

    fun activatePending(): ActivationOutcome {
        paths.initialize()
        val manifest = try {
            pendingStore.read() ?: return ActivationOutcome.NoPendingUpdate
        } catch (error: Throwable) {
            return ActivationOutcome.Failed("Pending update manifest is invalid: ${error.message}", Files.isRegularFile(paths.activeDatabase))
        }
        val candidate = try {
            validateCandidate(manifest)
        } catch (error: Throwable) {
            return ActivationOutcome.Failed("Pending candidate validation failed: ${error.message}", Files.isRegularFile(paths.activeDatabase))
        }

        val activeExists = Files.isRegularFile(paths.activeDatabase)
        val old = if (activeExists) {
            try {
                validateDatabase(paths.activeDatabase)
            } catch (error: Throwable) {
                return ActivationOutcome.Fatal("Active static database is invalid; refusing replacement: ${error.message}")
            }
        } else null
        if (old != null && manifest.currentBuildAtPreparation != null && old.metadata.sdeBuild != manifest.currentBuildAtPreparation) {
            return ActivationOutcome.Failed(
                "Active build changed after preparation: expected ${manifest.currentBuildAtPreparation}, found ${old.metadata.sdeBuild}",
                true,
            )
        }

        val transactionId = transactionIdGenerator()
        var sequence = 0
        var phase = ActivationPhase.PREPARED
        var backup: Path? = null
        var fallbackUsed = false
        fun record(next: ActivationPhase) {
            phase = next
            journalStore.append(
                ActivationJournalEntry(
                    transactionId = transactionId,
                    sequence = sequence++,
                    phase = next,
                    oldBuild = old?.metadata?.sdeBuild,
                    oldSha256 = old?.sha256,
                    newBuild = manifest.targetBuild,
                    newSha256 = manifest.generatedDatabaseSha256,
                    stagingId = manifest.stagingId,
                    backupFileName = backup?.fileName?.toString(),
                    recordedAt = Instant.now(clock).toString(),
                ),
            )
        }

        val installing = paths.dataDirectory.resolve("static.db.installing-$transactionId")
        val previous = paths.dataDirectory.resolve("static.db.previous-$transactionId")
        record(ActivationPhase.PREPARED)
        try {
            if (old != null) {
                backup = paths.backupPath(old.metadata.sdeBuild, old.sha256)
                publishValidatedCopy(paths.activeDatabase, backup, old)
                record(ActivationPhase.BACKUP_READY)
                faultInjector.trigger(ActivationFaultPoint.AFTER_BACKUP_CREATED)
            }

            publishValidatedCopy(candidate.path, installing, candidate)
            record(ActivationPhase.SWAP_INTENT)
            faultInjector.trigger(ActivationFaultPoint.AFTER_SWAP_INTENT)

            if (old != null) {
                fallbackUsed = moveForActivation(paths.activeDatabase, previous) || fallbackUsed
                record(ActivationPhase.ACTIVE_RETIRED)
                faultInjector.trigger(ActivationFaultPoint.AFTER_ACTIVE_RETIRED)
            }
            fallbackUsed = moveForActivation(installing, paths.activeDatabase) || fallbackUsed
            record(ActivationPhase.NEW_INSTALLED)
            faultInjector.trigger(ActivationFaultPoint.AFTER_NEW_INSTALLED_BEFORE_VALIDATION)
            validateInstalled(paths.activeDatabase, manifest)
            record(ActivationPhase.ACTIVATION_VALID)
            faultInjector.trigger(ActivationFaultPoint.AFTER_ACTIVATION_VALID)
            finalizeSuccess(transactionId, manifest, previous, backup, old?.metadata?.sdeBuild)
            return ActivationOutcome.Activated(manifest.targetBuild, old == null, fallbackUsed)
        } catch (error: Throwable) {
            if (error is SimulatedActivationCrash) throw error
            if (phase.ordinal < ActivationPhase.SWAP_INTENT.ordinal) {
                cleanupFile(installing)
                journalStore.delete(transactionId)
                return ActivationOutcome.Failed("Update activation did not start: ${error.message}", old != null)
            }
            return if (old != null && backup != null) {
                rollback(transactionId, sequence, old, manifest, backup, previous, error)
            } else {
                cleanupFile(paths.activeDatabase)
                cleanupFile(installing)
                journalStore.delete(transactionId)
                ActivationOutcome.Failed("First install failed: ${error.message}", false)
            }
        }
    }

    private fun recoverIncompleteTransaction(): ActivationOutcome? {
        val transactions = journalStore.incompleteTransactions()
        if (transactions.size > 1) {
            return ActivationOutcome.Fatal("Multiple incomplete activation transactions require manual recovery")
        }
        val entry = transactions.singleOrNull() ?: return null
        val manifest = runCatching { pendingStore.read() }.getOrNull()
            ?: return ActivationOutcome.Fatal("Activation journal exists but pending manifest is missing or invalid")
        if (manifest.targetBuild != entry.newBuild || manifest.generatedDatabaseSha256 != entry.newSha256) {
            return ActivationOutcome.Fatal("Activation journal and pending manifest identify different candidates")
        }

        val active = validatedOrNull(paths.activeDatabase)
        if (entry.phase == ActivationPhase.ROLLBACK_INTENT) {
            val backup = entry.backupFileName?.let(paths.backupsDirectory::resolve)
                ?: return ActivationOutcome.Fatal("Interrupted rollback has no recorded backup")
            val expectedOld = validatedOrNull(backup)
            if (expectedOld == null || expectedOld.metadata.sdeBuild != entry.oldBuild || expectedOld.sha256 != entry.oldSha256) {
                return ActivationOutcome.Fatal("Interrupted rollback backup does not match the journal")
            }
            return rollback(
                transactionId = entry.transactionId,
                startingSequence = entry.sequence + 1,
                old = expectedOld,
                manifest = manifest,
                backup = backup,
                previous = paths.dataDirectory.resolve("static.db.previous-${entry.transactionId}"),
                activationError = IOException("Recovered interrupted rollback"),
            )
        }
        if (active?.metadata?.sdeBuild == entry.newBuild && active.sha256 == entry.newSha256) {
            journalStore.append(entry.next(ActivationPhase.ACTIVATION_VALID, clock))
            finalizeSuccess(
                entry.transactionId,
                manifest,
                paths.dataDirectory.resolve("static.db.previous-${entry.transactionId}"),
                entry.backupFileName?.let(paths.backupsDirectory::resolve),
                entry.oldBuild,
            )
            return ActivationOutcome.Activated(entry.newBuild, entry.oldBuild == null, false)
        }

        if (entry.oldBuild == null) {
            cleanupTransactionFiles(entry.transactionId)
            journalStore.delete(entry.transactionId)
            return null
        }

        val activeIsOld = active?.metadata?.sdeBuild == entry.oldBuild && active.sha256 == entry.oldSha256
        if (activeIsOld && entry.phase.ordinal <= ActivationPhase.SWAP_INTENT.ordinal) {
            cleanupTransactionFiles(entry.transactionId)
            journalStore.delete(entry.transactionId)
            return null
        }

        val backup = entry.backupFileName?.let(paths.backupsDirectory::resolve)
            ?: return ActivationOutcome.Fatal("Incomplete activation has no recorded backup")
        val expectedOld = validatedOrNull(backup)
        if (expectedOld == null || expectedOld.metadata.sdeBuild != entry.oldBuild || expectedOld.sha256 != entry.oldSha256) {
            return ActivationOutcome.Fatal("Incomplete activation backup does not match the journal")
        }
        return rollback(
            transactionId = entry.transactionId,
            startingSequence = entry.sequence + 1,
            old = expectedOld,
            manifest = manifest,
            backup = backup,
            previous = paths.dataDirectory.resolve("static.db.previous-${entry.transactionId}"),
            activationError = IOException("Recovered interrupted activation at ${entry.phase}"),
        )
    }

    private fun rollback(
        transactionId: String,
        startingSequence: Int,
        old: ValidatedDatabase,
        manifest: PendingUpdateManifest,
        backup: Path,
        previous: Path,
        activationError: Throwable,
    ): ActivationOutcome {
        var sequence = startingSequence
        fun record(phase: ActivationPhase) = journalStore.append(
            ActivationJournalEntry(
                transactionId = transactionId,
                sequence = sequence++,
                phase = phase,
                oldBuild = old.metadata.sdeBuild,
                oldSha256 = old.sha256,
                newBuild = manifest.targetBuild,
                newSha256 = manifest.generatedDatabaseSha256,
                stagingId = manifest.stagingId,
                backupFileName = backup.fileName.toString(),
                recordedAt = Instant.now(clock).toString(),
            ),
        )
        return try {
            record(ActivationPhase.ROLLBACK_INTENT)
            faultInjector.trigger(ActivationFaultPoint.AFTER_ROLLBACK_STARTED)
            quarantineUnexpectedActive(transactionId, old)
            faultInjector.trigger(ActivationFaultPoint.DURING_ROLLBACK)
            val recovering = paths.dataDirectory.resolve("static.db.recovering-$transactionId")
            publishValidatedCopy(backup, recovering, old)
            if (Files.exists(paths.activeDatabase)) cleanupFile(paths.activeDatabase)
            moveForActivation(recovering, paths.activeDatabase)
            val restored = validateDatabase(paths.activeDatabase)
            check(restored.metadata.sdeBuild == old.metadata.sdeBuild && restored.sha256 == old.sha256) {
                "Restored database does not match the previous active database"
            }
            record(ActivationPhase.ROLLBACK_VALID)
            cleanupFile(previous)
            discardPending(manifest)
            journalStore.delete(transactionId)
            auditStore.append(
                event = "ROLLED_BACK",
                oldBuild = old.metadata.sdeBuild,
                newBuild = manifest.targetBuild,
                sourceUrl = manifest.sourceUrl,
                archiveSha256 = manifest.archiveSha256,
                message = activationError.message,
            )
            ActivationOutcome.RolledBack(old.metadata.sdeBuild, manifest.targetBuild, activationError.message ?: "activation failed")
        } catch (rollbackError: Throwable) {
            if (rollbackError is SimulatedActivationCrash) throw rollbackError
            ActivationOutcome.Fatal(
                "Activation failed (${activationError.message}) and rollback failed (${rollbackError.message})",
            )
        }
    }

    private fun validateCandidate(manifest: PendingUpdateManifest): ValidatedDatabase {
        val path = pendingStore.candidatePath(manifest)
        check(Files.isRegularFile(path)) { "Candidate database is missing: $path" }
        requireNoLinks(paths.stagingRoot(manifest.stagingId), path)
        val validated = validateDatabase(path)
        check(validated.sha256 == manifest.generatedDatabaseSha256) { "Candidate database SHA-256 mismatch" }
        check(Files.size(path) == manifest.generatedDatabaseSize) { "Candidate database size mismatch" }
        check(validated.metadata.sdeBuild == manifest.targetBuild) { "Candidate build metadata mismatch" }
        check(validated.metadata.schemaVersion == manifest.expectedSchemaVersion) { "Candidate schema metadata mismatch" }
        check(CandidateCounts.from(validated.report) == manifest.counts) { "Candidate database counts mismatch" }
        return validated
    }

    private fun validateInstalled(path: Path, manifest: PendingUpdateManifest) {
        val installed = validateDatabase(path)
        check(installed.sha256 == manifest.generatedDatabaseSha256)
        check(installed.metadata.sdeBuild == manifest.targetBuild)
        check(installed.metadata.schemaVersion == manifest.expectedSchemaVersion)
        check(CandidateCounts.from(installed.report) == manifest.counts)
    }

    private fun validateDatabase(path: Path): ValidatedDatabase = ValidatedDatabase(
        path = path,
        metadata = StaticDatabaseMetadataReader.read(path),
        report = StaticDatabaseValidator.validate(path),
        sha256 = FileIntegrity.sha256(path),
    )

    private fun validatedOrNull(path: Path): ValidatedDatabase? =
        if (Files.isRegularFile(path)) runCatching { validateDatabase(path) }.getOrNull() else null

    private fun publishValidatedCopy(source: Path, target: Path, expected: ValidatedDatabase) {
        Files.createDirectories(target.parent)
        val part = target.resolveSibling("${target.fileName}.part")
        cleanupFile(part)
        Files.copy(source, part, StandardCopyOption.COPY_ATTRIBUTES)
        FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
        val copy = validateDatabase(part)
        check(copy.sha256 == expected.sha256 && copy.metadata.sdeBuild == expected.metadata.sdeBuild) {
            "Published database copy failed validation"
        }
        movePublished(part, target)
    }

    private fun moveForActivation(source: Path, target: Path): Boolean {
        check(!Files.exists(target)) { "Activation target already exists: $target" }
        if (!forceJournaledMoveFallback) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                return false
            } catch (_: AtomicMoveNotSupportedException) {
                // The activation journal makes the following non-atomic move recoverable.
            }
        }
        Files.move(source, target)
        return true
    }

    private fun quarantineUnexpectedActive(transactionId: String, expectedOld: ValidatedDatabase) {
        if (!Files.exists(paths.activeDatabase)) return
        val active = validatedOrNull(paths.activeDatabase)
        if (active?.sha256 == expectedOld.sha256 && active.metadata.sdeBuild == expectedOld.metadata.sdeBuild) {
            cleanupFile(paths.activeDatabase)
            return
        }
        Files.createDirectories(paths.quarantineDirectory)
        val quarantine = paths.quarantineDirectory.resolve("failed-$transactionId.db")
        cleanupFile(quarantine)
        moveForActivation(paths.activeDatabase, quarantine)
    }

    private fun finalizeSuccess(
        transactionId: String,
        manifest: PendingUpdateManifest,
        previous: Path,
        retainedBackup: Path?,
        oldBuild: Long?,
    ) {
        cleanupFile(previous)
        cleanupTransactionFiles(transactionId)
        pendingStore.delete()
        deleteTree(paths.stagingRoot(manifest.stagingId))
        trimBackups(retainedBackup)
        auditStore.recordInstalled(oldBuild, manifest)
        journalStore.delete(transactionId)
    }

    private fun requireNoLinks(root: Path, candidate: Path) {
        var current = root
        val relative = root.relativize(candidate)
        for (segment in relative) {
            current = current.resolve(segment)
            check(!Files.isSymbolicLink(current) && !isWindowsReparsePoint(current)) {
                "Managed candidate path contains a symbolic link or reparse point: $current"
            }
        }
    }

    private fun isWindowsReparsePoint(path: Path): Boolean = runCatching {
        Files.getAttribute(path, "dos:reparsePoint", java.nio.file.LinkOption.NOFOLLOW_LINKS) == true
    }.getOrDefault(false)

    private fun discardPending(manifest: PendingUpdateManifest) {
        pendingStore.delete()
        deleteTree(paths.stagingRoot(manifest.stagingId))
    }

    private fun trimBackups(retained: Path?) {
        if (!Files.isDirectory(paths.backupsDirectory)) return
        Files.list(paths.backupsDirectory).use { files ->
            files.filter(Files::isRegularFile).forEach { if (retained == null || it != retained) cleanupFile(it) }
        }
    }

    private fun cleanupTransactionFiles(transactionId: String) {
        cleanupFile(paths.dataDirectory.resolve("static.db.installing-$transactionId"))
        cleanupFile(paths.dataDirectory.resolve("static.db.recovering-$transactionId"))
        cleanupFile(paths.dataDirectory.resolve("static.db.previous-$transactionId"))
    }

    private fun cleanupFile(path: Path) {
        Files.deleteIfExists(path)
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        paths.requireManaged(root)
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

private data class ValidatedDatabase(
    val path: Path,
    val metadata: StaticDatabaseMetadata,
    val report: dev.evestaticmapplanner.data.db.DatabaseValidationReport,
    val sha256: String,
)

private fun ActivationJournalEntry.next(phase: ActivationPhase, clock: Clock) = copy(
    sequence = sequence + 1,
    phase = phase,
    recordedAt = Instant.now(clock).toString(),
)
