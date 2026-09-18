package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.db.StaticDatabaseSchemaCompatibility
import dev.evestaticmapplanner.data.db.StaticDatabaseSchemaCompatibilityInspector
import java.nio.file.Files
import java.time.Clock
import java.time.Instant

interface ManagedSdeArchiveSource {
    fun cached(build: Long): DownloadedArchive?
    fun downloadLatest(): Pair<SdeBuildInfo, DownloadedArchive>
}

class CachedOrDownloadedSdeArchiveSource(
    private val paths: ManagedStaticDataPaths,
    private val client: SdeUpdateClient,
    private val downloader: SdeArchiveDownloader,
    private val clock: Clock = Clock.systemUTC(),
) : ManagedSdeArchiveSource {
    override fun cached(build: Long): DownloadedArchive? {
        val path = paths.downloadsDirectory.resolve("eve-online-static-data-$build-jsonl.zip")
        if (!Files.isRegularFile(path) || Files.size(path) <= 0L) return null
        return DownloadedArchive(
            path = path,
            sourceUri = client.fixedBuildUri(build),
            size = Files.size(path),
            sha256 = FileIntegrity.sha256(path),
            downloadedAt = Files.getLastModifiedTime(path).toInstant().takeIf { it != Instant.EPOCH }
                ?: Instant.now(clock),
        )
    }

    override fun downloadLatest(): Pair<SdeBuildInfo, DownloadedArchive> {
        val buildInfo = client.checkLatest().buildInfo
        return buildInfo to downloader.download(client.fixedBuildUri(buildInfo.buildNumber), buildInfo.buildNumber)
    }
}

sealed interface ManagedSchemaUpgradeOutcome {
    data class NotRequired(val compatibility: StaticDatabaseSchemaCompatibility) : ManagedSchemaUpgradeOutcome
    data class Upgraded(val oldSchema: Int, val newSchema: Int, val build: Long, val usedCachedArchive: Boolean) :
        ManagedSchemaUpgradeOutcome
    data class Failed(val message: String, val oldDatabasePreserved: Boolean) : ManagedSchemaUpgradeOutcome
}

class ManagedStaticDatabaseSchemaUpgrader(
    private val paths: ManagedStaticDataPaths,
    private val archiveSource: ManagedSdeArchiveSource,
    private val preparer: SdeCandidatePreparer = SdeCandidatePreparer(paths),
    private val activator: PendingUpdateActivator = PendingUpdateActivator(paths),
) {
    fun upgradeIfRequired(): ManagedSchemaUpgradeOutcome {
        val compatibility = StaticDatabaseSchemaCompatibilityInspector.inspect(paths.activeDatabase)
        if (compatibility !is StaticDatabaseSchemaCompatibility.Older) {
            return ManagedSchemaUpgradeOutcome.NotRequired(compatibility)
        }
        val currentBuild = runCatching { StaticDatabaseMetadataReader.read(paths.activeDatabase).sdeBuild }
            .getOrElse { error ->
                return ManagedSchemaUpgradeOutcome.Failed(
                    "Unable to read the managed database build before schema rebuild: ${error.message}",
                    oldDatabasePreserved = Files.isRegularFile(paths.activeDatabase),
                )
            }

        val cached = runCatching { archiveSource.cached(currentBuild) }.getOrNull()
        val preparation = cached?.let { archive ->
            runCatching {
                preparer.prepare(archive, SdeBuildInfo(currentBuild), currentBuild)
                PreparedSchemaUpgrade(usedCachedArchive = true)
            }
        }
        val prepared = preparation?.getOrNull() ?: runCatching {
            val (buildInfo, archive) = archiveSource.downloadLatest()
            preparer.prepare(archive, buildInfo, currentBuild)
            PreparedSchemaUpgrade(usedCachedArchive = false)
        }.getOrElse { error ->
            return ManagedSchemaUpgradeOutcome.Failed(
                "Unable to prepare schema ${StaticDatabaseSchema.VERSION} rebuild: ${error.message}",
                oldDatabasePreserved = Files.isRegularFile(paths.activeDatabase),
            )
        }

        return when (val activation = activator.activatePending()) {
            is ActivationOutcome.Activated -> ManagedSchemaUpgradeOutcome.Upgraded(
                oldSchema = compatibility.actualVersion,
                newSchema = StaticDatabaseSchema.VERSION,
                build = activation.build,
                usedCachedArchive = prepared.usedCachedArchive,
            )
            is ActivationOutcome.RolledBack -> ManagedSchemaUpgradeOutcome.Failed(
                "Schema rebuild activation rolled back: ${activation.reason}",
                oldDatabasePreserved = true,
            )
            is ActivationOutcome.Failed -> ManagedSchemaUpgradeOutcome.Failed(
                "Schema rebuild activation failed: ${activation.message}",
                oldDatabasePreserved = activation.currentDatabaseRemainsActive,
            )
            is ActivationOutcome.Fatal -> ManagedSchemaUpgradeOutcome.Failed(
                "Schema rebuild activation could not recover safely: ${activation.message}",
                oldDatabasePreserved = Files.isRegularFile(paths.activeDatabase),
            )
            ActivationOutcome.NoPendingUpdate -> ManagedSchemaUpgradeOutcome.Failed(
                "Schema rebuild candidate disappeared before activation",
                oldDatabasePreserved = Files.isRegularFile(paths.activeDatabase),
            )
        }
    }
}

private data class PreparedSchemaUpgrade(
    val usedCachedArchive: Boolean,
)
