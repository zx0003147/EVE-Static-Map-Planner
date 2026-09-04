package dev.evestaticmapplanner.shared

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.map.sharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDivider as HorizontalDivider
import dev.evestaticmapplanner.ui.EveLazyColumn
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SharedMarkerManagerWindow(
    state: SharedMapState,
    mutation: SharedMarkerMutationUiState,
    viewModel: SharedMapViewModel,
    searchRepository: SystemSearchRepository,
    onFocusSystem: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val systemIds = state.snapshot?.markers?.values.orEmpty().map { it.systemId }.distinct().sorted()
    var systemNamesById by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SharedMarkerManagerSort.SYSTEM) }
    var selectedMarkerId by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<SharedMarkerEditorRequest?>(null) }
    var pendingDelete by remember { mutableStateOf<SharedMarkerManagerRow?>(null) }
    var pendingDeleteOperation by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(systemIds) {
        systemNamesById = withContext(Dispatchers.IO) {
            systemIds.mapNotNull { systemId ->
                searchRepository.searchSystems(systemId.toString(), 1)
                    .singleOrNull { it.id == systemId }
                    ?.name
                    ?.let { systemId to it }
            }.toMap()
        }
    }
    LaunchedEffect(state.selectedWorkspaceId) {
        selectedMarkerId = null
        editor = null
        pendingDelete = null
        pendingDeleteOperation = null
        viewModel.clearMarkerMutationFeedback()
    }

    val presentation = SharedMarkerManagerPresentationBuilder.build(
        state,
        systemNamesById,
        query,
        sort,
        selectedMarkerId,
    )
    LaunchedEffect(presentation.selected, selectedMarkerId) {
        if (selectedMarkerId != null && presentation.selected == null) selectedMarkerId = null
    }
    LaunchedEffect(mutation.completion, pendingDeleteOperation) {
        if (pendingDeleteOperation != null && mutation.completion?.operationId == pendingDeleteOperation) {
            pendingDelete = null
            pendingDeleteOperation = null
            selectedMarkerId = null
        }
    }

    Window(
        onCloseRequest = { if (!mutation.busy) onDismiss() },
        title = "Shared Marker Manager",
        state = rememberWindowState(width = 1_050.dp, height = 620.dp),
    ) {
        EveWindowChrome(window)
        EveWindowSurface(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text("Shared Marker Manager", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Workspace: ${state.selectedWorkspace?.name ?: "None"}")
                    Text("Status: ${managerConnectionLabel(state)}")
                    Text("Role: ${state.identity?.workspace?.role?.name ?: "—"}")
                    Text("Markers: ${state.markerCount}")
                }
                if (state.connectionState != dev.evestaticmapplanner.shared.model.SharedConnectionState.ONLINE) {
                    Text(
                        when {
                            state.connectionState == dev.evestaticmapplanner.shared.model.SharedConnectionState.FORBIDDEN ->
                                "Shared Map access was removed."
                            state.stale -> "Showing stale Shared Marker data; editing is disabled."
                            else -> "Shared Map is read-only until the connection is online."
                        },
                        color = EveColors.Warning,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search system, marker, or tag") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { sort = SharedMarkerManagerSort.SYSTEM }, selected = sort == SharedMarkerManagerSort.SYSTEM) { Text("System") }
                    TextButton(onClick = { sort = SharedMarkerManagerSort.UPDATED }, selected = sort == SharedMarkerManagerSort.UPDATED) { Text("Updated") }
                    TextButton(onClick = { sort = SharedMarkerManagerSort.NAME }, selected = sort == SharedMarkerManagerSort.NAME) { Text("Name") }
                    TextButton(onClick = viewModel::refreshNow) { Text("Refresh") }
                }
                SharedMarkerTableHeader()
                HorizontalDivider()
                when {
                    state.snapshot == null -> Text("No Shared Marker snapshot is available.", color = EveColors.SecondaryText)
                    presentation.rows.isEmpty() -> Text(
                        if (query.isBlank()) "No Shared Markers in this Workspace." else "No Shared Markers match this search.",
                        color = EveColors.SecondaryText,
                    )
                    else -> EveLazyColumn(Modifier.weight(1f).fillMaxWidth().heightIn(min = 160.dp)) {
                        items(presentation.rows, key = SharedMarkerManagerRow::markerId) { row ->
                            SharedMarkerTableRow(
                                row,
                                selected = row.markerId == presentation.selected?.markerId,
                                onClick = { selectedMarkerId = row.markerId },
                                onDoubleClick = { if (row.systemKnown) onFocusSystem(row.systemId) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = presentation.selected?.systemKnown == true,
                        onClick = { presentation.selected?.let { onFocusSystem(it.systemId) } },
                    ) { Text("Focus") }
                    TextButton(
                        enabled = presentation.selected != null,
                        onClick = {
                            val row = presentation.selected ?: return@TextButton
                            val marker = state.snapshot?.markers?.get(row.markerId) ?: return@TextButton
                            val workspaceId = state.selectedWorkspaceId ?: return@TextButton
                            viewModel.clearMarkerMutationFeedback()
                            editor = SharedMarkerEditorRequest(
                                workspaceId,
                                if (presentation.canWrite) SharedMarkerEditorMode.EDIT else SharedMarkerEditorMode.VIEW,
                                row.systemId,
                                row.systemName,
                                marker,
                            )
                        },
                    ) { Text(if (presentation.canWrite) "Edit" else "View") }
                    TextButton(
                        enabled = presentation.canWrite && presentation.selected != null && !mutation.busy,
                        onClick = { pendingDelete = presentation.selected },
                    ) { Text("Delete") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !mutation.busy) { Text("Close") }
                }
            }
        }

        editor?.let { request ->
            val currentMarker = request.marker?.markerId?.let { state.snapshot?.markers?.get(it) }
            SharedMarkerEditorDialog(
                request,
                currentMarker,
                canWriteSharedMarkers(state),
                mutation,
                viewModel::createSharedMarker,
                viewModel::updateSharedMarker,
                viewModel::deleteSharedMarker,
                viewModel::clearMarkerMutationFeedback,
                onDismiss = {
                    editor = null
                    viewModel.clearMarkerMutationFeedback()
                },
            )
        }

        pendingDelete?.let { row ->
            val operationMatches = pendingDeleteOperation != null && mutation.operationId == pendingDeleteOperation
            AlertDialog(
                onDismissRequest = { if (!mutation.busy) pendingDelete = null },
                title = { Text("Delete Shared Marker?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Delete shared marker “${row.name}” from ${row.systemName}?")
                        mutation.error.takeIf { operationMatches }?.let {
                            Text(sharedMapErrorMessage(it), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !mutation.busy && canWriteSharedMarkers(state),
                        onClick = {
                            viewModel.clearMarkerMutationFeedback()
                            pendingDeleteOperation = viewModel.deleteSharedMarker(row.markerId, row.version)
                        },
                    ) { Text(if (mutation.busy && operationMatches) "Deleting…" else "Delete") }
                },
                dismissButton = {
                    TextButton(enabled = !mutation.busy, onClick = { pendingDelete = null }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun SharedMarkerTableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("System", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(150.dp))
        Text("Name", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(180.dp))
        Text("Color", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(75.dp))
        Text("Tags", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Updated by", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp))
        Text("Updated", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(150.dp))
    }
}

@Composable
private fun SharedMarkerTableRow(
    row: SharedMarkerManagerRow,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
            .background(
                when {
                    selected -> EveColors.SelectedSurface
                    hovered -> EveColors.HoverSurface
                    else -> EveColors.PrimarySurface
                },
            )
            .hoverable(interactionSource)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(row.systemName, modifier = Modifier.width(150.dp), maxLines = 1)
        Text(row.name, modifier = Modifier.width(180.dp), maxLines = 1)
        Text(row.color.name, color = sharedMarkerColor(row.color), modifier = Modifier.width(75.dp))
        Text(row.tags.joinToString(" · "), modifier = Modifier.weight(1f), maxLines = 1)
        Text(row.updatedBy, modifier = Modifier.width(120.dp), maxLines = 1)
        Text(MANAGER_TIME_FORMATTER.format(row.updatedAt.atZone(ZoneId.systemDefault())), modifier = Modifier.width(150.dp))
    }
}

private fun managerConnectionLabel(state: SharedMapState): String = when {
    state.stale -> "${state.connectionState.name} · stale"
    else -> state.connectionState.name
}

private val MANAGER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
