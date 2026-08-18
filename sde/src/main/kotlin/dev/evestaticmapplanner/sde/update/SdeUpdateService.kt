package dev.evestaticmapplanner.sde.update

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CancellationException

enum class SdeUpdateComparison {
    INSTALL_AVAILABLE,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    LOCAL_NEWER,
}

enum class SdeUpdaterPhase {
    IDLE,
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    READING_REGIONS,
    READING_CONSTELLATIONS,
    READING_SYSTEMS,
    READING_STARGATES,
    VALIDATING_REFERENCES,
    BUILDING_DATABASE,
    VALIDATING_DATABASE,
    RESTART_REQUIRED,
    APPLYING,
    SUCCEEDED,
    FAILED,
    FATAL,
}

data class SdeUpdateState(
    val currentBuild: Long? = null,
    val latestBuild: Long? = null,
    val lastChecked: Instant? = null,
    val comparison: SdeUpdateComparison? = null,
    val phase: SdeUpdaterPhase = SdeUpdaterPhase.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
    val pendingBuild: Long? = null,
)

class SdeUpdateService(
    private val paths: ManagedStaticDataPaths,
    private val client: SdeUpdateClient,
    private val downloader: SdeArchiveDownloader,
    private val preparer: SdeCandidatePreparer,
    private val activator: PendingUpdateActivator,
    private val pendingStore: PendingUpdateStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val auditStore: UpdaterAuditStore = UpdaterAuditStore(paths),
    private val onFirstInstallActivated: (Long) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<SdeUpdateState> = mutableState.asStateFlow()
    private var activeJob: Job? = null

    fun checkForUpdates(): Boolean = startJob {
        mutableState.update { it.copy(phase = SdeUpdaterPhase.CHECKING, error = null) }
        try {
            val result = runInterruptible(ioDispatcher) { client.checkLatest() }
            val current = readCurrentBuild()
            val comparison = compareBuilds(current, result.buildInfo.buildNumber)
            mutableState.update {
                it.copy(
                    currentBuild = current,
                    latestBuild = result.buildInfo.buildNumber,
                    lastChecked = result.checkedAt,
                    comparison = comparison,
                    phase = SdeUpdaterPhase.IDLE,
                )
            }
            auditStore.append("CHECK_SUCCEEDED", current, result.buildInfo.buildNumber)
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(phase = SdeUpdaterPhase.IDLE, error = null) }
        } catch (error: Throwable) {
            mutableState.update { it.copy(phase = SdeUpdaterPhase.FAILED, error = "Unable to check: ${error.message}") }
            auditStore.append("CHECK_FAILED", oldBuild = readCurrentBuild(), message = error.message)
        }
    }

    fun downloadAndPrepare(): Boolean {
        val latest = state.value.latestBuild ?: return false
        val comparison = state.value.comparison
        if (comparison != SdeUpdateComparison.INSTALL_AVAILABLE && comparison != SdeUpdateComparison.UPDATE_AVAILABLE) return false
        return startJob {
            try {
                mutableState.update {
                    it.copy(phase = SdeUpdaterPhase.DOWNLOADING, downloadedBytes = 0, totalBytes = null, error = null)
                }
                val buildInfo = SdeBuildInfo(latest)
                val uri = client.fixedBuildUri(latest)
                val archive = runInterruptible(ioDispatcher) {
                    downloader.download(
                        uri,
                        latest,
                        onProgress = { progress ->
                            mutableState.update {
                                it.copy(downloadedBytes = progress.downloadedBytes, totalBytes = progress.totalBytes)
                            }
                        },
                        isCancelled = { Thread.currentThread().isInterrupted },
                    )
                }
                val current = readCurrentBuild()
                val manifest = runInterruptible(ioDispatcher) {
                    preparer.prepare(archive, buildInfo, current) { progress ->
                        mutableState.update { it.copy(phase = progress.toPhase()) }
                    }
                }
                auditStore.append(
                    "CANDIDATE_PREPARED",
                    current,
                    manifest.targetBuild,
                    manifest.sourceUrl,
                    manifest.archiveSha256,
                )
                if (current == null) {
                    mutableState.update { it.copy(phase = SdeUpdaterPhase.APPLYING, pendingBuild = manifest.targetBuild) }
                    when (val outcome = runInterruptible(ioDispatcher) { activator.activatePending() }) {
                        is ActivationOutcome.Activated -> {
                            mutableState.update {
                                it.copy(
                                    currentBuild = outcome.build,
                                    pendingBuild = null,
                                    phase = SdeUpdaterPhase.SUCCEEDED,
                                    comparison = SdeUpdateComparison.UP_TO_DATE,
                                )
                            }
                            auditStore.append("FIRST_INSTALL_ACTIVATED", newBuild = outcome.build)
                            onFirstInstallActivated(outcome.build)
                        }
                        is ActivationOutcome.Fatal -> failFatal(outcome.message)
                        is ActivationOutcome.Failed -> fail(outcome.message)
                        else -> fail("Unexpected first-install activation result: $outcome")
                    }
                } else {
                    mutableState.update {
                        it.copy(phase = SdeUpdaterPhase.RESTART_REQUIRED, pendingBuild = manifest.targetBuild)
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(phase = SdeUpdaterPhase.IDLE, error = null) }
            } catch (error: Throwable) {
                fail("Update preparation failed: ${error.message}")
                auditStore.append("PREPARE_FAILED", oldBuild = readCurrentBuild(), newBuild = latest, message = error.message)
            }
        }
    }

    fun cancel(): Boolean {
        val cancellable = state.value.phase == SdeUpdaterPhase.CHECKING || state.value.phase == SdeUpdaterPhase.DOWNLOADING
        if (cancellable) activeJob?.cancel()
        return cancellable
    }

    fun discardPending(): Boolean {
        if (activeJob?.isActive == true) return false
        val manifest = pendingStore.read() ?: return false
        pendingStore.delete()
        val staging = paths.stagingRoot(manifest.stagingId)
        if (Files.exists(staging)) {
            Files.walk(staging).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
        mutableState.update { it.copy(pendingBuild = null, phase = SdeUpdaterPhase.IDLE, error = null) }
        auditStore.append("PENDING_DISCARDED", oldBuild = readCurrentBuild(), newBuild = manifest.targetBuild)
        return true
    }

    private fun startJob(block: suspend () -> Unit): Boolean {
        if (activeJob?.isActive == true) return false
        activeJob = scope.launch { block() }
        return true
    }

    private fun initialState(): SdeUpdateState {
        val current = readCurrentBuild()
        val pending = runCatching { pendingStore.read()?.targetBuild }.getOrNull()
        return SdeUpdateState(
            currentBuild = current,
            pendingBuild = pending,
            phase = if (pending != null) SdeUpdaterPhase.RESTART_REQUIRED else SdeUpdaterPhase.IDLE,
        )
    }

    private fun readCurrentBuild(): Long? = if (Files.isRegularFile(paths.activeDatabase)) {
        runCatching { StaticDatabaseMetadataReader.read(paths.activeDatabase).sdeBuild }.getOrNull()
    } else null

    private fun fail(message: String) {
        mutableState.update { it.copy(phase = SdeUpdaterPhase.FAILED, error = message) }
    }

    private fun failFatal(message: String) {
        mutableState.update { it.copy(phase = SdeUpdaterPhase.FATAL, error = message) }
    }
}

fun compareBuilds(current: Long?, latest: Long): SdeUpdateComparison = when {
    current == null -> SdeUpdateComparison.INSTALL_AVAILABLE
    latest > current -> SdeUpdateComparison.UPDATE_AVAILABLE
    latest == current -> SdeUpdateComparison.UP_TO_DATE
    else -> SdeUpdateComparison.LOCAL_NEWER
}

private fun CandidatePreparationProgress.toPhase(): SdeUpdaterPhase = when (this) {
    CandidatePreparationProgress.Extracting -> SdeUpdaterPhase.EXTRACTING
    CandidatePreparationProgress.Validating -> SdeUpdaterPhase.VALIDATING_DATABASE
    is CandidatePreparationProgress.Importing -> when (stage) {
        dev.evestaticmapplanner.sde.SdeImportStage.LOCATING_SOURCES -> SdeUpdaterPhase.EXTRACTING
        dev.evestaticmapplanner.sde.SdeImportStage.READING_REGIONS -> SdeUpdaterPhase.READING_REGIONS
        dev.evestaticmapplanner.sde.SdeImportStage.READING_CONSTELLATIONS -> SdeUpdaterPhase.READING_CONSTELLATIONS
        dev.evestaticmapplanner.sde.SdeImportStage.READING_SYSTEMS -> SdeUpdaterPhase.READING_SYSTEMS
        dev.evestaticmapplanner.sde.SdeImportStage.READING_STARGATES -> SdeUpdaterPhase.READING_STARGATES
        dev.evestaticmapplanner.sde.SdeImportStage.VALIDATING_REFERENCES -> SdeUpdaterPhase.VALIDATING_REFERENCES
        dev.evestaticmapplanner.sde.SdeImportStage.BUILDING_DATABASE -> SdeUpdaterPhase.BUILDING_DATABASE
        dev.evestaticmapplanner.sde.SdeImportStage.VALIDATING_DATABASE -> SdeUpdaterPhase.VALIDATING_DATABASE
    }
}
