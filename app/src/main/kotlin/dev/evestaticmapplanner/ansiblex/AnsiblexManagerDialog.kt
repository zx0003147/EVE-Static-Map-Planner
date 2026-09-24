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
import dev.evestaticmapplanner.core.ansiblex.AnsiblexAccessPolicy
import dev.evestaticmapplanner.core.ansiblex.MAX_ALLIANCE_NAME_LENGTH
import dev.evestaticmapplanner.core.ansiblex.MAX_ALLIANCE_TICKER_LENGTH
import dev.evestaticmapplanner.core.alliance.AllianceDirectorySnapshot
import dev.evestaticmapplanner.core.alliance.AllianceOwnerResolution
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.ImportDiagnosticSeverity
import dev.evestaticmapplanner.search.AllianceSearchField
import dev.evestaticmapplanner.search.displayLabel
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
    allianceDirectorySnapshot: AllianceDirectorySnapshot,
    viewModel: RoutePlannerViewModel,
    onDismiss: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.ansiblex
    var manualFrom by remember { mutableStateOf("") }
    var manualTo by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var manualNotes by remember { mutableStateOf("") }
    var manualOwnerAllianceId by remember { mutableStateOf("") }
    var manualOwnerAllianceName by remember { mutableStateOf("") }
    var manualOwnerAllianceTicker by remember { mutableStateOf("") }
    var showPasteImport by remember { mutableStateOf(false) }
    var pastedText by remember { mutableStateOf("") }
    var resolvingOwnerRaw by remember { mutableStateOf<String?>(null) }
    var bidirectional by remember { mutableStateOf(true) }
    var confirmation by remember { mutableStateOf<ClearConfirmation?>(null) }
    var clearAllPhrase by remember { mutableStateOf("") }
    val dismissConfirmation = {
        confirmation = null
        clearAllPhrase = ""
    }
    val dismissManager = {
        if (confirmation == null && !showPasteImport) onDismiss()
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
                        AnsiblexImportEntryButtons(
                            busy = state.isImportBusy,
                            onFileImport = {
                                chooseImportFile(strings.fileChooserTitle, strings.fileChooserFilter)
                                    ?.let(viewModel::previewImport)
                            },
                            onPasteImport = { showPasteImport = true },
                        )
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
                            if (preview.ownerResolutions.isNotEmpty()) {
                                Text(strings.ownerResolution, style = MaterialTheme.typography.titleSmall)
                                preview.ownerResolutions.forEach { owner ->
                                    val resolved = owner.resolution as? AllianceOwnerResolution.ResolvedExact
                                    Text(
                                        if (resolved != null) {
                                            "${owner.rawText} ✓ ${resolved.alliance.displayLabel()} · #${resolved.alliance.allianceId}"
                                        } else {
                                            "${owner.rawText} ⚠ ${strings.needsConfirmation}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (resolved != null) EveColors.Success else EveColors.Warning,
                                    )
                                    val candidates = when (val resolution = owner.resolution) {
                                        is AllianceOwnerResolution.AmbiguousExact -> resolution.candidates
                                        is AllianceOwnerResolution.NeedsConfirmation -> resolution.candidates
                                        else -> emptyList()
                                    }
                                    candidates.take(4).forEach { candidate ->
                                        TextButton(onClick = { viewModel.confirmImportOwner(owner.rawText, candidate) }) {
                                            Text("${candidate.displayLabel()} · #${candidate.allianceId}")
                                        }
                                    }
                                    if (resolved == null) {
                                        TextButton(onClick = { resolvingOwnerRaw = owner.rawText }) {
                                            Text(strings.searchAlliance)
                                        }
                                        if (resolvingOwnerRaw == owner.rawText) {
                                            AllianceSearchField(
                                                snapshot = allianceDirectorySnapshot,
                                                label = strings.searchAlliance,
                                                onSelect = { alliance ->
                                                    viewModel.confirmImportOwner(owner.rawText, alliance)
                                                    resolvingOwnerRaw = null
                                                },
                                            )
                                        }
                                    }
                                }
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
                        OutlinedTextField(
                            manualOwnerAllianceId,
                            { manualOwnerAllianceId = it },
                            label = { Text(strings.ownerAllianceId) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            manualOwnerAllianceName,
                            { manualOwnerAllianceName = it },
                            label = { Text(strings.ownerAllianceName) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            manualOwnerAllianceTicker,
                            { manualOwnerAllianceTicker = it },
                            label = { Text(strings.ownerAllianceTicker) },
                            singleLine = true,
                        )
                        OutlinedTextField(manualNotes, { manualNotes = it }, label = { Text(strings.notesOptional) })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(bidirectional, { bidirectional = it })
                            Text(if (bidirectional) strings.bidirectional else strings.fromTo)
                        }
                        Button(
                            onClick = {
                                viewModel.addManual(
                                    manualFrom,
                                    manualTo,
                                    bidirectional,
                                    manualName,
                                    manualNotes,
                                    manualOwnerAllianceId.trim().toLongOrNull(),
                                    manualOwnerAllianceName,
                                    manualOwnerAllianceTicker,
                                )
                            },
                            enabled = manualFrom.isNotBlank() && manualTo.isNotBlank() &&
                                (manualOwnerAllianceId.isBlank() ||
                                    manualOwnerAllianceId.trim().toLongOrNull()?.let { it > 0 } == true) &&
                                manualOwnerAllianceName.trim().length <= MAX_ALLIANCE_NAME_LENGTH &&
                                manualOwnerAllianceTicker.trim().length <= MAX_ALLIANCE_TICKER_LENGTH,
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
        if (showPasteImport) {
            AnsiblexPasteImportDialog(
                pastedText = pastedText,
                onPastedTextChange = { pastedText = it },
                onParse = { text ->
                    viewModel.previewPastedImport(text)
                    showPasteImport = false
                },
                onDismiss = { showPasteImport = false },
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

@Composable
internal fun AnsiblexImportEntryButtons(
    busy: Boolean,
    onFileImport: () -> Unit,
    onPasteImport: () -> Unit,
) {
    val strings = LocalAppStrings.current.ansiblex
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onFileImport, enabled = !busy) {
            Text(if (busy) strings.working else strings.fileImport)
        }
        Button(onClick = onPasteImport, enabled = !busy) { Text(strings.pasteImport) }
    }
}

@Composable
internal fun AnsiblexPasteImportDialog(
    pastedText: String,
    onPastedTextChange: (String) -> Unit,
    onParse: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.ansiblex
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.pasteTitle) },
        text = {
            OutlinedTextField(
                value = pastedText,
                onValueChange = onPastedTextChange,
                label = { Text(strings.pasteHint) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 12,
            )
        },
        confirmButton = {
            Button(
                onClick = { onParse(pastedText) },
                enabled = pastedText.isNotBlank(),
            ) { Text(strings.parse) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appStrings.common.cancel) } },
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
    val accessStatus = AnsiblexAccessPolicy.status(connection, viewModel.state.value.currentIdentityContext)
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
                Text(
                    "Owner: ${connection.ownerDisplayLabel()} · ${strings.accessStatus(accessStatus)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessStatus == dev.evestaticmapplanner.core.ansiblex.AnsiblexAccessStatus.AVAILABLE) {
                        EveColors.Important
                    } else {
                        EveColors.Warning
                    },
                )
            }
            TextButton(onClick = { viewModel.deleteConnection(connection.id) }) { Text(appStrings.common.delete) }
        }
    }
}

private fun AnsiblexConnection.ownerDisplayLabel(): String {
    val display = listOfNotNull(ownerAllianceName, ownerAllianceTicker?.let { "[$it]" }).joinToString(" ")
    return listOfNotNull(display.takeIf(String::isNotEmpty), ownerAllianceId?.let { "#$it" }).joinToString(" · ")
        .ifEmpty { "—" }
}

private fun chooseImportFile(dialogTitle: String, filterDescription: String): Path? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        fileFilter = FileNameExtensionFilter(filterDescription, "csv", "json")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
