package dev.evestaticmapplanner.minimap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedRouteLeg
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.map.QuadraticMapConnectionGeometry
import dev.evestaticmapplanner.map.StraightMapConnectionGeometry
import dev.evestaticmapplanner.map.activeRouteConnectionGeometry
import dev.evestaticmapplanner.map.routeLegRenderStyle
import dev.evestaticmapplanner.preferences.MiniMapFollowMode
import dev.evestaticmapplanner.preferences.MiniMapInteractionMode
import dev.evestaticmapplanner.preferences.MiniMapWindowBounds
import dev.evestaticmapplanner.platform.windows.minimaphud.WindowsMiniMapWindowStyles
import dev.evestaticmapplanner.ui.CharacterPortraitSegment
import dev.evestaticmapplanner.ui.CharacterPortraitStack
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDivider
import dev.evestaticmapplanner.ui.EveDropdownMenu
import dev.evestaticmapplanner.ui.EveDropdownMenuItem
import dev.evestaticmapplanner.ui.EvePanel
import dev.evestaticmapplanner.ui.EveTextButton
import dev.evestaticmapplanner.ui.EveTheme
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface
import dev.evestaticmapplanner.ui.drawCharacterPortraitDisc
import dev.evestaticmapplanner.ui.presentCharacterPortraits
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import com.sun.jna.Platform
import java.awt.MouseInfo
import java.awt.Window as AwtWindow

@OptIn(FlowPreview::class)
@Composable
internal fun MiniMapWindow(
    state: MiniMapUiState,
    viewModel: MiniMapViewModel,
    automaticFollowDiagnostic: String,
    onBindCurrentWindow: (Long) -> String,
    hudRuntimeState: MiniMapHudRuntimeState,
    onNativeWindowFailure: (Throwable) -> Unit,
    onClose: () -> Unit,
) {
    val bounds = state.preferences.windowBounds
    val initialBounds = remember {
        MiniMapWindowPlacement.recover(bounds, AwtMiniMapWorkAreaProvider.workAreas())
    }
    val windowState = rememberWindowState(
        position = WindowPosition.Absolute(initialBounds.x.dp, initialBounds.y.dp),
        size = DpSize(initialBounds.width.dp, initialBounds.height.dp),
    )
    LaunchedEffect(initialBounds) {
        if (initialBounds != viewModel.state.value.preferences.windowBounds) {
            viewModel.updatePreferences(
                viewModel.state.value.preferences.copy(windowBounds = initialBounds),
                fit = false,
            )
        }
    }
    LaunchedEffect(windowState, state.preferences.snapToScreenEdges) {
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
            val placed = updated?.let {
                MiniMapWindowPlacement.afterMove(
                    bounds = it,
                    workAreas = AwtMiniMapWorkAreaProvider.workAreas(),
                    snapEnabled = viewModel.state.value.preferences.snapToScreenEdges,
                )
            }
            if (placed != null && placed != updated) {
                windowState.position = WindowPosition.Absolute(placed.x.dp, placed.y.dp)
            }
            if (placed != null && placed != viewModel.state.value.preferences.windowBounds) {
                viewModel.updatePreferences(
                    viewModel.state.value.preferences.copy(windowBounds = placed),
                    fit = false,
                )
            }
        }
    }
    val behavior = resolveMiniMapWindowBehavior(state.preferences, hudRuntimeState)
    val interactionMode = behavior.interactionMode
    val hud = behavior.hudPresentation
    val locked = behavior.clickThrough
    key(hud) {
        Window(
            onCloseRequest = onClose,
            title = "EVE Mini-map",
            state = windowState,
            alwaysOnTop = true,
            undecorated = hud,
            transparent = hud,
            focusable = behavior.focusable,
        ) {
            var nativeStyleFailed by remember(window) { mutableStateOf(false) }
            SideEffect {
                if (Platform.isWindows() && !nativeStyleFailed) {
                    WindowsMiniMapWindowStyles.apply(window, clickThrough = behavior.clickThrough).onFailure { failure ->
                        nativeStyleFailed = true
                        onNativeWindowFailure(failure)
                    }
                }
            }
            DisposableEffect(window) {
                onDispose { if (Platform.isWindows()) WindowsMiniMapWindowStyles.restore(window) }
            }
            EveTheme {
                if (!hud) EveWindowChrome(window)
                val dragModifier = if (hud && !locked) {
                    Modifier.moveAwtWindow(window)
                } else {
                    Modifier
                }
                val content: @Composable () -> Unit = {
                    Box(Modifier.fillMaxSize()) {
                        MiniMapContent(
                            state,
                            viewModel,
                            automaticFollowDiagnostic,
                            hudPresentation = hud,
                            hudOpacity = state.preferences.hudOpacity,
                            interactionMode = interactionMode,
                            headerModifier = dragModifier,
                            onBindCurrentWindow = onBindCurrentWindow,
                        )
                        if (hud && !locked) {
                            MiniMapResizeGrip(window, Modifier.align(Alignment.BottomEnd))
                        }
                    }
                }
                if (hud) content() else EveWindowSurface(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

@Composable
internal fun MiniMapContent(
    state: MiniMapUiState,
    viewModel: MiniMapViewModel,
    automaticFollowDiagnostic: String,
    hudPresentation: Boolean = false,
    hudOpacity: Float = 1f,
    interactionMode: MiniMapInteractionMode = MiniMapInteractionMode.INTERACTIVE,
    headerModifier: Modifier = Modifier,
    onBindCurrentWindow: (Long) -> String,
) {
    var characterMenuExpanded by remember { mutableStateOf(false) }
    var followModeMenuExpanded by remember { mutableStateOf(false) }
    var optionsMenuExpanded by remember { mutableStateOf(false) }
    var bindMenuExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var bindingFeedback by remember { mutableStateOf<String?>(null) }
    val available = state.characters.filter { it.trackingEnabled }

    val backgroundAlpha = if (hudPresentation) hudOpacity else 1f
    val locked = interactionMode == MiniMapInteractionMode.HUD_LOCKED
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(EveColors.SecondarySurface.copy(alpha = backgroundAlpha))
                .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hudPresentation && !locked) {
                Text(
                    "⋮",
                    modifier = headerModifier.width(12.dp),
                    color = EveColors.SecondaryText,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Box(Modifier.weight(1f)) {
                EveTextButton(
                    onClick = { characterMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    CharacterPortraitAvatar(state.followedCharacter)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.followedCharacter?.characterName ?: "Select character",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            miniMapLocationLine(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = miniMapStatusColor(state.followedCharacter?.locationStatus),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                EveDropdownMenu(
                    expanded = characterMenuExpanded && !locked,
                    onDismissRequest = { characterMenuExpanded = false },
                ) {
                    if (available.isEmpty()) {
                        EveDropdownMenuItem(
                            text = { Text("No tracked characters") },
                            enabled = false,
                            onClick = {},
                        )
                    } else {
                        available.forEach { character ->
                            EveDropdownMenuItem(
                                text = { Text(character.characterName) },
                                onClick = {
                                    viewModel.setPinnedCharacter(character.characterId)
                                    characterMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            if (!locked) Box {
                EveTextButton(
                    onClick = { followModeMenuExpanded = true },
                    selected = true,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(state.preferences.followMode.name, style = MaterialTheme.typography.labelMedium)
                }
                EveDropdownMenu(
                    expanded = followModeMenuExpanded,
                    onDismissRequest = { followModeMenuExpanded = false },
                ) {
                    EveDropdownMenuItem(
                        text = { Text("AUTO — Follow foreground EVE client") },
                        onClick = {
                            viewModel.setFollowMode(MiniMapFollowMode.AUTO)
                            followModeMenuExpanded = false
                        },
                    )
                    EveDropdownMenuItem(
                        text = { Text("PINNED — Keep current character") },
                        enabled = state.followedCharacter != null,
                        onClick = {
                            state.followedCharacter?.let { viewModel.setPinnedCharacter(it.characterId) }
                            followModeMenuExpanded = false
                        },
                    )
                }
            } else {
                Text(
                    state.preferences.followMode.name,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = EveColors.PrimaryAccent,
                )
            }
            if (!locked) Box {
                EveTextButton(
                    onClick = { optionsMenuExpanded = true },
                    modifier = Modifier.semantics { contentDescription = "Mini-map options" },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("•••", style = MaterialTheme.typography.labelLarge)
                }
                EveDropdownMenu(
                    expanded = optionsMenuExpanded,
                    onDismissRequest = { optionsMenuExpanded = false },
                ) {
                    EveDropdownMenuItem(
                        text = { Text(if (diagnosticsExpanded) "Hide diagnostics" else "Show diagnostics") },
                        onClick = {
                            diagnosticsExpanded = !diagnosticsExpanded
                            optionsMenuExpanded = false
                        },
                    )
                    EveDivider()
                    EveDropdownMenuItem(
                        text = { Text("Bind current EVE client…") },
                        enabled = available.isNotEmpty(),
                        onClick = {
                            optionsMenuExpanded = false
                            bindMenuExpanded = true
                        },
                    )
                }
                EveDropdownMenu(
                    expanded = bindMenuExpanded,
                    onDismissRequest = { bindMenuExpanded = false },
                ) {
                    Text(
                        "Temporarily bind this EVE client to:",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = EveColors.SecondaryText,
                    )
                    available.forEach { character ->
                        EveDropdownMenuItem(
                            text = { Text(character.characterName) },
                            onClick = {
                                bindingFeedback = onBindCurrentWindow(character.characterId)
                                bindMenuExpanded = false
                                diagnosticsExpanded = true
                            },
                        )
                    }
                }
            }
        }
        EveDivider()
        if (diagnosticsExpanded && !locked) {
            MiniMapDiagnostics(state, automaticFollowDiagnostic, bindingFeedback)
            EveDivider()
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(EveColors.PrimarySurface.copy(alpha = backgroundAlpha)),
        ) {
            val diagnostic = state.diagnostic
            if (diagnostic != null) {
                EvePanel(
                    modifier = Modifier.align(Alignment.Center).padding(18.dp),
                    secondary = true,
                ) {
                    Text(
                        diagnostic,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = EveColors.SecondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                MiniMapCanvas(state, viewModel)
            }
        }
    }
}

@Composable
private fun MiniMapResizeGrip(
    window: AwtWindow,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier.size(18.dp).resizeAwtWindow(window),
    ) {
        val color = EveColors.SecondaryText.copy(alpha = 0.7f)
        drawLine(color, Offset(size.width * 0.45f, size.height), Offset(size.width, size.height * 0.45f), 1f)
        drawLine(color, Offset(size.width * 0.7f, size.height), Offset(size.width, size.height * 0.7f), 1f)
    }
}

private fun Modifier.moveAwtWindow(window: AwtWindow): Modifier = pointerInput(window) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerAtStart = currentAwtPointerLocation() ?: return@awaitEachGesture
        val session = MiniMapWindowDragSession(
            pointerAtStart = pointerAtStart,
            windowAtStart = MiniMapScreenPoint(window.x, window.y),
        )
        drag(down.id) { change ->
            change.consume()
            currentAwtPointerLocation()?.let(session::positionAt)?.let { position ->
                window.setLocation(position.x, position.y)
            }
        }
    }
}

private fun Modifier.resizeAwtWindow(window: AwtWindow): Modifier = pointerInput(window) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerAtStart = currentAwtPointerLocation() ?: return@awaitEachGesture
        val session = MiniMapWindowResizeSession(
            pointerAtStart = pointerAtStart,
            sizeAtStart = MiniMapWindowPixelSize(window.width, window.height),
        )
        drag(down.id) { change ->
            change.consume()
            currentAwtPointerLocation()?.let { pointer ->
                session.sizeAt(pointer, MINI_MAP_MINIMUM_SIZE, MINI_MAP_MAXIMUM_SIZE)
            }?.let { size ->
                window.setSize(size.width, size.height)
            }
        }
    }
}

private fun currentAwtPointerLocation(): MiniMapScreenPoint? = runCatching {
    MouseInfo.getPointerInfo()?.location?.let { MiniMapScreenPoint(it.x, it.y) }
}.getOrNull()

private val MINI_MAP_MINIMUM_SIZE = MiniMapWindowPixelSize(width = 280, height = 240)
private val MINI_MAP_MAXIMUM_SIZE = MiniMapWindowPixelSize(width = 2_000, height = 2_000)

@Composable
private fun CharacterPortraitAvatar(character: TrackedCharacterSnapshot?) {
    val textMeasurer = rememberTextMeasurer()
    val stack = remember(character) {
        if (character == null) {
            CharacterPortraitStack(listOf(CharacterPortraitSegment(null, "?")), 0)
        } else {
            presentCharacterPortraits(listOf(character))
        }
    }
    Canvas(
        Modifier.size(38.dp).semantics {
            contentDescription = "${character?.characterName ?: "Unknown character"} portrait"
        },
    ) {
        drawCharacterPortraitDisc(
            stack = stack,
            center = center,
            radius = size.minDimension / 2f - 2f,
            textMeasurer = textMeasurer,
            borderColor = miniMapStatusColor(character?.locationStatus),
            alpha = if (character?.locationStatus == TrackedCharacterLocationStatus.STALE) 0.7f else 1f,
        )
    }
}

@Composable
private fun MiniMapDiagnostics(
    state: MiniMapUiState,
    automaticFollowDiagnostic: String,
    bindingFeedback: String?,
) {
    val available = state.characters.filter { it.trackingEnabled }
    val counts = TrackedCharacterLocationStatus.entries.associateWith { status ->
        available.count { it.locationStatus == status }
    }
    EvePanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        secondary = true,
    ) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.labelMedium, color = EveColors.PrimaryAccent)
            if (state.preferences.followMode == MiniMapFollowMode.AUTO) {
                Text(automaticFollowDiagnostic, style = MaterialTheme.typography.labelSmall, color = EveColors.SecondaryText)
            }
            Text(
                "Tracking ${available.size}/${state.characters.size} · current ${counts.getValue(TrackedCharacterLocationStatus.CURRENT)} · " +
                    "stale ${counts.getValue(TrackedCharacterLocationStatus.STALE)} · " +
                    "degraded ${counts.getValue(TrackedCharacterLocationStatus.DEGRADED)} · " +
                    "unknown ${counts.getValue(TrackedCharacterLocationStatus.UNKNOWN)}",
                style = MaterialTheme.typography.labelSmall,
                color = EveColors.SecondaryText,
            )
            state.followedCharacter?.let { character ->
                Text(
                    "Validated ${character.lastValidatedAt ?: "never"} · Error ${character.lastErrorCategory ?: "none"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = EveColors.SecondaryText,
                )
            }
            Text(
                "${state.preferences.stargateHops} hops · Ansiblex ${if (state.preferences.includeAnsiblexEdges) "included" else "excluded"}",
                style = MaterialTheme.typography.labelSmall,
                color = EveColors.SecondaryText,
            )
            bindingFeedback?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = EveColors.Important)
            }
        }
    }
}

internal fun miniMapLocationLine(state: MiniMapUiState): String {
    val character = state.followedCharacter ?: return "No character selected · UNKNOWN"
    val status = character.locationStatus
    val system = state.followedSystemName ?: if (character.solarSystemId == null) "Location unavailable" else "Unknown system"
    return when (status) {
        TrackedCharacterLocationStatus.STALE -> "Last known: $system · STALE"
        TrackedCharacterLocationStatus.CURRENT -> "$system · CURRENT"
        TrackedCharacterLocationStatus.DEGRADED -> "$system · DEGRADED"
        TrackedCharacterLocationStatus.UNKNOWN -> "$system · UNKNOWN"
    }
}

internal fun miniMapStatusColor(status: TrackedCharacterLocationStatus?): Color = when (status) {
    TrackedCharacterLocationStatus.CURRENT -> EveColors.Success
    TrackedCharacterLocationStatus.STALE -> EveColors.Warning
    TrackedCharacterLocationStatus.DEGRADED -> EveColors.Error
    TrackedCharacterLocationStatus.UNKNOWN, null -> EveColors.SecondaryText
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MiniMapCanvas(state: MiniMapUiState, viewModel: MiniMapViewModel) {
    val textMeasurer = rememberTextMeasurer()
    val portraitStacks = remember(state.characterGroups) {
        state.characterGroups.associateWith { presentCharacterPortraits(it.characters) }
    }
    Canvas(
        Modifier.fillMaxSize()
            .background(EveColors.InputSurface)
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
        slice.edges.filter { it.type == RouteEdgeType.STARGATE }.forEach { edge ->
            val first = transform.worldToScreen(edge.first)
            val second = transform.worldToScreen(edge.second)
            drawLine(
                color = EveColors.Border,
                start = Offset(first.x.toFloat(), first.y.toFloat()),
                end = Offset(second.x.toFloat(), second.y.toFloat()),
                strokeWidth = 1.2f,
            )
        }
        state.ansiblexVisualEdges.forEach { edge ->
            val first = transform.worldToScreen(edge.first)
            val second = transform.worldToScreen(edge.second)
            drawLine(
                color = EveColors.CapitalAccent.copy(alpha = 0.78f),
                start = Offset(first.x.toFloat(), first.y.toFloat()),
                end = Offset(second.x.toFloat(), second.y.toFloat()),
                strokeWidth = 1.6f,
            )
        }
        drawMiniMapRoute(state.visibleRouteLegs, transform)
        slice.nodes.forEach { node ->
            val point = transform.worldToScreen(node.position)
            val center = Offset(point.x.toFloat(), point.y.toFloat())
            val isCenter = node.system.id == slice.centerSystemId
            drawCircle(
                if (isCenter) EveColors.PrimaryAccent else securityColor(node.system.securityStatus),
                radius = if (isCenter) 5.5f else 3.2f,
                center = center,
            )
            val label = textMeasurer.measure(
                node.system.name,
                TextStyle(color = EveColors.PrimaryText, fontSize = 10.sp),
            )
            drawText(label, topLeft = Offset(center.x + 5f, center.y - label.size.height / 2f))
        }
        state.characterGroups.forEach { group ->
            val node = slice.nodes.firstOrNull { it.system.id == group.systemId } ?: return@forEach
            val point = transform.worldToScreen(node.position)
            val nodeCenter = Offset(point.x.toFloat(), point.y.toFloat())
            val markerCenter = nodeCenter + Offset(0f, -17f)
            val current = group.characters.any { it.locationStatus == TrackedCharacterLocationStatus.CURRENT }
            val accent = if (group.containsFollowedCharacter) EveColors.Important else EveColors.PrimaryAccent
            val alpha = if (current) 1f else 0.55f
            val pin = Path().apply {
                moveTo(markerCenter.x - 4f, markerCenter.y + 10f)
                lineTo(nodeCenter.x, nodeCenter.y)
                lineTo(markerCenter.x + 4f, markerCenter.y + 10f)
                close()
            }
            drawPath(pin, EveColors.InputSurface.copy(alpha = alpha))
            drawCircle(EveColors.InputSurface.copy(alpha = alpha), 12f, markerCenter)
            drawCharacterPortraitDisc(
                stack = portraitStacks.getValue(group),
                center = markerCenter,
                radius = 10f,
                textMeasurer = textMeasurer,
                borderColor = accent,
                alpha = alpha,
            )
            if (group.containsFollowedCharacter) {
                drawCircle(accent.copy(alpha = 0.48f), 14f, markerCenter, style = Stroke(2f))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMiniMapRoute(
    legs: List<ProjectedRouteLeg>,
    transform: MapTransform,
) {
    val curvedPath = Path()
    legs.forEach { leg ->
        val style = routeLegRenderStyle(leg.edge.type)
        val geometry = activeRouteConnectionGeometry(
            firstSystemId = leg.edge.fromSystemId,
            secondSystemId = leg.edge.toSystemId,
            edgeType = leg.edge.type,
            start = transform.worldToScreen(leg.from),
            end = transform.worldToScreen(leg.to),
        )
        val width = style.strokeWidth * MINI_MAP_ROUTE_WIDTH_SCALE
        when (geometry) {
            is StraightMapConnectionGeometry -> drawLine(
                color = style.color,
                start = Offset(geometry.start.x.toFloat(), geometry.start.y.toFloat()),
                end = Offset(geometry.end.x.toFloat(), geometry.end.y.toFloat()),
                strokeWidth = width,
            )
            is QuadraticMapConnectionGeometry -> {
                curvedPath.reset()
                curvedPath.moveTo(geometry.start.x.toFloat(), geometry.start.y.toFloat())
                curvedPath.quadraticTo(
                    geometry.control.x.toFloat(),
                    geometry.control.y.toFloat(),
                    geometry.end.x.toFloat(),
                    geometry.end.y.toFloat(),
                )
                drawPath(
                    curvedPath,
                    color = style.color,
                    style = Stroke(
                        width = width,
                        pathEffect = style.dashPattern?.let { pattern ->
                            PathEffect.dashPathEffect(pattern.map { it * MINI_MAP_ROUTE_WIDTH_SCALE }.toFloatArray())
                        },
                    ),
                )
            }
        }
    }
}

private const val MINI_MAP_ROUTE_WIDTH_SCALE = 0.8f

private fun securityColor(security: Double): Color = when {
    security >= 0.5 -> Color(0xFF66B77A)
    security > 0.0 -> EveColors.Warning
    else -> EveColors.Error
}
