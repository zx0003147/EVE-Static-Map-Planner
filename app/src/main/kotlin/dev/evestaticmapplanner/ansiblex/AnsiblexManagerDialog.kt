package dev.evestaticmapplanner.ansiblex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.ImportDiagnosticSeverity
import dev.evestaticmapplanner.route.RoutePlannerUiState
import dev.evestaticmapplanner.route.RoutePlannerViewModel
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveCheckbox as Checkbox
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDivider
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveLazyColumn
import dev.evestaticmapplanner.ui.EvePanel
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveVerticalScrollColumn
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal enum class ClearConfirmation { IMPORTED, ALL }

@Composable
fun AnsiblexManagerDialog(
    userDatabasePath: Path,
    state: RoutePlannerUiState,
    viewModel: RoutePlannerViewModel,
    onDismiss: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.ansiblex
    var manualFrom by remember { mutableStateOf("") }
    var manualTo by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var manualNotes by remember { mutableStateOf("") }
    var bidirectional by remember { mutableStateOf(true) }
    var confirmation by remember { mutableStateOf<ClearConfirmation?>(null) }
    var clearAllPhrase by remember { mutableStateOf("") }
    val dismissConfirmation = {
        confirmation = null
        clearAllPhrase = ""
    }
    val dismissManager = {
        if (confirmation == null) onDismiss()
    }

    DialogWindow(
        onCloseRequest = dismissManager,
        title = strings.managerTitle,
        state = rememberDialogState(
            width = ANSIBLEX_MANAGER_DEFAULT_SIZE.width,
            height = ANSIBLEX_MANAGER_DEFAULT_SIZE.height,
        ),
    ) {
        EveWindowChrome(window)
        val density = LocalDensity.current
        val minimumWidthPx = with(density) { ANSIBLEX_MANAGER_MINIMUM_SIZE.width.roundToPx() }
        val minimumHeightPx = with(density) { ANSIBLEX_MANAGER_MINIMUM_SIZE.height.roundToPx() }
        SideEffect {
            window.minimumSize = Dimension(minimumWidthPx, minimumHeightPx)
        }
        AnsiblexManagerRoot {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(strings.managerTitle, style = MaterialTheme.typography.titleLarge)
                        Text(
                            strings.summary(
                                state.enabledAnsiblexCount,
                                state.ansiblexConnections.size,
                                userDatabasePath.toString(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = EveColors.SecondaryText,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismissManager) { Text(appStrings.common.close) }
                }
                EveDivider()
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    EveVerticalScrollColumn(
                        Modifier
                            .width(ANSIBLEX_MANAGER_FORM_WIDTH)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(strings.importCsvJson, style = MaterialTheme.typography.titleMedium)
                        Row {
                            AnsiblexImportMode.entries.forEach { mode ->
                                TextButton(
                                    onClick = { viewModel.setImportMode(mode) },
                                    enabled = mode != state.importMode,
                                    selected = mode == state.importMode,
                                ) { Text(strings.importMode(mode)) }
                            }
                        }
                        Button(
                            onClick = {
                                chooseImportFile(strings.fileChooserTitle, strings.fileChooserFilter)
                                    ?.let(viewModel::previewImport)
                            },
                            enabled = !state.isImportBusy,
                        ) { Text(if (state.isImportBusy) strings.working else strings.importAndPreview) }
                        state.importPreview?.let { preview ->
                            Text(
                                strings.previewCounts(
                                    preview.rawRowCount,
                                    preview.validRowCount,
                                    preview.invalidRowCount,
                                    preview.duplicateCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                strings.previewChanges(
                                    preview.additions.size,
                                    preview.updates.size,
                                    preview.unchanged.size,
                                    preview.removals.size,
                                ),
                                color = EveColors.Important,
                            )
                            preview.diagnostics.take(6).forEach { diagnostic ->
                                Text(
                                    strings.diagnostic(diagnostic),
                                    color = if (diagnostic.severity == ImportDiagnosticSeverity.ERROR) EveColors.Error else EveColors.Warning,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row {
                                Button(
                                    onClick = viewModel::applyImport,
                                    enabled = preview.canApply && !state.isImportBusy,
                                ) { Text(appStrings.common.apply) }
                                TextButton(onClick = viewModel::discardImportPreview) { Text(strings.discard) }
                            }
                        }
                        state.importError?.let {
                            Text(it.resolve(appStrings), color = EveColors.Error, style = MaterialTheme.typography.bodySmall)
                        }
                        EveDivider()
                        Text(strings.manualAdd, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(manualFrom, { manualFrom = it }, label = { Text(strings.fromNameOrId) }, singleLine = true)
                        OutlinedTextField(manualTo, { manualTo = it }, label = { Text(strings.toNameOrId) }, singleLine = true)
                        OutlinedTextField(manualName, { manualName = it }, label = { Text(strings.connectionNameOptional) }, singleLine = true)
                        OutlinedTextField(manualNotes, { manualNotes = it }, label = { Text(strings.notesOptional) })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(bidirectional, { bidirectional = it })
                            Text(if (bidirectional) strings.bidirectional else strings.fromTo)
                        }
                        Button(
                            onClick = {
                                viewModel.addManual(manualFrom, manualTo, bidirectional, manualName, manualNotes)
                            },
                            enabled = manualFrom.isNotBlank() && manualTo.isNotBlank(),
                        ) { Text(strings.addConnection) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.connections, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { confirmation = ClearConfirmation.IMPORTED }) { Text(strings.clearImported) }
                        }
                        EveLazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(state.ansiblexConnections, key = AnsiblexConnection::id) { connection ->
                                ConnectionRow(connection, viewModel)
                            }
                        }
                        EveDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.dangerZone, color = EveColors.Error, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { confirmation = ClearConfirmation.ALL }) {
                                Text(strings.clearAllAnsiblex, color = EveColors.Error)
                            }
                        }
                    }
                }
                state.managerMessage?.let {
                    Text(it.resolve(appStrings), color = EveColors.Important, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        confirmation?.let { kind ->
            AnsiblexClearConfirmationDialog(
                kind = kind,
                clearAllPhrase = clearAllPhrase,
                onClearAllPhraseChange = { clearAllPhrase = it },
                onConfirm = {
                    if (kind == ClearConfirmation.ALL) viewModel.clearAll() else viewModel.clearImported()
                    dismissConfirmation()
                },
                onDismiss = dismissConfirmation,
            )
        }
    }
}

@Composable
internal fun AnsiblexManagerRoot(content: @Composable () -> Unit) {
    EveWindowSurface(
        modifier = Modifier.fillMaxSize().testTag(ANSIBLEX_MANAGER_ROOT_TEST_TAG),
        content = content,
    )
}

internal val ANSIBLEX_MANAGER_DEFAULT_SIZE = DpSize(960.dp, 760.dp)
internal val ANSIBLEX_MANAGER_MINIMUM_SIZE = DpSize(840.dp, 680.dp)
internal val ANSIBLEX_MANAGER_FORM_WIDTH = 390.dp
internal val ANSIBLEX_MANAGER_BACKGROUND = EveColors.PrimarySurface
internal const val ANSIBLEX_MANAGER_ROOT_TEST_TAG = "ansiblex-manager-root"

@Composable
internal fun AnsiblexClearConfirmationDialog(
    kind: ClearConfirmation,
    clearAllPhrase: String,
    onClearAllPhraseChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.ansiblex
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == ClearConfirmation.ALL) strings.deleteAllTitle else strings.deleteImportedTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (kind == ClearConfirmation.ALL) {
                        strings.deleteAllWarning
                    } else {
                        strings.deleteImportedWarning
                    },
                )
                if (kind == ClearConfirmation.ALL) {
                    Text(strings.typeDeleteManual, color = EveColors.Error)
                    OutlinedTextField(clearAllPhrase, onClearAllPhraseChange, singleLine = true)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = kind != ClearConfirmation.ALL || clearAllPhrase == "DELETE MANUAL",
            ) { Text(if (kind == ClearConfirmation.ALL) strings.deleteEverything else strings.clearImported) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(appStrings.common.cancel) }
        },
    )
}

@Composable
private fun ConnectionRow(connection: AnsiblexConnection, viewModel: RoutePlannerViewModel) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.ansiblex
    EvePanel(secondary = true, bordered = false) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(connection.enabled, { viewModel.setConnectionEnabled(connection.id, it) })
            Column(Modifier.weight(1f)) {
                Text(connection.displayName ?: "${connection.firstSystemId} ↔ ${connection.secondSystemId}")
                Text(
                    "${connection.firstSystemId} / ${connection.secondSystemId} · " +
                        "${strings.direction(connection.direction)} · ${strings.source(connection.source)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = EveColors.SecondaryText,
                )
            }
            TextButton(onClick = { viewModel.deleteConnection(connection.id) }) { Text(appStrings.common.delete) }
        }
    }
}

private fun chooseImportFile(dialogTitle: String, filterDescription: String): Path? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        fileFilter = FileNameExtensionFilter(filterDescription, "csv", "json")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
