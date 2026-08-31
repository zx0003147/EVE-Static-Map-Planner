package dev.evestaticmapplanner.wormhole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.evestaticmapplanner.search.SearchSuggestionsPresentation
import dev.evestaticmapplanner.search.SystemSearchField
import java.awt.Dimension

@Composable
fun WormholeManagerDialog(
    state: WormholeUiState,
    viewModel: WormholeViewModel,
    onDismiss: () -> Unit,
) {
    var confirmClearAll by remember { mutableStateOf(false) }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Wormhole Manager",
        state = rememberDialogState(
            width = WORMHOLE_MANAGER_DEFAULT_SIZE.width,
            height = WORMHOLE_MANAGER_DEFAULT_SIZE.height,
        ),
    ) {
        val density = LocalDensity.current
        val minimumWidthPx = with(density) { WORMHOLE_MANAGER_MINIMUM_SIZE.width.roundToPx() }
        val minimumHeightPx = with(density) { WORMHOLE_MANAGER_MINIMUM_SIZE.height.roundToPx() }
        SideEffect { window.minimumSize = Dimension(minimumWidthPx, minimumHeightPx) }

        WormholeManagerRoot {
            WormholeManagerContent(
                state = state,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onRequestClearAll = { confirmClearAll = true },
            )
        }
        if (confirmClearAll) {
            WormholeClearAllConfirmationDialog(
                onConfirm = {
                    viewModel.clearAll()
                    confirmClearAll = false
                },
                onDismiss = { confirmClearAll = false },
            )
        }
    }
}

@Composable
internal fun WormholeManagerRoot(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize().testTag(WORMHOLE_MANAGER_ROOT_TEST_TAG),
        color = WORMHOLE_MANAGER_BACKGROUND,
        contentColor = Color(0xFFD7E6F2),
        tonalElevation = 8.dp,
        content = content,
    )
}

@Composable
internal fun WormholeManagerContent(
    state: WormholeUiState,
    viewModel: WormholeViewModel,
    onDismiss: () -> Unit,
    onRequestClearAll: () -> Unit,
) {
    val rows = remember(state.connections, state.systemNamesById) {
        WormholePresentationBuilder.rows(state.connections, state.systemNamesById)
    }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Wormhole Manager", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.connections.size} active session ${if (state.connections.size == 1) "connection" else "connections"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAB9C7),
                )
                Text(
                    "Wormholes exist only for the current application session.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8FA3B4),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Close") }
        }
        HorizontalDivider(color = Color(0xFF314252))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(
                Modifier.width(WORMHOLE_MANAGER_FORM_WIDTH).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Add Wormhole", style = MaterialTheme.typography.titleMedium)
                SystemSearchField(
                    value = state.managerFromQuery,
                    label = "From",
                    results = state.managerFromResults,
                    onValueChange = viewModel::updateManagerFromQuery,
                    onSelect = viewModel::selectManagerFrom,
                    modifier = Modifier.fillMaxWidth(),
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
                SystemSearchField(
                    value = state.managerToQuery,
                    label = "To",
                    results = state.managerToResults,
                    onValueChange = viewModel::updateManagerToQuery,
                    onSelect = viewModel::selectManagerTo,
                    modifier = Modifier.fillMaxWidth(),
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
                Button(
                    onClick = { viewModel.addFromManager() },
                    enabled = state.canAddFromManager && !state.isLoading,
                    modifier = Modifier.testTag(WORMHOLE_MANAGER_ADD_TEST_TAG),
                ) { Text("Add Wormhole") }
                state.managerMessage?.let {
                    Text(
                        it,
                        color = if (it == WORMHOLE_ADDED_MESSAGE || it == WORMHOLE_REMOVED_MESSAGE || it.startsWith("Cleared")) {
                            Color(0xFFBFE7F5)
                        } else {
                            Color(0xFFFF9F9F)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.loadError?.let { Text(it, color = Color(0xFFFF9F9F), style = MaterialTheme.typography.bodySmall) }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("Current Wormholes", style = MaterialTheme.typography.titleMedium)
                if (rows.isEmpty()) {
                    Text(
                        "No active Wormhole connections",
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = Color(0xFFAAB9C7),
                    )
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(rows, key = WormholeConnectionRow::id) { row ->
                        WormholeManagerConnectionRow(row) { viewModel.remove(row.id) }
                    }
                }
                HorizontalDivider(color = Color(0xFF314252))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onRequestClearAll,
                        enabled = rows.isNotEmpty(),
                        modifier = Modifier.testTag(WORMHOLE_MANAGER_CLEAR_ALL_TEST_TAG),
                    ) { Text("Clear All") }
                }
            }
        }
    }
}

@Composable
private fun WormholeManagerConnectionRow(row: WormholeConnectionRow, onRemove: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.canonicalLabel, modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
internal fun WormholeClearAllConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear all Wormholes?") },
        text = { Text("This will remove all temporary Wormhole connections for the current application session.") },
        confirmButton = { Button(onClick = onConfirm) { Text("Clear All") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CreateWormholeDialog(
    state: WormholeUiState,
    viewModel: WormholeViewModel,
    onCreated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val origin = state.quickOrigin ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Wormhole") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("From", style = MaterialTheme.typography.labelMedium, color = Color(0xFFAAB9C7))
                Text(
                    origin.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(WORMHOLE_QUICK_ORIGIN_TEST_TAG),
                )
                SystemSearchField(
                    value = state.quickToQuery,
                    label = "To",
                    results = state.quickToResults,
                    onValueChange = viewModel::updateQuickToQuery,
                    onSelect = viewModel::selectQuickTo,
                    modifier = Modifier.fillMaxWidth(),
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
                state.quickMessage?.let { Text(it, color = Color(0xFFFF9F9F), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (viewModel.addFromQuickCreate() == CreateWormholeUiResult.CREATED) onCreated()
                },
                enabled = state.canAddFromQuickCreate,
                modifier = Modifier.testTag(WORMHOLE_QUICK_ADD_TEST_TAG),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun WormholeConnectionsDialog(
    systemId: Int,
    systemName: String,
    state: WormholeUiState,
    viewModel: WormholeViewModel,
    onDismiss: () -> Unit,
) {
    val rows = remember(systemId, state.connections, state.systemNamesById) {
        WormholePresentationBuilder.rowsForSystem(systemId, state.connections, state.systemNamesById)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wormhole Connections — $systemName") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = WormholeConnectionRow::id) { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.otherEndpointName(systemId), modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                if (viewModel.remove(row.id) && rows.size == 1) onDismiss()
                            },
                        ) { Text("Remove") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal val WORMHOLE_MANAGER_DEFAULT_SIZE = DpSize(860.dp, 640.dp)
internal val WORMHOLE_MANAGER_MINIMUM_SIZE = DpSize(760.dp, 560.dp)
internal val WORMHOLE_MANAGER_FORM_WIDTH = 320.dp
internal val WORMHOLE_MANAGER_BACKGROUND = Color(0xFF15212D)
internal const val WORMHOLE_MANAGER_ROOT_TEST_TAG = "wormhole-manager-root"
internal const val WORMHOLE_MANAGER_ADD_TEST_TAG = "wormhole-manager-add"
internal const val WORMHOLE_MANAGER_CLEAR_ALL_TEST_TAG = "wormhole-manager-clear-all"
internal const val WORMHOLE_QUICK_ADD_TEST_TAG = "wormhole-quick-add"
internal const val WORMHOLE_QUICK_ORIGIN_TEST_TAG = "wormhole-quick-origin"
