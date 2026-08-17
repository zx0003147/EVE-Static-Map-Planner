package dev.evestaticmapplanner.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.map.MapProjectionId
import java.nio.file.Path
import java.util.Locale

@Composable
fun StaticMapScreen(
    databasePath: Path,
    state: MapUiState,
    viewModel: MapViewModel,
) {
    Row(Modifier.fillMaxSize().background(Color(0xFF101923))) {
        FutureToolsPanel()
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ProjectionToolbar(state, viewModel)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> CenterMessage("Loading static universe…")
                    state.error != null -> CenterMessage("Unable to load map\n${state.error}\n\nDatabase: $databasePath")
                    state.scene != null && state.viewport != null -> StaticMapCanvas(
                        state = state,
                        onCanvasSizeChanged = viewModel::onCanvasSizeChanged,
                        onZoom = viewModel::zoomAt,
                        onPan = viewModel::panBy,
                        onHover = viewModel::hoverAt,
                        onHoverExit = viewModel::clearHover,
                        onSelect = viewModel::selectAt,
                        onContextMenu = viewModel::openContextMenuAt,
                        onContextSystemInfo = viewModel::selectContextMenuSystem,
                        onContextDismiss = viewModel::dismissContextMenu,
                        onFirstMapDisplayed = viewModel::onFirstMapDisplayed,
                    )
                    state.scene != null -> CanvasSizeProbe(viewModel::onCanvasSizeChanged)
                }
            }
            state.scene?.let { scene ->
                Text(
                    text = "${scene.projectionId.displayName}: ${scene.nodes.size} systems · ${scene.edges.size} stargate connections" +
                        if (scene.omittedSystemIds.isNotEmpty()) " · ${scene.omittedSystemIds.size} unavailable" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFAAB9C7),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF121D28)).padding(8.dp),
                )
            }
        }
        SystemInfoPanel(state)
    }
}

@Composable
private fun FutureToolsPanel() {
    Surface(color = Color(0xFF15212D), modifier = Modifier.width(190.dp).fillMaxHeight()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Future Tools", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = Color(0xFF314252))
            Text("System Search", color = Color(0xFF738394))
            Text("Normal Route", color = Color(0xFF738394))
            Text("Capital Route", color = Color(0xFF738394))
            Text("Jump Overlays", color = Color(0xFF738394))
            Spacer(Modifier.weight(1f))
            Text("Phase 3 · Static Map", style = MaterialTheme.typography.labelSmall, color = Color(0xFF728495))
        }
    }
}

@Composable
private fun ProjectionToolbar(state: MapUiState, viewModel: MapViewModel) {
    Surface(color = Color(0xFF121D28)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("Projection", style = MaterialTheme.typography.labelLarge)
            MapProjectionId.entries.forEach { projection ->
                if (projection == state.projectionId) {
                    Button(onClick = {}, enabled = false) { Text(projection.displayName) }
                } else {
                    TextButton(onClick = { viewModel.switchProjection(projection) }) { Text(projection.displayName) }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::fitMap, enabled = state.scene != null) { Text("Fit Map") }
        }
    }
}

@Composable
private fun SystemInfoPanel(state: MapUiState) {
    Surface(color = Color(0xFF15212D), modifier = Modifier.width(240.dp).fillMaxHeight()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("System Info", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = Color(0xFF314252))
            val details = state.selectedSystemDetails
            when {
                state.selectedSystemId == null -> Text("Select a solar system on the map.", color = Color(0xFF91A2B2))
                details == null -> Text("Loading system details…", color = Color(0xFF91A2B2))
                else -> {
                    InfoRow("Name", details.system.name)
                    InfoRow("System ID", details.system.id.toString())
                    InfoRow("Region", details.region.name)
                    InfoRow("Constellation", details.constellation.name)
                    InfoRow("Security", String.format(Locale.ROOT, "%.6f", details.system.securityStatus))
                    InfoRow("Stargate Count", details.stargateCount.toString())
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8395A6))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = Color(0xFFC5D4E0), modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun CanvasSizeProbe(onSizeChanged: (dev.evestaticmapplanner.core.map.MapSize) -> Unit) {
    Box(
        Modifier.fillMaxSize().then(
            Modifier.onSizeChanged {
                onSizeChanged(dev.evestaticmapplanner.core.map.MapSize(it.width.toDouble(), it.height.toDouble()))
            },
        ),
    )
}
