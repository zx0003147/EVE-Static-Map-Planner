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
        title = "Ansiblex Manager",
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
                        Text("Ansiblex Manager", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.enabledAnsiblexCount} enabled / ${state.ansiblexConnections.size} total · $userDatabasePath",
                            style = MaterialTheme.typography.bodySmall,
                            color = EveColors.SecondaryText,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismissManager) { Text("Close") }
                }
                EveDivider()
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    EveVerticalScrollColumn(
                        Modifier
                            .width(ANSIBLEX_MANAGER_FORM_WIDTH)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Import CSV / JSON", style = MaterialTheme.typography.titleMedium)
                        Row {
                            AnsiblexImportMode.entries.forEach { mode ->
                                TextButton(
                                    onClick = { viewModel.setImportMode(mode) },
                                    enabled = mode != state.importMode,
                                    selected = mode == state.importMode,
                                ) { Text(mode.name) }
                            }
                        }
                        Button(
                            onClick = {
                                chooseImportFile()?.let(viewModel::previewImport)
                            },
                            enabled = !state.isImportBusy,
                        ) { Text(if (state.isImportBusy) "Working…" else "Import and Preview") }
                        state.importPreview?.let { preview ->
                            Text(
                                "Rows ${preview.rawRowCount} · valid ${preview.validRowCount} · invalid ${preview.invalidRowCount} · " +
                                    "duplicates ${preview.duplicateCount}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "+${preview.additions.size}  ~${preview.updates.size}  =${preview.unchanged.size}  -${preview.removals.size}",
                                color = EveColors.Important,
                            )
                            preview.diagnostics.take(6).forEach { diagnostic ->
                                Text(
                                    "${diagnostic.rowNumber?.let { "Row $it: " }.orEmpty()}${diagnostic.message}",
                                    color = if (diagnostic.severity == ImportDiagnosticSeverity.ERROR) EveColors.Error else EveColors.Warning,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row {
                                Button(
                                    onClick = viewModel::applyImport,
                                    enabled = preview.canApply && !state.isImportBusy,
                                ) { Text("Apply") }
                                TextButton(onClick = viewModel::discardImportPreview) { Text("Discard") }
                            }
                        }
                        state.importError?.let { Text(it, color = EveColors.Error, style = MaterialTheme.typography.bodySmall) }
                        EveDivider()
                        Text("Manual Add", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(manualFrom, { manualFrom = it }, label = { Text("From name or ID") }, singleLine = true)
                        OutlinedTextField(manualTo, { manualTo = it }, label = { Text("To name or ID") }, singleLine = true)
                        OutlinedTextField(manualName, { manualName = it }, label = { Text("Connection name (optional)") }, singleLine = true)
                        OutlinedTextField(manualNotes, { manualNotes = it }, label = { Text("Notes (optional)") })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(bidirectional, { bidirectional = it })
                            Text(if (bidirectional) "Bidirectional" else "From → To")
                        }
                        Button(
                            onClick = {
                                viewModel.addManual(manualFrom, manualTo, bidirectional, manualName, manualNotes)
                            },
                            enabled = manualFrom.isNotBlank() && manualTo.isNotBlank(),
                        ) { Text("Add Connection") }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Connections", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { confirmation = ClearConfirmation.IMPORTED }) { Text("Clear Imported") }
                        }
                        EveLazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(state.ansiblexConnections, key = AnsiblexConnection::id) { connection ->
                                ConnectionRow(connection, viewModel)
                            }
                        }
                        EveDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Danger zone", color = EveColors.Error, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { confirmation = ClearConfirmation.ALL }) {
                                Text("Clear All Ansiblex", color = EveColors.Error)
                            }
                        }
                    }
                }
                state.managerMessage?.let {
                    Text(it, color = EveColors.Important, style = MaterialTheme.typography.bodySmall)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == ClearConfirmation.ALL) "Delete all Ansiblex data?" else "Delete imported Ansiblex data?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (kind == ClearConfirmation.ALL) {
                        "This permanently deletes IMPORT and MANUAL connections from user.db. This cannot be undone."
                    } else {
                        "This deletes only source=IMPORT connections. MANUAL connections are preserved."
                    },
                )
                if (kind == ClearConfirmation.ALL) {
                    Text("Type DELETE MANUAL to confirm:", color = EveColors.Error)
                    OutlinedTextField(clearAllPhrase, onClearAllPhraseChange, singleLine = true)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = kind != ClearConfirmation.ALL || clearAllPhrase == "DELETE MANUAL",
            ) { Text(if (kind == ClearConfirmation.ALL) "Delete Everything" else "Clear Imported") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConnectionRow(connection: AnsiblexConnection, viewModel: RoutePlannerViewModel) {
    EvePanel(secondary = true, bordered = false) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(connection.enabled, { viewModel.setConnectionEnabled(connection.id, it) })
            Column(Modifier.weight(1f)) {
                Text(connection.displayName ?: "${connection.firstSystemId} ↔ ${connection.secondSystemId}")
                Text(
                    "${connection.firstSystemId} / ${connection.secondSystemId} · ${connection.direction.name} · ${connection.source.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = EveColors.SecondaryText,
                )
            }
            TextButton(onClick = { viewModel.deleteConnection(connection.id) }) { Text("Delete") }
        }
    }
}

private fun chooseImportFile(): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Select synthetic or user-maintained Ansiblex CSV/JSON"
        fileFilter = FileNameExtensionFilter("Ansiblex CSV or JSON", "csv", "json")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
