package dev.evestaticmapplanner.marker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkerManagerWindow(
    markerState: MarkerUiState,
    markerViewModel: MarkerViewModel,
    searchRepository: SystemSearchRepository,
    onShowOnMap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val savedSystemIds = markerState.markersBySystemId.values
        .filter { it.persistence == MarkerPersistence.SAVED }
        .map { it.systemId }
        .sorted()
    var systemNamesById by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var selectedSystemId by remember { mutableStateOf<Int?>(null) }
    var editor by remember { mutableStateOf<MarkerEditorRequest?>(null) }
    var editorSystemQuery by remember { mutableStateOf("") }
    var editorSystemResults by remember { mutableStateOf<List<SolarSystem>>(emptyList()) }
    var editorSelectedSystem by remember { mutableStateOf<SolarSystem?>(null) }
    var editorLocalError by remember { mutableStateOf<String?>(null) }
    var expectedDraft by remember { mutableStateOf<MarkerDraft?>(null) }
    var pendingEditorSystemId by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedMarkerRowPresentation?>(null) }
    var deleteStarted by remember { mutableStateOf(false) }

    LaunchedEffect(savedSystemIds) {
        systemNamesById = withContext(Dispatchers.IO) {
            savedSystemIds.associateWith { systemId ->
                searchRepository.searchSystems(systemId.toString(), 1).singleOrNull()?.name ?: "System $systemId"
            }
        }
    }

    val presentation = MarkerManagerPresentationBuilder.build(
        state = markerState,
        systemNamesById = systemNamesById,
        query = query,
        selectedSystemId = selectedSystemId,
    )
    LaunchedEffect(presentation.selectedRow, selectedSystemId) {
        if (selectedSystemId != null && presentation.selectedRow == null) selectedSystemId = null
    }

    LaunchedEffect(editor, editorSystemQuery, editorSelectedSystem) {
        if (editor?.systemId != null || editorSystemQuery.isBlank() ||
            editorSelectedSystem?.name == editorSystemQuery
        ) {
            editorSystemResults = emptyList()
            return@LaunchedEffect
        }
        delay(SYSTEM_SEARCH_DEBOUNCE_MILLIS)
        editorSystemResults = withContext(Dispatchers.IO) {
            searchRepository.searchSystems(editorSystemQuery, 20)
        }
    }

    LaunchedEffect(markerState.markersBySystemId, markerState.busySystemIds, markerState.operationError) {
        val systemId = pendingEditorSystemId
        val draft = expectedDraft
        if (systemId != null && draft != null && systemId !in markerState.busySystemIds &&
            markerState.operationError == null && markerState.markersBySystemId[systemId]?.toDraft() == draft
        ) {
            editor = null
            expectedDraft = null
            pendingEditorSystemId = null
            editorLocalError = null
        }
        val deleting = pendingDelete
        if (deleting != null && deleteStarted && deleting.systemId !in markerState.busySystemIds &&
            markerState.markersBySystemId[deleting.systemId] == null
        ) {
            pendingDelete = null
            deleteStarted = false
            selectedSystemId = null
        }
    }

    val showOnMap: (Int) -> Unit = { systemId ->
        showSavedMarkerOnMap(
            systemId = systemId,
            selectRow = { selectedSystemId = it },
            focusSystem = onShowOnMap,
        )
    }
    val dismissManager = {
        if (markerManagerCanClose(editor != null, pendingDelete != null)) onDismiss()
    }

    Window(
        onCloseRequest = dismissManager,
        title = "Marker Manager",
        state = rememberWindowState(width = 760.dp, height = 560.dp),
    ) {
        Surface(
            color = Color(0xFF15212D),
            contentColor = Color(0xFFD7E6F2),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().padding(18.dp),
            ) {
                Text("Saved Marker Manager", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search saved markers…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MarkerTableHeader()
                HorizontalDivider(color = Color(0xFF415466))
                when {
                    markerState.isLoading -> Text("Loading saved markers…", color = Color(0xFFAFC1D1))
                    markerState.databaseError != null -> Text(
                        markerState.databaseError,
                        color = MaterialTheme.colorScheme.error,
                    )
                    presentation.rows.isEmpty() -> Text(
                        if (query.isBlank()) "No saved markers." else "No saved markers match this search.",
                        color = Color(0xFFAFC1D1),
                    )
                    else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp)) {
                        items(presentation.rows, key = SavedMarkerRowPresentation::systemId) { row ->
                            MarkerTableRow(
                                row = row,
                                selected = row.systemId == presentation.selectedRow?.systemId,
                                onClick = { selectedSystemId = row.systemId },
                                onDoubleClick = { showOnMap(row.systemId) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF415466))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        enabled = markerState.canCreateMarkers,
                        onClick = {
                            markerViewModel.clearOperationError()
                            editor = MarkerEditorRequest(MarkerEditorMode.CREATE_SAVED, null, null)
                            editorSystemQuery = ""
                            editorSystemResults = emptyList()
                            editorSelectedSystem = null
                            editorLocalError = null
                        },
                    ) { Text("Add") }
                    TextButton(
                        enabled = presentation.selectionActionsEnabled,
                        onClick = {
                            val row = presentation.selectedRow ?: return@TextButton
                            val marker = markerState.markersBySystemId[row.systemId] ?: return@TextButton
                            markerViewModel.clearOperationError()
                            editor = MarkerEditorRequest(MarkerEditorMode.EDIT_SAVED, row.systemId, row.systemName, marker)
                            editorLocalError = null
                        },
                    ) { Text("Edit") }
                    TextButton(
                        enabled = presentation.selectionActionsEnabled,
                        onClick = {
                            pendingDelete = presentation.selectedRow
                            deleteStarted = false
                            markerViewModel.clearOperationError()
                        },
                    ) { Text("Delete") }
                    TextButton(
                        enabled = presentation.selectionActionsEnabled,
                        onClick = { presentation.selectedRow?.let { showOnMap(it.systemId) } },
                    ) { Text("Show on Map") }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismissManager) { Text("Close") }
                }
            }
        }
        editor?.let { request ->
            MarkerEditorDialog(
                request = request,
                isBusy = pendingEditorSystemId?.let { it in markerState.busySystemIds } == true,
                error = editorLocalError ?: markerState.operationError,
                children = request.systemId?.let { markerState.childrenByParentSystemId[it] }.orEmpty(),
                onAddChild = { type -> request.systemId?.let { markerViewModel.addChild(it, type) } },
                onRemoveChild = { childId -> request.systemId?.let { markerViewModel.removeChild(it, childId) } },
                saveEnabled = editorLocalError == null,
                systemSearch = if (request.systemId == null) {
                    MarkerEditorSystemSearch(editorSystemQuery, editorSystemResults, editorSelectedSystem)
                } else {
                    null
                },
                onSystemQueryChange = { value ->
                    editorSystemQuery = value
                    editorSelectedSystem = null
                    editorLocalError = null
                },
                onSystemSelected = { system ->
                    editorSelectedSystem = system
                    editorSystemQuery = system.name
                    editorSystemResults = emptyList()
                    editorLocalError = markerCreationConflict(markerState.markersBySystemId[system.id])
                },
                onSave = { systemId, draft ->
                    val conflict = if (request.mode == MarkerEditorMode.CREATE_SAVED) {
                        markerCreationConflict(markerState.markersBySystemId[systemId])
                    } else {
                        null
                    }
                    if (conflict != null) {
                        editorLocalError = conflict
                    } else {
                        val accepted = when (request.mode) {
                            MarkerEditorMode.CREATE_SAVED -> markerViewModel.createSaved(systemId, draft)
                            MarkerEditorMode.EDIT_SAVED -> markerViewModel.updateSaved(systemId, draft)
                            MarkerEditorMode.EDIT_TEMPORARY -> false
                        }
                        if (accepted) {
                            pendingEditorSystemId = systemId
                            expectedDraft = draft
                            editorLocalError = null
                        }
                    }
                },
                onDismiss = {
                    editor = null
                    expectedDraft = null
                    pendingEditorSystemId = null
                    editorLocalError = null
                    markerViewModel.clearOperationError()
                },
            )
        }

        pendingDelete?.let { row ->
            SavedMarkerDeleteConfirmationDialog(
                row = row,
                operationError = markerState.operationError,
                isBusy = row.systemId in markerState.busySystemIds,
                onRemove = { deleteStarted = markerViewModel.removeSaved(row.systemId) },
                onCancel = {
                    pendingDelete = null
                    deleteStarted = false
                    markerViewModel.clearOperationError()
                },
                onDismissRequest = {
                    if (!deleteStarted) pendingDelete = null
                },
            )
        }
    }
}

@Composable
internal fun SavedMarkerDeleteConfirmationDialog(
    row: SavedMarkerRowPresentation,
    operationError: String?,
    isBusy: Boolean,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Remove saved marker?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remove saved marker from ${row.systemName}?")
                operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isBusy,
                onClick = onRemove,
            ) { Text(if (isBusy) "Removing…" else "Remove") }
        },
        dismissButton = {
            TextButton(
                enabled = !isBusy,
                onClick = onCancel,
            ) { Text("Cancel") }
        },
    )
}

internal fun markerManagerCanClose(editorOpen: Boolean, deleteConfirmationOpen: Boolean): Boolean =
    !editorOpen && !deleteConfirmationOpen

@Composable
private fun MarkerTableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Text("System", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.2f))
        Text("Name", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.2f))
        Text("Color", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.7f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkerTableRow(
    row: SavedMarkerRowPresentation,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF29465C) else Color.Transparent)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(row.systemName, modifier = Modifier.weight(1.2f))
        Text(row.markerName.orEmpty(), modifier = Modifier.weight(1.2f))
        Text(row.color.name, color = markerColor(row.color), modifier = Modifier.weight(0.7f))
    }
}

private const val SYSTEM_SEARCH_DEBOUNCE_MILLIS = 180L

internal fun showSavedMarkerOnMap(
    systemId: Int,
    selectRow: (Int) -> Unit,
    focusSystem: (Int) -> Unit,
) {
    selectRow(systemId)
    focusSystem(systemId)
}
