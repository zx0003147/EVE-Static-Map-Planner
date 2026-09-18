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
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveOutlinedButton as OutlinedButton
import dev.evestaticmapplanner.ui.EveWindowSurface

@Composable
fun StaticDataBootstrapScreen(state: StaticDataManagerUiState, viewModel: StaticDataManagerViewModel) {
    val strings = LocalAppStrings.current.staticData
    EveWindowSurface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth(0.65f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.setupTitle, style = MaterialTheme.typography.headlineMedium)
                Text(strings.noStaticDataInstalled)
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
    val strings = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.staticData.title) },
        text = { StaticDataManagerContent(state, viewModel) },
        confirmButton = { Button(onClick = onDismiss) { Text(strings.common.close) } },
    )
}

@Composable
private fun StaticDataManagerContent(state: StaticDataManagerUiState, viewModel: StaticDataManagerViewModel) {
    val strings = LocalAppStrings.current
    val staticData = strings.staticData
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Info(staticData.mode, if (state.mode == StaticDatabaseMode.MANAGED) staticData.managedDatabase else staticData.externalDatabase)
        Info(staticData.database, state.databasePath.toString())
        Info(staticData.currentBuild, state.currentBuild?.toString() ?: staticData.notInstalled)
        Info(staticData.latestBuild, state.latestBuild?.toString() ?: staticData.notChecked)
        Info(staticData.lastChecked, state.lastChecked ?: staticData.never)
        Info(staticData.status, statusText(state, staticData))
        if (state.mode == StaticDatabaseMode.EXTERNAL) {
            Text(staticData.externalDatabaseWarning, color = EveColors.Warning)
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
            Text("${formatMb(state.downloadedBytes)} / ${total?.let(::formatMb) ?: staticData.unknownSize}")
        }
        state.error?.let { Text(it.resolve(strings), color = EveColors.Error) }
        if (state.mode == StaticDatabaseMode.MANAGED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.checkForUpdates() },
                    enabled = state.phase in setOf(SdeUpdaterPhase.IDLE, SdeUpdaterPhase.FAILED, SdeUpdaterPhase.SUCCEEDED),
                ) { Text(staticData.checkForUpdates) }
                Button(
                    onClick = { viewModel.downloadAndPrepare() },
                    enabled = state.phase in setOf(SdeUpdaterPhase.IDLE, SdeUpdaterPhase.FAILED) &&
                        state.comparison in setOf(SdeUpdateComparison.INSTALL_AVAILABLE, SdeUpdateComparison.UPDATE_AVAILABLE),
                ) { Text(if (state.currentBuild == null) staticData.installStaticData else staticData.downloadAndPrepare) }
            }
            if (state.phase == SdeUpdaterPhase.CHECKING || state.phase == SdeUpdaterPhase.DOWNLOADING) {
                OutlinedButton(onClick = { viewModel.cancel() }) { Text(staticData.cancel) }
            }
            if (state.pendingBuild != null) {
                OutlinedButton(onClick = { viewModel.discardPending() }) { Text(staticData.discardPendingUpdate) }
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

private fun statusText(
    state: StaticDataManagerUiState,
    strings: dev.evestaticmapplanner.localization.StaticDataStrings,
): String = if (state.phase == SdeUpdaterPhase.IDLE) {
    strings.comparison(state.comparison)
} else {
    strings.phase(state.phase, state.pendingBuild)
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0)
