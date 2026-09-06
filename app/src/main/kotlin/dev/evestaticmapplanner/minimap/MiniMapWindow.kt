package dev.evestaticmapplanner.minimap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.preferences.MiniMapWindowBounds
import dev.evestaticmapplanner.preferences.MiniMapFollowMode
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveTheme
import dev.evestaticmapplanner.ui.EveWindowChrome
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(FlowPreview::class)
@Composable
fun MiniMapWindow(
    state: MiniMapUiState,
    viewModel: MiniMapViewModel,
    automaticFollowDiagnostic: String,
    onBindCurrentWindow: (Long) -> String,
    onClose: () -> Unit,
) {
    val bounds = state.preferences.windowBounds
    val windowState = rememberWindowState(
        position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp),
        size = DpSize(bounds.width.dp, bounds.height.dp),
    )
    LaunchedEffect(windowState) {
        snapshotFlow {
            val position = windowState.position
            if (position is WindowPosition.Absolute) {
                MiniMapWindowBounds(
                    position.x.value,
                    position.y.value,
                    windowState.size.width.value.coerceIn(280f, 2_000f),
                    windowState.size.height.value.coerceIn(240f, 2_000f),
                )
            } else null
        }.debounce(250).collect { updated ->
            if (updated != null && updated != viewModel.state.value.preferences.windowBounds) {
                viewModel.updatePreferences(
                    viewModel.state.value.preferences.copy(windowBounds = updated),
                    fit = false,
                )
            }
        }
    }
    Window(
        onCloseRequest = onClose,
        title = "EVE Mini-map",
        state = windowState,
        alwaysOnTop = true,
    ) {
        EveTheme {
            EveWindowChrome(window)
            Surface(Modifier.fillMaxSize(), color = EveColors.PrimarySurface) {
                Column(Modifier.fillMaxSize()) {
                    MiniMapControls(state, viewModel, automaticFollowDiagnostic, onBindCurrentWindow)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val diagnostic = state.diagnostic
                        if (diagnostic != null) {
                            Text(
                                diagnostic,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            MiniMapCanvas(state, viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMapControls(
    state: MiniMapUiState,
    viewModel: MiniMapViewModel,
    automaticFollowDiagnostic: String,
    onBindCurrentWindow: (Long) -> String,
) {
    var characterMenu by remember { mutableStateOf(false) }
    var bindMenu by remember { mutableStateOf(false) }
    var bindingFeedback by remember { mutableStateOf<String?>(null) }
    val available = state.characters.filter { it.trackingEnabled }
    val currentCount = available.count { it.locationStatus == TrackedCharacterLocationStatus.CURRENT }
    val staleCount = available.count { it.locationStatus == TrackedCharacterLocationStatus.STALE }
    val degradedCount = available.count { it.locationStatus == TrackedCharacterLocationStatus.DEGRADED }
    val unknownCount = available.count { it.locationStatus == TrackedCharacterLocationStatus.UNKNOWN }
    Column(Modifier.fillMaxWidth().background(EveColors.SecondarySurface).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Button(onClick = { characterMenu = true }) {
                    Text(state.followedCharacter?.characterName ?: "Select character")
                }
                DropdownMenu(expanded = characterMenu, onDismissRequest = { characterMenu = false }) {
                    available.forEach { character ->
                        DropdownMenuItem(
                            text = { Text(character.characterName) },
                            onClick = {
                                viewModel.setPinnedCharacter(character.characterId)
                                characterMenu = false
                            },
                        )
                    }
                }
            }
            TextButton(onClick = { viewModel.setFollowMode(MiniMapFollowMode.AUTO) }) {
                Text(if (state.preferences.followMode == MiniMapFollowMode.AUTO) "[AUTO]" else "AUTO")
            }
            Box {
                TextButton(onClick = { bindMenu = true }) { Text("Bind…") }
                DropdownMenu(expanded = bindMenu, onDismissRequest = { bindMenu = false }) {
                    available.forEach { character ->
                        DropdownMenuItem(
                            text = { Text(character.characterName) },
                            onClick = {
                                bindingFeedback = onBindCurrentWindow(character.characterId)
                                bindMenu = false
                            },
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (1..5).forEach { hops ->
                TextButton(onClick = { viewModel.setRange(hops) }) {
                    Text(if (hops == state.preferences.stargateHops) "[$hops]" else "$hops")
                }
            }
            TextButton(
                onClick = {
                    viewModel.updatePreferences(
                        state.preferences.copy(includeAnsiblexEdges = !state.preferences.includeAnsiblexEdges),
                    )
                },
            ) {
                Text(if (state.preferences.includeAnsiblexEdges) "[JB visual]" else "JB visual")
            }
        }
        if (state.preferences.followMode == MiniMapFollowMode.AUTO) {
            Text(
                automaticFollowDiagnostic,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        bindingFeedback?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        state.followedCharacter?.let { character ->
            Text(
                "${state.preferences.followMode} · ${character.characterName} · ${character.locationStatus.name} · " +
                    "${state.preferences.stargateHops} Stargate hops",
                style = MaterialTheme.typography.labelSmall,
                color = if (character.locationStatus == TrackedCharacterLocationStatus.CURRENT) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else MaterialTheme.colorScheme.tertiary,
            )
            Text(
                "Validated ${character.lastValidatedAt ?: "never"} · Error ${character.lastErrorCategory ?: "none"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Tracking ${available.size}/${state.characters.size} · current $currentCount · stale $staleCount · " +
                "degraded $degradedCount · unknown $unknownCount · Ansiblex is visual-only",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MiniMapCanvas(state: MiniMapUiState, viewModel: MiniMapViewModel) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        Modifier.fillMaxSize()
            .background(Color(0xFF081018))
            .onSizeChanged { viewModel.updateCanvasSize(MapSize(it.width.toDouble(), it.height.toDouble())) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    viewModel.panBy(MapPoint(dragAmount.x.toDouble(), dragAmount.y.toDouble()))
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                viewModel.zoomAt(
                    MapPoint(change.position.x.toDouble(), change.position.y.toDouble()),
                    -change.scrollDelta.y.toDouble(),
                )
            },
    ) {
        val slice = state.slice ?: return@Canvas
        val viewport = state.viewport ?: return@Canvas
        if (state.canvasSize.isEmpty) return@Canvas
        val transform = MapTransform(viewport, state.canvasSize)
        state.ansiblexVisualEdges.forEach { edge ->
            val first = transform.worldToScreen(edge.first)
            val second = transform.worldToScreen(edge.second)
            drawLine(
                color = Color(0xFFB56DFF),
                start = Offset(first.x.toFloat(), first.y.toFloat()),
                end = Offset(second.x.toFloat(), second.y.toFloat()),
                strokeWidth = 1.8f,
            )
        }
        slice.edges.forEach { edge ->
            val first = transform.worldToScreen(edge.first)
            val second = transform.worldToScreen(edge.second)
            drawLine(
                color = Color(0xFF345065),
                start = Offset(first.x.toFloat(), first.y.toFloat()),
                end = Offset(second.x.toFloat(), second.y.toFloat()),
                strokeWidth = 1.2f,
            )
        }
        slice.nodes.forEach { node ->
            val point = transform.worldToScreen(node.position)
            val center = Offset(point.x.toFloat(), point.y.toFloat())
            val isCenter = node.system.id == slice.centerSystemId
            drawCircle(if (isCenter) Color(0xFF54D7FF) else securityColor(node.system.securityStatus),
                radius = if (isCenter) 6f else 3.5f, center = center)
            val label = textMeasurer.measure(
                node.system.name,
                TextStyle(color = Color(0xFFDCE8EF), fontSize = 10.sp),
            )
            drawText(label, topLeft = Offset(center.x + 5f, center.y - label.size.height / 2f))
        }
        state.characterGroups.forEach { group ->
            val node = slice.nodes.firstOrNull { it.system.id == group.systemId } ?: return@forEach
            val point = transform.worldToScreen(node.position)
            val current = group.characters.any { it.locationStatus == TrackedCharacterLocationStatus.CURRENT }
            val color = if (group.containsFollowedCharacter) Color(0xFFFFD166) else Color(0xFFE783FF)
            drawCircle(
                color.copy(alpha = if (current) 1f else 0.45f),
                radius = if (group.containsFollowedCharacter) 10f else 7f,
                center = Offset(point.x.toFloat(), point.y.toFloat()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (group.containsFollowedCharacter) 3f else 2f),
            )
            group.characters.forEachIndexed { index, character ->
                val angle = (2.0 * PI * index / group.characters.size) - PI / 2.0
                val markerDistance = if (group.containsFollowedCharacter) 14f else 11f
                drawCircle(
                    color = color.copy(
                        alpha = if (character.locationStatus == TrackedCharacterLocationStatus.CURRENT) 1f else 0.4f,
                    ),
                    radius = 2.5f,
                    center = Offset(
                        point.x.toFloat() + cos(angle).toFloat() * markerDistance,
                        point.y.toFloat() + sin(angle).toFloat() * markerDistance,
                    ),
                )
            }
            if (group.characters.size > 1) {
                val count = textMeasurer.measure(
                    group.characters.size.toString(),
                    TextStyle(color = color, fontSize = 9.sp),
                )
                drawText(count, topLeft = Offset(point.x.toFloat() - count.size.width / 2f, point.y.toFloat() + 10f))
            }
        }
    }
}

private fun securityColor(security: Double): Color = when {
    security >= 0.5 -> Color(0xFF66D17A)
    security > 0.0 -> Color(0xFFFFC857)
    else -> Color(0xFFE05A5A)
}
