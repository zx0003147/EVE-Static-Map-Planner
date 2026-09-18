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
import dev.evestaticmapplanner.search.SearchSuggestionsPresentation
import dev.evestaticmapplanner.search.SystemSearchField
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.localization.WormholeMessage
import dev.evestaticmapplanner.localization.WormholeUiMessage
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDivider
import dev.evestaticmapplanner.ui.EveLazyColumn
import dev.evestaticmapplanner.ui.EvePanel
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface
import java.awt.Dimension

@Composable
fun WormholeManagerDialog(
    state: WormholeUiState,
    viewModel: WormholeViewModel,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current.wormhole
    var confirmClearAll by remember { mutableStateOf(false) }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = strings.managerTitle,
        state = rememberDialogState(
            width = WORMHOLE_MANAGER_DEFAULT_SIZE.width,
            height = WORMHOLE_MANAGER_DEFAULT_SIZE.height,
        ),
    ) {
        EveWindowChrome(window)
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
    EveWindowSurface(
        modifier = Modifier.fillMaxSize().testTag(WORMHOLE_MANAGER_ROOT_TEST_TAG),
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
    val appStrings = LocalAppStrings.current
    val strings = appStrings.wormhole
    val rows = remember(state.connections, state.systemNamesById, strings) {
        WormholePresentationBuilder.rows(state.connections, state.systemNamesById, strings)
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(strings.managerTitle, style = MaterialTheme.typography.titleLarge)
                Text(
                    strings.connectionCount(state.connections.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = EveColors.SecondaryText,
                )
                Text(
                    strings.sessionOnlyHelp,
                    style = MaterialTheme.typography.labelSmall,
                    color = EveColors.SecondaryText,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(appStrings.common.close) }
        }
        EveDivider()
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.width(WORMHOLE_MANAGER_FORM_WIDTH).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(strings.addWormhole, style = MaterialTheme.typography.titleMedium)
                SystemSearchField(
                    value = state.managerFromQuery,
                    label = strings.from,
                    results = state.managerFromResults,
                    onValueChange = viewModel::updateManagerFromQuery,
                    onSelect = viewModel::selectManagerFrom,
                    modifier = Modifier.fillMaxWidth(),
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
                SystemSearchField(
                    value = state.managerToQuery,
                    label = strings.to,
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
                ) { Text(strings.addWormhole) }
                state.managerMessage?.let {
                    Text(
                        it.resolve(appStrings),
                        color = if (it is WormholeUiMessage && it.id in setOf(
                                WormholeMessage.ADDED,
                                WormholeMessage.REMOVED,
                                WormholeMessage.CLEARED,
                            )
                        ) {
                            EveColors.Important
                        } else {
                            EveColors.Error
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.loadError?.let { Text(it.resolve(appStrings), color = EveColors.Error, style = MaterialTheme.typography.bodySmall) }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(strings.currentWormholes, style = MaterialTheme.typography.titleMedium)
                if (rows.isEmpty()) {
                    Text(
                        strings.noActiveConnections,
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = EveColors.SecondaryText,
                    )
                }
                EveLazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(rows, key = WormholeConnectionRow::id) { row ->
                        WormholeManagerConnectionRow(row) { viewModel.remove(row.id) }
                    }
                }
                EveDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onRequestClearAll,
                        enabled = rows.isNotEmpty(),
                        modifier = Modifier.testTag(WORMHOLE_MANAGER_CLEAR_ALL_TEST_TAG),
                    ) { Text(strings.clearAll) }
                }
            }
        }
    }
}

@Composable
private fun WormholeManagerConnectionRow(row: WormholeConnectionRow, onRemove: () -> Unit) {
    val common = LocalAppStrings.current.common
    EvePanel(secondary = true, bordered = false) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.canonicalLabel, modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text(common.remove) }
        }
    }
}

@Composable
internal fun WormholeClearAllConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.wormhole
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.clearAllTitle) },
        text = { Text(strings.clearAllMessage) },
        confirmButton = { Button(onClick = onConfirm) { Text(strings.clearAll) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appStrings.common.cancel) } },
    )
}

@Composable
fun CreateWormholeDialog(
    state: WormholeUiState,
    viewModel: WormholeViewModel,
    onCreated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.wormhole
    val origin = state.quickOrigin ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createWormhole) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.from, style = MaterialTheme.typography.labelMedium, color = EveColors.SecondaryText)
                Text(
                    origin.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(WORMHOLE_QUICK_ORIGIN_TEST_TAG),
                )
                SystemSearchField(
                    value = state.quickToQuery,
                    label = strings.to,
                    results = state.quickToResults,
                    onValueChange = viewModel::updateQuickToQuery,
                    onSelect = viewModel::selectQuickTo,
                    modifier = Modifier.fillMaxWidth(),
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
                state.quickMessage?.let { Text(it.resolve(appStrings), color = EveColors.Error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (viewModel.addFromQuickCreate() == CreateWormholeUiResult.CREATED) onCreated()
                },
                enabled = state.canAddFromQuickCreate,
                modifier = Modifier.testTag(WORMHOLE_QUICK_ADD_TEST_TAG),
            ) { Text(appStrings.common.add) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appStrings.common.cancel) } },
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
    val appStrings = LocalAppStrings.current
    val strings = appStrings.wormhole
    val rows = remember(systemId, state.connections, state.systemNamesById, strings) {
        WormholePresentationBuilder.rowsForSystem(systemId, state.connections, state.systemNamesById, strings)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.connectionsTitle(systemName)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = WormholeConnectionRow::id) { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.otherEndpointName(systemId), modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                if (viewModel.remove(row.id) && rows.size == 1) onDismiss()
                            },
                        ) { Text(appStrings.common.remove) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(appStrings.common.close) } },
    )
}

internal val WORMHOLE_MANAGER_DEFAULT_SIZE = DpSize(860.dp, 640.dp)
internal val WORMHOLE_MANAGER_MINIMUM_SIZE = DpSize(760.dp, 560.dp)
internal val WORMHOLE_MANAGER_FORM_WIDTH = 320.dp
internal val WORMHOLE_MANAGER_BACKGROUND = EveColors.PrimarySurface
internal const val WORMHOLE_MANAGER_ROOT_TEST_TAG = "wormhole-manager-root"
internal const val WORMHOLE_MANAGER_ADD_TEST_TAG = "wormhole-manager-add"
internal const val WORMHOLE_MANAGER_CLEAR_ALL_TEST_TAG = "wormhole-manager-clear-all"
internal const val WORMHOLE_QUICK_ADD_TEST_TAG = "wormhole-quick-add"
internal const val WORMHOLE_QUICK_ORIGIN_TEST_TAG = "wormhole-quick-origin"
