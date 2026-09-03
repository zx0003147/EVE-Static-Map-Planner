package dev.evestaticmapplanner.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.NavigationSnapshot
import dev.evestaticmapplanner.feature.api.RouteActionTargetId
import dev.evestaticmapplanner.feature.api.RouteActionTargetSnapshot
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.featurepack.RouteActionKey
import dev.evestaticmapplanner.featurepack.RouteActionUiState

@Composable
internal fun RouteActionButtons(
    actions: List<RouteActionUiState>,
    snapshot: RouteSnapshot?,
    selectedTargetIds: Map<String, String> = emptyMap(),
    onSelectTarget: (String, String?) -> Unit = { _, _ -> },
    onInvoke: (RouteActionKey, RouteSnapshot, RouteActionTargetId?) -> Unit,
) = NavigationRouteActionButtons(
    actions = actions,
    snapshot = snapshot,
    navigationSnapshot = null,
    selectedTargetIds = selectedTargetIds,
    onSelectTarget = onSelectTarget,
    onInvoke = onInvoke,
    onInvokeNavigation = { _, _, _ -> },
)

@Composable
internal fun NavigationRouteActionButtons(
    actions: List<RouteActionUiState>,
    snapshot: RouteSnapshot?,
    navigationSnapshot: NavigationSnapshot?,
    selectedTargetIds: Map<String, String>,
    onSelectTarget: (String, String?) -> Unit,
    onInvoke: (RouteActionKey, RouteSnapshot, RouteActionTargetId?) -> Unit,
    onInvokeNavigation: (RouteActionKey, NavigationSnapshot, RouteActionTargetId?) -> Unit,
) {
    val routeKind = navigationSnapshot?.kind ?: snapshot?.kind ?: return
    val visible = actions.filter { routeKind in it.supportedRouteKinds }
    if (visible.isEmpty()) return

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Draft only — EVE changes only after you press a button below.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAAB9C7),
        )
        visible.mapNotNull { action ->
            action.targetSelector?.let { selector -> routeActionTargetSelectionKey(action) to selector }
        }.distinctBy { it.first }.forEach { (selectorKey, selector) ->
            RouteActionTargetSelector(
                selector = selector,
                selectedTargetId = selectedTargetIds[selectorKey],
                onSelect = { onSelectTarget(selectorKey, it) },
            )
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.forEach { action ->
                val selectedTarget = action.targetSelector?.let { selector ->
                    selectedTargetIds[routeActionTargetSelectionKey(action)]?.let(::RouteActionTargetId)
                        ?.takeIf { selected -> selector.options.any { it.id == selected && it.available } }
                }
                Button(
                    enabled = !action.busy &&
                        (action.targetSelector == null || selectedTarget != null) &&
                        (if (action.supportsNavigationIntent) navigationSnapshot != null else snapshot != null),
                    onClick = {
                        if (action.supportsNavigationIntent) {
                            navigationSnapshot?.let { onInvokeNavigation(action.key, it, selectedTarget) }
                        } else {
                            snapshot?.let { onInvoke(action.key, it, selectedTarget) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (action.busy) "${action.label}…" else action.label)
                }
            }
        }
        visible.filter { it.lastStatus != null }.forEach { action ->
            val status = checkNotNull(action.lastStatus)
            Text(
                buildString {
                    append(
                        when (status) {
                            RouteActionStatus.SUCCEEDED -> "Succeeded"
                            RouteActionStatus.REJECTED -> "Rejected"
                            RouteActionStatus.FAILED -> "Failed"
                        },
                    )
                    action.lastMessage?.let { append(": ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (status) {
                    RouteActionStatus.SUCCEEDED -> Color(0xFF9FE3B1)
                    RouteActionStatus.REJECTED -> Color(0xFFFFD166)
                    RouteActionStatus.FAILED -> Color(0xFFFF8A80)
                },
            )
        }
    }
}

internal fun routeActionTargetSelectionKey(action: RouteActionUiState): String {
    val selectorId = checkNotNull(action.targetSelector?.selectorId)
    return "${action.key.packId.value}:$selectorId"
}

@Composable
private fun RouteActionTargetSelector(
    selector: RouteActionTargetSnapshot,
    selectedTargetId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember(selector.selectorId) { mutableStateOf(false) }
    val selected = selector.options.firstOrNull { it.id.value == selectedTargetId }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(selector.label, style = MaterialTheme.typography.labelMedium, color = Color(0xFFD7E6F2))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = selector.options.any { it.available },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        selected != null && selected.available -> selected.label
                        selected != null -> "${selected.label} (unavailable)"
                        selectedTargetId != null -> "$selectedTargetId (disconnected / unavailable)"
                        else -> "Select…"
                    },
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                selector.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(if (option.available) option.label else "${option.label} (unavailable)") },
                        enabled = option.available,
                        onClick = {
                            expanded = false
                            onSelect(option.id.value)
                        },
                    )
                }
            }
        }
    }
}
