package dev.evestaticmapplanner.staticdata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.StaticDatabaseMode
import dev.evestaticmapplanner.sde.update.SdeUpdateComparison
import dev.evestaticmapplanner.sde.update.SdeUpdaterPhase
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveOutlinedButton as OutlinedButton
import dev.evestaticmapplanner.ui.EveWindowSurface

@Composable
fun StaticDataBootstrapScreen(state: StaticDataManagerUiState, viewModel: StaticDataManagerViewModel) {
    EveWindowSurface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth(0.65f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Static Data Setup", style = MaterialTheme.typography.headlineMedium)
                Text("No static data installed")
                StaticDataManagerContent(state, viewModel)
            }
        }
    }
}

@Composable
fun StaticDataManagerDialog(
    state: StaticDataManagerUiState,
    viewModel: StaticDataManagerViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Static Data") },
        text = { StaticDataManagerContent(state, viewModel) },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StaticDataManagerContent(state: StaticDataManagerUiState, viewModel: StaticDataManagerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Info("Mode", if (state.mode == StaticDatabaseMode.MANAGED) "Managed database" else "External database")
        Info("Database", state.databasePath.toString())
        Info("Current", state.currentBuild?.toString() ?: "Not installed")
        Info("Latest", state.latestBuild?.toString() ?: "Not checked")
        Info("Last checked", state.lastChecked ?: "Never")
        Info("Status", statusText(state))
        if (state.mode == StaticDatabaseMode.EXTERNAL) {
            Text("Updates cannot replace this file automatically.", color = EveColors.Warning)
        }
        if (state.phase == SdeUpdaterPhase.DOWNLOADING) {
            val total = state.totalBytes
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text("${formatMb(state.downloadedBytes)} / ${total?.let(::formatMb) ?: "unknown"}")
        }
        state.error?.let { Text(it, color = EveColors.Error) }
        if (state.mode == StaticDatabaseMode.MANAGED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.checkForUpdates() },
                    enabled = state.phase in setOf(SdeUpdaterPhase.IDLE, SdeUpdaterPhase.FAILED, SdeUpdaterPhase.SUCCEEDED),
                ) { Text("Check for Updates") }
                Button(
                    onClick = { viewModel.downloadAndPrepare() },
                    enabled = state.phase in setOf(SdeUpdaterPhase.IDLE, SdeUpdaterPhase.FAILED) &&
                        state.comparison in setOf(SdeUpdateComparison.INSTALL_AVAILABLE, SdeUpdateComparison.UPDATE_AVAILABLE),
                ) { Text(if (state.currentBuild == null) "Install Static Data" else "Download & Prepare") }
            }
            if (state.phase == SdeUpdaterPhase.CHECKING || state.phase == SdeUpdaterPhase.DOWNLOADING) {
                OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
            }
            if (state.pendingBuild != null) {
                OutlinedButton(onClick = { viewModel.discardPending() }) { Text("Discard Pending Update") }
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = EveColors.SecondaryText)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun statusText(state: StaticDataManagerUiState): String = when (state.phase) {
    SdeUpdaterPhase.IDLE -> when (state.comparison) {
        SdeUpdateComparison.INSTALL_AVAILABLE -> "Install available"
        SdeUpdateComparison.UPDATE_AVAILABLE -> "Update available"
        SdeUpdateComparison.UP_TO_DATE -> "Up to date"
        SdeUpdateComparison.LOCAL_NEWER -> "Local build is newer"
        null -> "Idle"
    }
    SdeUpdaterPhase.RESTART_REQUIRED -> "Restart required to install build ${state.pendingBuild}"
    SdeUpdaterPhase.SUCCEEDED -> "Static data installed"
    else -> state.phase.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0)
