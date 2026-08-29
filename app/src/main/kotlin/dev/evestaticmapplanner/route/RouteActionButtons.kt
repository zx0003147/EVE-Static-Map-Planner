package dev.evestaticmapplanner.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.featurepack.RouteActionKey
import dev.evestaticmapplanner.featurepack.RouteActionUiState

@Composable
internal fun RouteActionButtons(
    actions: List<RouteActionUiState>,
    snapshot: RouteSnapshot?,
    onInvoke: (RouteActionKey, RouteSnapshot) -> Unit,
) {
    if (snapshot == null) return
    val visible = actions.filter { snapshot.kind in it.supportedRouteKinds }
    if (visible.isEmpty()) return

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.forEach { action ->
                Button(
                    enabled = action.enabled,
                    onClick = { onInvoke(action.key, snapshot) },
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
