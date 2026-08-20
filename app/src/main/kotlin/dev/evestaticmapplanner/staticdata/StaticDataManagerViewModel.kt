package dev.evestaticmapplanner.staticdata

import dev.evestaticmapplanner.AppDiagnostics
import dev.evestaticmapplanner.StaticDatabaseMode
import dev.evestaticmapplanner.sde.update.SdeUpdateComparison
import dev.evestaticmapplanner.sde.update.SdeUpdateService
import dev.evestaticmapplanner.sde.update.SdeUpdateState
import dev.evestaticmapplanner.sde.update.SdeUpdaterPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

data class StaticDataManagerUiState(
    val mode: StaticDatabaseMode,
    val databasePath: Path,
    val currentBuild: Long? = null,
    val latestBuild: Long? = null,
    val lastChecked: String? = null,
    val comparison: SdeUpdateComparison? = null,
    val phase: SdeUpdaterPhase = SdeUpdaterPhase.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
    val pendingBuild: Long? = null,
)

class StaticDataManagerViewModel(
    mode: StaticDatabaseMode,
    databasePath: Path,
    currentBuild: Long?,
    private val service: SdeUpdateService?,
    private val scope: CoroutineScope,
    autoCheck: Boolean,
) {
    private val mutableState = MutableStateFlow(StaticDataManagerUiState(mode, databasePath, currentBuild))
    val state: StateFlow<StaticDataManagerUiState> = mutableState.asStateFlow()

    init {
        if (service != null) {
            scope.launch {
                var lastLoggedError: String? = null
                service.state.collect {
                    mutableState.value = it.toUiState(mode, databasePath)
                    if (it.error != null && it.error != lastLoggedError) {
                        AppDiagnostics.warning("Static-data updater ${it.phase}: ${it.error}")
                        lastLoggedError = it.error
                    } else if (it.error == null) {
                        lastLoggedError = null
                    }
                }
            }
            if (autoCheck && service.state.value.pendingBuild == null) service.checkForUpdates()
        }
    }

    fun checkForUpdates() = service?.checkForUpdates() ?: false
    fun downloadAndPrepare() = service?.downloadAndPrepare() ?: false
    fun cancel() = service?.cancel() ?: false
    fun discardPending() = service?.discardPending() ?: false
    fun close() = scope.cancel()
}

private fun SdeUpdateState.toUiState(mode: StaticDatabaseMode, path: Path) = StaticDataManagerUiState(
    mode = mode,
    databasePath = path,
    currentBuild = currentBuild,
    latestBuild = latestBuild,
    lastChecked = lastChecked?.toString(),
    comparison = comparison,
    phase = phase,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    error = error,
    pendingBuild = pendingBuild,
)
