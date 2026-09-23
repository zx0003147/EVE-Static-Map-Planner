package dev.evestaticmapplanner.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.capital.CapitalRouteViewModel
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.search.SystemSearchField
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.feature.api.NavigationSnapshot
import dev.evestaticmapplanner.feature.api.RouteActionTargetId
import dev.evestaticmapplanner.featurepack.RouteActionKey
import dev.evestaticmapplanner.featurepack.RouteActionUiState
import dev.evestaticmapplanner.map.confirmGlobalSystemSearch
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.identity.CurrentIdentitySource
import dev.evestaticmapplanner.search.CompactOutlinedTextField
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveCheckbox as Checkbox
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDivider
import dev.evestaticmapplanner.ui.EvePanel
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveVerticalScrollColumn
import dev.evestaticmapplanner.shared.RouteHandoffPublishUiState
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.localization.AppStrings
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.localization.RouteStrings
import kotlin.math.abs

internal enum class SidebarToolIcon {
    SEARCH,
    JUMP_RANGE,
    NORMAL_ROUTE,
    CAPITAL_ROUTE,
    ANSIBLEX,
}

internal enum class ToolSidebarSection(val icon: SidebarToolIcon) {
    SEARCH(SidebarToolIcon.SEARCH),
    JUMP_RANGE(SidebarToolIcon.JUMP_RANGE),
    NORMAL_ROUTE(SidebarToolIcon.NORMAL_ROUTE),
    CAPITAL_ROUTE(SidebarToolIcon.CAPITAL_ROUTE),
    ANSIBLEX(SidebarToolIcon.ANSIBLEX),
}

internal val TOOL_SIDEBAR_SECTION_ORDER = listOf(
    ToolSidebarSection.SEARCH,
    ToolSidebarSection.JUMP_RANGE,
    ToolSidebarSection.ANSIBLEX,
    ToolSidebarSection.NORMAL_ROUTE,
    ToolSidebarSection.CAPITAL_ROUTE,
)

internal fun sidebarSectionLabel(section: ToolSidebarSection, strings: AppStrings): String = when (section) {
    ToolSidebarSection.SEARCH -> strings.search.title
    ToolSidebarSection.JUMP_RANGE -> strings.route.jumpRangeOverlays
    ToolSidebarSection.NORMAL_ROUTE -> strings.route.normalRoute
    ToolSidebarSection.CAPITAL_ROUTE -> strings.route.capitalRoute
    ToolSidebarSection.ANSIBLEX -> strings.ansiblex.sectionTitle
}

internal data class ToolSidebarExpansionState(
    val expandedSections: Set<ToolSidebarSection> = emptySet(),
) {
    fun isExpanded(section: ToolSidebarSection): Boolean = section in expandedSections

    fun expand(section: ToolSidebarSection): ToolSidebarExpansionState = copy(
        expandedSections = expandedSections + section,
    )

    fun toggle(section: ToolSidebarSection): ToolSidebarExpansionState = copy(
        expandedSections = if (isExpanded(section)) expandedSections - section else expandedSections + section,
    )
}

@Composable
internal fun RouteToolsPanel(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    projectionId: MapProjectionId,
    onToggleProjection: () -> Unit,
    onOpenEmbeddedAi: () -> Unit,
    state: RoutePlannerUiState,
    viewModel: RoutePlannerViewModel,
    capitalState: CapitalRouteUiState,
    capitalViewModel: CapitalRouteViewModel,
    jumpState: JumpOverlayUiState,
    jumpViewModel: JumpOverlayViewModel,
    routeActions: List<RouteActionUiState>,
    normalRouteSnapshot: RouteSnapshot?,
    normalNavigationSnapshot: NavigationSnapshot?,
    capitalRouteSnapshot: RouteSnapshot?,
    selectedRouteActionTargets: Map<String, String>,
    onSelectRouteActionTarget: (String, String?) -> Unit,
    onInvokeRouteAction: (RouteActionKey, RouteSnapshot, RouteActionTargetId?) -> Unit,
    onInvokeNavigationAction: (RouteActionKey, NavigationSnapshot, RouteActionTargetId?) -> Unit,
    onOpenAnsiblexManager: () -> Unit,
    onSwitchAnsiblexIdentity: () -> Unit,
    onOpenWormholeManager: () -> Unit,
    onFocusSystem: (Int) -> Unit,
    sharedMapState: SharedMapState,
    routeHandoffPublishState: RouteHandoffPublishUiState,
    onPublishNormalRoute: () -> Unit,
    onPublishCapitalRoute: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var expansionState by remember { mutableStateOf(ToolSidebarExpansionState()) }
    EvePanel(
        modifier = Modifier
            .width(if (expanded) TOOL_SIDEBAR_WIDTH else TOOL_SIDEBAR_COLLAPSED_WIDTH)
            .fillMaxHeight(),
    ) {
        if (expanded) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EveVerticalScrollColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TOOL_SIDEBAR_SECTION_ORDER.forEachIndexed { index, section ->
                        if (index > 0) EveDivider()
                        val sectionExpanded = expansionState.isExpanded(section)
                        val sectionTitle = sidebarSectionLabel(section, strings)
                        when (section) {
                            ToolSidebarSection.SEARCH -> CollapsibleToolSection(
                                title = sectionTitle,
                                icon = section.icon,
                                summary = null,
                                expanded = sectionExpanded,
                                onToggle = { expansionState = expansionState.toggle(section) },
                            ) {
                                SystemSearchField(
                                    value = state.systemQuery,
                                    label = strings.search.searchSystemPlaceholder,
                                    results = state.systemResults,
                                    onValueChange = viewModel::updateSystemQuery,
                                    onSelect = { system ->
                                        confirmGlobalSystemSearch(
                                            system,
                                            viewModel::selectSystemSearch,
                                            onFocusSystem,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    compact = true,
                                )
                            }
                            ToolSidebarSection.JUMP_RANGE -> CollapsibleToolSection(
                                title = sectionTitle,
                                icon = section.icon,
                                summary = jumpState.overlays.takeIf(List<*>::isNotEmpty)?.let {
                                    "${jumpState.overlays.count { overlay -> overlay.enabled }}/${jumpState.overlays.size}"
                                },
                                expanded = sectionExpanded,
                                onToggle = { expansionState = expansionState.toggle(section) },
                            ) {
                                JumpRangeSectionContent(jumpState, jumpViewModel)
                            }
                            ToolSidebarSection.NORMAL_ROUTE -> CollapsibleToolSection(
                                title = sectionTitle,
                                icon = section.icon,
                                summary = state.activeRoute?.let { strings.route.jumpCount(it.totalJumps) },
                                expanded = sectionExpanded,
                                onToggle = { expansionState = expansionState.toggle(section) },
                            ) {
                                NormalRouteSectionContent(
                                    state,
                                    viewModel,
                                    routeActions,
                                    normalRouteSnapshot,
                                    normalNavigationSnapshot,
                                    selectedRouteActionTargets,
                                    onSelectRouteActionTarget,
                                    onInvokeRouteAction,
                                    onInvokeNavigationAction,
                                    onOpenWormholeManager,
                                    sharedMapState,
                                    routeHandoffPublishState,
                                    onPublishNormalRoute,
                                )
                            }
                            ToolSidebarSection.CAPITAL_ROUTE -> CollapsibleToolSection(
                                title = sectionTitle,
                                icon = section.icon,
                                summary = capitalState.activeRoute?.let { strings.route.jumpCount(it.totalJumps) },
                                expanded = sectionExpanded,
                                onToggle = { expansionState = expansionState.toggle(section) },
                            ) {
                                CapitalRouteSectionContent(
                                    capitalState,
                                    capitalViewModel,
                                    routeActions,
                                    capitalRouteSnapshot,
                                    selectedRouteActionTargets,
                                    onSelectRouteActionTarget,
                                    onInvokeRouteAction,
                                    sharedMapState,
                                    routeHandoffPublishState,
                                    onPublishCapitalRoute,
                                )
                            }
                            ToolSidebarSection.ANSIBLEX -> CollapsibleToolSection(
                                title = sectionTitle,
                                icon = section.icon,
                                summary = state.currentIdentityContext?.allianceTicker?.let { "[$it]" },
                                expanded = sectionExpanded,
                                onToggle = { expansionState = expansionState.toggle(section) },
                            ) {
                                AnsiblexSectionContent(
                                    state = state,
                                    onSwitchIdentity = onSwitchAnsiblexIdentity,
                                    onShowUnavailableChanged = viewModel::setShowAnsiblexLayer,
                                    onOpenManager = onOpenAnsiblexManager,
                                )
                            }
                        }
                    }
                }
                EveDivider()
                SidebarControlCluster(
                    expanded = true,
                    projectionId = projectionId,
                    onToggleProjection = onToggleProjection,
                    onOpenEmbeddedAi = onOpenEmbeddedAi,
                    onToggleSidebar = onToggleExpanded,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TOOL_SIDEBAR_SECTION_ORDER.forEach { section ->
                        SidebarRailActionButton(
                            section = section,
                            onClick = {
                                expansionState = expansionState.expand(section)
                                onToggleExpanded()
                            },
                        )
                    }
                }
                SidebarControlCluster(
                    expanded = false,
                    projectionId = projectionId,
                    onToggleProjection = onToggleProjection,
                    onOpenEmbeddedAi = onOpenEmbeddedAi,
                    onToggleSidebar = onToggleExpanded,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SidebarRailActionButton(
    section: ToolSidebarSection,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (hovered) EveColors.HoverSurface else EveColors.PrimarySurface)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = sidebarSectionLabel(section, strings) }
            .testTag("sidebar-rail-${section.name.lowercase()}"),
        contentAlignment = Alignment.Center,
    ) {
        SidebarToolIcon(
            icon = section.icon,
            color = if (hovered) EveColors.PrimaryAccent else EveColors.PrimaryText,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun SidebarControlCluster(
    expanded: Boolean,
    projectionId: MapProjectionId,
    onToggleProjection: () -> Unit,
    onOpenEmbeddedAi: () -> Unit,
    onToggleSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    if (expanded) {
        Row(
            modifier = modifier.height(SIDEBAR_CONTROL_SIZE),
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SidebarUtilityButton(
                    description = strings.map.toggleProjection,
                    stateDescription = projectionToggleStateDescription(projectionId, strings),
                    testTag = SIDEBAR_PROJECTION_TOGGLE_TEST_TAG,
                    onClick = onToggleProjection,
                    modifier = Modifier.width(TOOL_SIDEBAR_COLLAPSED_WIDTH).fillMaxHeight(),
                ) { ProjectionToggleGraphic(projectionId) }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SidebarUtilityButton(
                    description = strings.map.openEmbeddedAiAssistant,
                    testTag = SIDEBAR_AI_BUTTON_TEST_TAG,
                    onClick = onOpenEmbeddedAi,
                    modifier = Modifier.width(TOOL_SIDEBAR_COLLAPSED_WIDTH).fillMaxHeight(),
                ) { AuraAvatar() }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SidebarUtilityButton(
                    description = strings.map.collapseSidebar,
                    testTag = SIDEBAR_TOGGLE_TEST_TAG,
                    onClick = onToggleSidebar,
                    modifier = Modifier.width(TOOL_SIDEBAR_COLLAPSED_WIDTH).fillMaxHeight(),
                ) { SidebarToggleIcon(expanded = true) }
            }
        }
    } else {
        Column(modifier = modifier.height(SIDEBAR_CONTROL_SIZE * 3)) {
            SidebarUtilityButton(
                description = strings.map.toggleProjection,
                stateDescription = projectionToggleStateDescription(projectionId, strings),
                testTag = SIDEBAR_PROJECTION_TOGGLE_TEST_TAG,
                onClick = onToggleProjection,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { ProjectionToggleGraphic(projectionId) }
            SidebarUtilityButton(
                description = strings.map.openEmbeddedAiAssistant,
                testTag = SIDEBAR_AI_BUTTON_TEST_TAG,
                onClick = onOpenEmbeddedAi,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { AuraAvatar() }
            SidebarUtilityButton(
                description = strings.map.expandSidebar,
                testTag = SIDEBAR_TOGGLE_TEST_TAG,
                onClick = onToggleSidebar,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { SidebarToggleIcon(expanded = false) }
        }
    }
}

@Composable
private fun SidebarUtilityButton(
    description: String,
    stateDescription: String? = null,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .heightIn(min = SIDEBAR_CONTROL_SIZE)
            .background(if (hovered) EveColors.HoverSurface else EveColors.PrimarySurface)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = description
                stateDescription?.let { this.stateDescription = it }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ProjectionToggleGraphic(projectionId: MapProjectionId) {
    val is2D = projectionId == MapProjectionId.OFFICIAL_2D
    val currentColor = EveColors.PrimaryAccent
    val inactiveColor = EveColors.SecondaryText.copy(alpha = 0.62f)
    Box(Modifier.size(width = 46.dp, height = 38.dp).scale(0.81f)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag(SIDEBAR_PROJECTION_SWITCH_ICON_TEST_TAG),
        ) {
            val color = EveColors.PrimaryText
            val strokeWidth = 1.7.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            projectionToggleArrowGeometry(size.width, size.height).forEach { arrow ->
                val path = Path().apply {
                    moveTo(arrow.start.x, arrow.start.y)
                    cubicTo(
                        arrow.control1.x,
                        arrow.control1.y,
                        arrow.control2.x,
                        arrow.control2.y,
                        arrow.end.x,
                        arrow.end.y,
                    )
                }
                drawPath(path, color, style = stroke)
                drawLine(color, arrow.end, arrow.headSideA, strokeWidth, StrokeCap.Round)
                drawLine(color, arrow.end, arrow.headSideB, strokeWidth, StrokeCap.Round)
            }
        }
        Text(
            "2D",
            color = if (is2D) currentColor else inactiveColor,
            fontWeight = if (is2D) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 1.dp)
                .testTag(SIDEBAR_PROJECTION_2D_LABEL_TEST_TAG),
        )
        Text(
            "3D",
            color = if (is2D) inactiveColor else currentColor,
            fontWeight = if (is2D) androidx.compose.ui.text.font.FontWeight.Normal else androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 11.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 1.dp)
                .testTag(SIDEBAR_PROJECTION_3D_LABEL_TEST_TAG),
        )
    }
}

internal data class ProjectionTogglePoint(val x: Float, val y: Float)

internal data class ProjectionToggleArcSpec(
    val start: ProjectionTogglePoint,
    val control1: ProjectionTogglePoint,
    val control2: ProjectionTogglePoint,
    val end: ProjectionTogglePoint,
)

internal data class ProjectionToggleArrowGeometry(
    val start: Offset,
    val control1: Offset,
    val control2: Offset,
    val end: Offset,
    val headSideA: Offset,
    val headSideB: Offset,
)

internal val PROJECTION_TOGGLE_LEFT_ARC = ProjectionToggleArcSpec(
    start = ProjectionTogglePoint(0.56f, 0.18f),
    control1 = ProjectionTogglePoint(0.26f, 0.08f),
    control2 = ProjectionTogglePoint(0.32f, 0.38f),
    end = ProjectionTogglePoint(0.20f, 0.62f),
)

internal val PROJECTION_TOGGLE_RIGHT_ARC = ProjectionToggleArcSpec(
    start = ProjectionTogglePoint(0.44f, 0.82f),
    control1 = ProjectionTogglePoint(0.74f, 0.92f),
    control2 = ProjectionTogglePoint(0.68f, 0.62f),
    end = ProjectionTogglePoint(0.80f, 0.38f),
)

internal fun projectionToggleArrowGeometry(width: Float, height: Float): List<ProjectionToggleArrowGeometry> =
    listOf(PROJECTION_TOGGLE_LEFT_ARC, PROJECTION_TOGGLE_RIGHT_ARC).map { spec ->
        fun ProjectionTogglePoint.toOffset() = Offset(x * width, y * height)
        val start = spec.start.toOffset()
        val control1 = spec.control1.toOffset()
        val control2 = spec.control2.toOffset()
        val end = spec.end.toOffset()
        val tangent = end - control2
        val tangentLength = tangent.getDistance().coerceAtLeast(1f)
        val unit = tangent / tangentLength
        val normal = Offset(-unit.y, unit.x)
        val headLength = minOf(width, height) * 0.14f
        val headWidth = minOf(width, height) * 0.08f
        val headBase = end - unit * headLength
        ProjectionToggleArrowGeometry(
            start = start,
            control1 = control1,
            control2 = control2,
            end = end,
            headSideA = headBase + normal * headWidth,
            headSideB = headBase - normal * headWidth,
        )
    }

private fun projectionToggleStateDescription(projectionId: MapProjectionId, strings: AppStrings): String =
    if (projectionId == MapProjectionId.OFFICIAL_2D) strings.map.official2DSelected else strings.map.real3DSelected

@Composable
private fun AuraAvatar() {
    Image(
        painter = painterResource("icons/aura-avatar.png"),
        contentDescription = null,
        modifier = Modifier.size(34.dp).clip(CircleShape),
    )
}

@Composable
private fun SidebarToggleIcon(expanded: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val color = EveColors.PrimaryText
        val strokeWidth = 2.dp.toPx()
        val left = if (expanded) 0.66f else 0.34f
        val point = if (expanded) 0.36f else 0.64f
        drawLine(color, Offset(size.width * left, size.height * 0.20f), Offset(size.width * point, size.height * 0.50f), strokeWidth)
        drawLine(color, Offset(size.width * point, size.height * 0.50f), Offset(size.width * left, size.height * 0.80f), strokeWidth)
    }
}

internal const val SIDEBAR_PROJECTION_TOGGLE_TEST_TAG = "sidebar-projection-toggle"
internal const val SIDEBAR_PROJECTION_2D_LABEL_TEST_TAG = "sidebar-projection-2d-label"
internal const val SIDEBAR_PROJECTION_3D_LABEL_TEST_TAG = "sidebar-projection-3d-label"
internal const val SIDEBAR_PROJECTION_SWITCH_ICON_TEST_TAG = "sidebar-projection-switch-icon"
internal const val SIDEBAR_AI_BUTTON_TEST_TAG = "sidebar-ai-assistant"
internal const val SIDEBAR_TOGGLE_TEST_TAG = "sidebar-toggle"
private val SIDEBAR_CONTROL_SIZE = 44.dp

@Composable
private fun CollapsibleToolSection(
    title: String,
    icon: SidebarToolIcon,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
                .background(if (hovered) EveColors.HoverSurface else EveColors.PrimarySurface)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle)
                .padding(vertical = 6.dp, horizontal = 2.dp),
        ) {
            SidebarDisclosureIcon(expanded = expanded, modifier = Modifier.size(10.dp))
            SidebarToolIcon(icon = icon, modifier = Modifier.size(18.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            summary?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = EveColors.SecondaryText)
            }
        }
        if (expanded) content()
    }
}

@Composable
private fun SidebarDisclosureIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val triangle = Path().apply {
            if (expanded) {
                moveTo(size.width * 0.16f, size.height * 0.30f)
                lineTo(size.width * 0.84f, size.height * 0.30f)
                lineTo(size.width * 0.50f, size.height * 0.72f)
            } else {
                moveTo(size.width * 0.28f, size.height * 0.16f)
                lineTo(size.width * 0.72f, size.height * 0.50f)
                lineTo(size.width * 0.28f, size.height * 0.84f)
            }
            close()
        }
        drawPath(triangle, EveColors.PrimaryText)
    }
}

@Composable
private fun SidebarToolIcon(
    icon: SidebarToolIcon,
    modifier: Modifier = Modifier,
    color: Color = EveColors.PrimaryText,
) {
    Canvas(modifier) {
        val strokeWidth = 1.5.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        when (icon) {
            SidebarToolIcon.SEARCH -> {
                val center = Offset(size.width * 0.42f, size.height * 0.42f)
                drawCircle(color, size.minDimension * 0.25f, center, style = stroke)
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.60f, size.height * 0.60f),
                    end = Offset(size.width * 0.84f, size.height * 0.84f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            SidebarToolIcon.JUMP_RANGE -> {
                val center = Offset(size.width * 0.50f, size.height * 0.50f)
                drawCircle(color, size.minDimension * 0.17f, center, style = stroke)
                drawCircle(color, size.minDimension * 0.36f, center, style = stroke)
            }
            SidebarToolIcon.NORMAL_ROUTE -> {
                val end = Offset(size.width * 0.82f, size.height * 0.18f)
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.18f, size.height * 0.82f),
                    end = end,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawRouteArrowHead(color, end, strokeWidth)
            }
            SidebarToolIcon.ANSIBLEX -> {
                val first = Offset(size.width * 0.28f, size.height * 0.70f)
                val second = Offset(size.width * 0.72f, size.height * 0.30f)
                drawLine(color, first, second, strokeWidth, cap = StrokeCap.Round)
                drawCircle(color, size.minDimension * 0.12f, first, style = stroke)
                drawCircle(color, size.minDimension * 0.12f, second, style = stroke)
            }
            SidebarToolIcon.CAPITAL_ROUTE -> {
                val viewport = 24f
                fun point(x: Float, y: Float) = Offset(
                    x = size.width * x / viewport,
                    y = size.height * y / viewport,
                )
                val end = point(19.5f, 4.5f)
                val jumpArc = Path().apply {
                    val start = point(4.5f, 19.5f)
                    val control = point(12f, 7f)
                    moveTo(start.x, start.y)
                    quadraticTo(control.x, control.y, end.x, end.y)
                }
                drawPath(jumpArc, color, style = stroke)
                drawRouteArrowHead(color, end, strokeWidth)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRouteArrowHead(
    color: Color,
    end: Offset,
    strokeWidth: Float,
) {
    drawLine(
        color = color,
        start = Offset(end.x - size.width * 0.26f, end.y),
        end = end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(end.x, end.y + size.height * 0.26f),
        end = end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

@Composable
private fun JumpRangeSectionContent(
    state: JumpOverlayUiState,
    viewModel: JumpOverlayViewModel,
) {
    val strings = LocalAppStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SystemSearchField(
            value = state.originQuery,
            label = strings.route.overlayOrigin,
            results = state.originResults,
            onValueChange = viewModel::updateOriginQuery,
            onSelect = viewModel::selectOrigin,
            compact = true,
        )
        CompactOutlinedTextField(
            value = state.manualRangeText,
            onValueChange = viewModel::updateManualRange,
            label = strings.route.effectiveMaximumLy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = viewModel::addSelectedOrigin,
                enabled = state.selectedOrigin != null && !state.isLoading && !state.isCalculating,
            ) { Text(strings.common.add) }
            TextButton(onClick = viewModel::clear, enabled = state.overlays.isNotEmpty()) { Text(strings.common.clear) }
        }
        state.overlays.forEach { overlay ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = overlay.enabled,
                        onCheckedChange = { viewModel.setEnabled(overlay.id, it) },
                    )
                    Text(overlay.label ?: overlay.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = overlay.id in state.intersectionOverlayIds,
                        onCheckedChange = { viewModel.toggleIntersectionSelection(overlay.id, it) },
                        enabled = overlay.enabled,
                    )
                    Text(strings.route.intersect, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { viewModel.updateWithCurrentRange(overlay.id) }) { Text(strings.common.update) }
                    TextButton(onClick = { viewModel.remove(overlay.id) }) { Text(strings.common.remove) }
                }
            }
        }
        if (state.intersectionOverlayIds.isNotEmpty()) {
            Text(
                strings.route.overlayIntersection(state.intersectionOverlayIds.size, state.intersectionSystemIds.size),
                color = EveColors.Important,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let { Text(it.resolve(strings), color = EveColors.Error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun NormalRouteSectionContent(
    state: RoutePlannerUiState,
    viewModel: RoutePlannerViewModel,
    routeActions: List<RouteActionUiState>,
    routeSnapshot: RouteSnapshot?,
    navigationSnapshot: NavigationSnapshot?,
    selectedRouteActionTargets: Map<String, String>,
    onSelectRouteActionTarget: (String, String?) -> Unit,
    onInvokeRouteAction: (RouteActionKey, RouteSnapshot, RouteActionTargetId?) -> Unit,
    onInvokeNavigationAction: (RouteActionKey, NavigationSnapshot, RouteActionTargetId?) -> Unit,
    onOpenWormholeManager: () -> Unit,
    sharedMapState: SharedMapState,
    routeHandoffPublishState: RouteHandoffPublishUiState,
    onPublishRoute: () -> Unit,
) {
    val strings = LocalAppStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROUTE_CONTROL_VERTICAL_SPACING)) {
        SystemSearchField(
            value = state.fromQuery,
            label = strings.route.start,
            results = state.fromResults,
            onValueChange = viewModel::updateFromQuery,
            onSelect = viewModel::selectFrom,
            compact = true,
        )
        WaypointList(
            waypoints = state.waypoints,
            onMove = viewModel::moveRouteWaypoint,
            onRemove = viewModel::removeRouteWaypoint,
        )
        SystemSearchField(
            value = state.toQuery,
            label = strings.route.destinationOptional,
            results = state.toResults,
            onValueChange = viewModel::updateToQuery,
            onSelect = viewModel::selectTo,
            compact = true,
        )
        NormalRouteConnectionOptions(
            state = state,
            onUseAnsiblexChanged = viewModel::setUseAnsiblex,
            onUseWormholesChanged = viewModel::setUseWormholes,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::calculateRoute,
                enabled = state.selectedFrom != null &&
                    (state.selectedTo != null || state.waypoints.isNotEmpty()) && !state.isLoading,
            ) { Text(strings.route.calculate) }
            TextButton(onClick = viewModel::clearRoute, enabled = state.routeOutcome != null || state.activeRoute != null) {
                Text(strings.common.clear)
            }
        }
        if (state.isRouteStale) {
            Text(strings.route.needsRecalculation, color = EveColors.Important, style = MaterialTheme.typography.labelSmall)
        }
        state.navigationMessage?.let {
            Text(it.resolve(strings), color = EveColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
        RouteSummary(state)
        RouteHandoffPublishControl(
            hasRoute = state.activeRoute != null,
            sharedMapState = sharedMapState,
            publishState = routeHandoffPublishState,
            label = strings.route.publishNormalRoute,
            onPublish = onPublishRoute,
        )
        if (
            state.activeRoute?.wormholeJumps?.let { it > 0 } == true &&
            routeActions.any { !it.supportsNavigationIntent }
        ) {
            Text(
                strings.route.routeActionsUnavailableForWormholes,
                color = EveColors.Important,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        NavigationRouteActionButtons(
            routeActions,
            routeSnapshot,
            navigationSnapshot,
            selectedRouteActionTargets,
            onSelectRouteActionTarget,
            onInvokeRouteAction,
            onInvokeNavigationAction,
        )
        RouteManagerButtons(state, onOpenWormholeManager)
        state.userDatabaseError?.let {
            Text(it.resolve(strings), color = EveColors.Error, style = MaterialTheme.typography.bodySmall)
            Text(
                strings.route.stargateOnlyRoutingAvailable,
                color = EveColors.SecondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun RouteManagerButtons(
    state: RoutePlannerUiState,
    onOpenWormholeManager: () -> Unit,
) {
    val strings = LocalAppStrings.current
    TextButton(onClick = onOpenWormholeManager) {
        Text(strings.route.wormholeManager(state.wormholeConnections.size))
    }
}

@Composable
internal fun NormalRouteConnectionOptions(
    state: RoutePlannerUiState,
    onUseAnsiblexChanged: (Boolean) -> Unit,
    onUseWormholesChanged: (Boolean) -> Unit,
) {
    val strings = LocalAppStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.useAnsiblex,
            onCheckedChange = onUseAnsiblexChanged,
            enabled = state.isAnsiblexAvailable,
            modifier = Modifier.size(ROUTE_OPTION_CONTROL_SIZE),
        )
        Text(strings.route.useAnsiblex)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.useWormholes,
            onCheckedChange = onUseWormholesChanged,
            modifier = Modifier.size(ROUTE_OPTION_CONTROL_SIZE).testTag(USE_WORMHOLES_CHECKBOX_TAG),
        )
        Text(strings.route.useWormholes)
    }
}

@Composable
internal fun AnsiblexSectionContent(
    state: RoutePlannerUiState,
    onSwitchIdentity: () -> Unit,
    onShowUnavailableChanged: (Boolean) -> Unit,
    onOpenManager: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val identity = state.currentIdentityContext
    Column(
        Modifier.fillMaxWidth().testTag(ANSIBLEX_SECTION_CONTENT_TAG),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(strings.ansiblex.currentCharacter, color = EveColors.SecondaryText, style = MaterialTheme.typography.labelSmall)
        Text(
            when (identity?.source) {
                CurrentIdentitySource.ESI -> "ESI · ${identity.character?.name ?: strings.ansiblex.esiUnavailable}"
                CurrentIdentitySource.MANUAL -> strings.ansiblex.manualIdentity
                null -> strings.ansiblex.esiUnavailable
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(identity.allianceDisplayLabel(), color = EveColors.Important, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onSwitchIdentity) { Text(strings.ansiblex.switchIdentity) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.showAnsiblexLayer,
                onCheckedChange = onShowUnavailableChanged,
                enabled = state.isAnsiblexAvailable,
                modifier = Modifier.size(ROUTE_OPTION_CONTROL_SIZE).testTag(SHOW_UNAVAILABLE_ANSIBLEX_TAG),
            )
            Text(strings.ansiblex.showUnavailable)
        }
        Button(onClick = onOpenManager, enabled = state.isAnsiblexAvailable) {
            Text(strings.ansiblex.managerTitle)
        }
    }
}

private fun dev.evestaticmapplanner.core.identity.CurrentIdentityContext?.allianceDisplayLabel(): String {
    if (this == null) return "—"
    return listOfNotNull(allianceTicker?.let { "[$it]" }, allianceName, allianceId?.let { "#$it" })
        .joinToString(" ")
        .ifEmpty { "—" }
}

internal const val ANSIBLEX_SECTION_CONTENT_TAG = "ansiblex-section-content"
internal const val SHOW_UNAVAILABLE_ANSIBLEX_TAG = "show-unavailable-ansiblex"

@Composable
private fun CapitalRouteSectionContent(
    state: CapitalRouteUiState,
    viewModel: CapitalRouteViewModel,
    routeActions: List<RouteActionUiState>,
    routeSnapshot: RouteSnapshot?,
    selectedRouteActionTargets: Map<String, String>,
    onSelectRouteActionTarget: (String, String?) -> Unit,
    onInvokeRouteAction: (RouteActionKey, RouteSnapshot, RouteActionTargetId?) -> Unit,
    sharedMapState: SharedMapState,
    routeHandoffPublishState: RouteHandoffPublishUiState,
    onPublishRoute: () -> Unit,
) {
    val strings = LocalAppStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROUTE_CONTROL_VERTICAL_SPACING)) {
        SystemSearchField(
            value = state.fromQuery,
            label = strings.route.capitalStart,
            results = state.fromResults,
            onValueChange = viewModel::updateFromQuery,
            onSelect = viewModel::selectFrom,
            compact = true,
        )
        WaypointList(
            waypoints = state.waypoints,
            onMove = viewModel::moveRouteWaypoint,
            onRemove = viewModel::removeRouteWaypoint,
        )
        SystemSearchField(
            value = state.toQuery,
            label = strings.route.capitalDestinationOptional,
            results = state.toResults,
            onValueChange = viewModel::updateToQuery,
            onSelect = viewModel::selectTo,
            compact = true,
        )
        CompactOutlinedTextField(
            value = state.manualRangeText,
            onValueChange = viewModel::updateManualRange,
            label = strings.route.effectiveMaximumLy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::calculate,
                enabled = state.selectedFrom != null && (state.selectedTo != null || state.waypoints.isNotEmpty()) &&
                    !state.isLoading && !state.isCalculating,
            ) { Text(if (state.isCalculating) strings.route.calculating else strings.route.calculate) }
            TextButton(onClick = viewModel::clear, enabled = state.outcome != null) { Text(strings.common.clear) }
        }
        if (state.isRouteStale) {
            Text(strings.route.needsRecalculation, color = EveColors.Important, style = MaterialTheme.typography.labelSmall)
        }
        state.navigationMessage?.let {
            Text(it.resolve(strings), color = EveColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
        CapitalRouteSummary(state)
        RouteHandoffPublishControl(
            hasRoute = state.activeRoute != null,
            sharedMapState = sharedMapState,
            publishState = routeHandoffPublishState,
            label = strings.route.publishCapitalRoute,
            onPublish = onPublishRoute,
        )
        RouteActionButtons(
            routeActions,
            routeSnapshot,
            selectedRouteActionTargets,
            onSelectRouteActionTarget,
            onInvokeRouteAction,
        )
        state.error?.let { Text(it.resolve(strings), color = EveColors.Error, style = MaterialTheme.typography.bodySmall) }
        Text(
            strings.route.validatesStaticCapitalRules,
            color = EveColors.SecondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            strings.route.capitalLiveStateDisclaimer,
            color = EveColors.Warning,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            strings.route.phaseLabel,
            style = MaterialTheme.typography.labelSmall,
            color = EveColors.SecondaryText,
        )
    }
}

internal val TOOL_SIDEBAR_WIDTH = 270.dp
internal val TOOL_SIDEBAR_COLLAPSED_WIDTH = 48.dp
internal val ROUTE_CONTROL_VERTICAL_SPACING = 5.dp
internal val ROUTE_OPTION_CONTROL_SIZE = 28.dp
internal const val USE_WORMHOLES_CHECKBOX_TAG = "normal-route-use-wormholes"

@Composable
private fun RouteSummary(state: RoutePlannerUiState) {
    val strings = LocalAppStrings.current
    when (val outcome = state.routeOutcome) {
        null -> Unit
        is RouteCalculationOutcome.Found -> {
            val route = outcome.route
            Text(
                normalRouteSummaryText(route, strings.route),
                color = EveColors.Important,
            )
            Text(state.routeSystemNames.joinToString(" → "), style = MaterialTheme.typography.bodySmall)
        }
        is RouteCalculationOutcome.SameSystem -> Text(strings.route.sameNormalSystem)
        is RouteCalculationOutcome.Unreachable -> Text(strings.route.normalRouteUnreachable, color = EveColors.Warning)
        is RouteCalculationOutcome.InvalidEndpoint -> Text(strings.route.invalidNormalEndpoints, color = EveColors.Error)
    }
}

@Composable
internal fun WaypointList(
    waypoints: List<SolarSystem>,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val strings = LocalAppStrings.current
    if (waypoints.isEmpty()) {
        Text(
            strings.route.waypointHint,
            color = EveColors.SecondaryText,
            style = MaterialTheme.typography.labelSmall,
        )
        return
    }
    val rowCenters = remember(waypoints) { mutableMapOf<Int, Float>() }
    var draggedIndex by remember(waypoints) { mutableStateOf<Int?>(null) }
    var accumulatedDragY by remember(waypoints) { mutableStateOf(0f) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WAYPOINT_ROW_SPACING)) {
        Text(strings.route.waypoints, color = EveColors.SecondaryText, style = MaterialTheme.typography.labelSmall)
        waypoints.forEachIndexed { index, waypoint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WAYPOINT_ROW_HEIGHT)
                    .background(if (draggedIndex == index) EveColors.SelectedSurface else EveColors.SecondarySurface)
                    .onGloballyPositioned { coordinates ->
                        rowCenters[index] = coordinates.positionInParent().y + coordinates.size.height / 2f
                    }
                    .padding(start = 6.dp, end = 2.dp)
                    .testTag("$WAYPOINT_ROW_TEST_TAG_PREFIX-$index"),
            ) {
                Text(
                    "${index + 1}",
                    color = EveColors.PrimaryAccent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(WAYPOINT_SEQUENCE_WIDTH),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(WAYPOINT_DRAG_HANDLE_WIDTH)
                        .fillMaxHeight()
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(index, waypoints) {
                            detectDragGestures(
                                onDragStart = {
                                    draggedIndex = index
                                    accumulatedDragY = 0f
                                },
                                onDragCancel = {
                                    draggedIndex = null
                                    accumulatedDragY = 0f
                                },
                                onDragEnd = {
                                    val intendedCenter = rowCenters[index]?.plus(accumulatedDragY)
                                    val target = intendedCenter?.let { center ->
                                        rowCenters.minByOrNull { (_, rowCenter) -> abs(rowCenter - center) }?.key
                                    } ?: index
                                    draggedIndex = null
                                    accumulatedDragY = 0f
                                    onMove(index, target)
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                accumulatedDragY += dragAmount.y
                            }
                        }
                        .testTag("$WAYPOINT_DRAG_HANDLE_TEST_TAG_PREFIX-$index"),
                ) {
                    Text("::", color = EveColors.SecondaryText, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    waypoint.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onRemove(index) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .size(WAYPOINT_REMOVE_CONTROL_SIZE)
                        .testTag("$WAYPOINT_REMOVE_TEST_TAG_PREFIX-$index"),
                ) { Text("×") }
            }
        }
    }
}

@Composable
private fun RouteHandoffPublishControl(
    hasRoute: Boolean,
    sharedMapState: SharedMapState,
    publishState: RouteHandoffPublishUiState,
    label: String,
    onPublish: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val role = sharedMapState.identity?.workspace?.role
    val reason = when {
        !hasRoute -> strings.route.calculateBeforePublishing
        sharedMapState.connectionState != SharedConnectionState.ONLINE -> strings.route.connectSharedMapBeforePublishing
        sharedMapState.meta?.supportsRouteHandoffs != true -> strings.route.routeHandoffsUnsupported
        role == SharedWorkspaceRole.VIEWER -> strings.route.publishPermissionRequired
        publishState.busy -> strings.route.publishingRoute
        else -> null
    }
    Button(onClick = onPublish, enabled = reason == null) { Text(label) }
    reason?.let { Text(it, color = EveColors.SecondaryText, style = MaterialTheme.typography.labelSmall) }
    publishState.message?.let { Text(it.resolve(strings), color = EveColors.Success, style = MaterialTheme.typography.bodySmall) }
    publishState.error?.let { Text(strings.sharedMap.error(it), color = EveColors.Error, style = MaterialTheme.typography.bodySmall) }
}

internal val WAYPOINT_ROW_HEIGHT = 32.dp
internal val WAYPOINT_ROW_SPACING = 2.dp
internal val WAYPOINT_SEQUENCE_WIDTH = 18.dp
internal val WAYPOINT_DRAG_HANDLE_WIDTH = 32.dp
internal val WAYPOINT_REMOVE_CONTROL_SIZE = 32.dp
internal const val WAYPOINT_ROW_TEST_TAG_PREFIX = "route-waypoint-row"
internal const val WAYPOINT_DRAG_HANDLE_TEST_TAG_PREFIX = "route-waypoint-drag-handle"
internal const val WAYPOINT_REMOVE_TEST_TAG_PREFIX = "route-waypoint-remove"

internal fun normalRouteSummaryText(
    route: dev.evestaticmapplanner.core.route.RouteResult,
    strings: RouteStrings = AppStringsCatalog.forLocale(AppLocale.EN_US).route,
): String = buildString {
    append(strings.jumpCount(route.totalJumps))
    append(" · ${strings.stargateCount(route.stargateJumps)}")
    append(" · ${strings.ansiblexCount(route.ansiblexJumps)}")
    if (route.wormholeJumps > 0) append(" · ${strings.wormholeCount(route.wormholeJumps)}")
}

@Composable
private fun CapitalRouteSummary(state: CapitalRouteUiState) {
    val strings = LocalAppStrings.current
    when (val outcome = state.outcome) {
        null -> Unit
        is CapitalRouteOutcome.Found -> {
            Text(
                "${strings.route.capitalJumpCount(outcome.route.totalJumps)} · " +
                    strings.route.totalDistanceLy(outcome.route.totalDistanceLy),
                color = EveColors.Important,
            )
            Text(state.routeSystemNames.joinToString(" → "), style = MaterialTheme.typography.bodySmall)
            outcome.route.legs.forEach { leg ->
                Text(
                    "${leg.fromSystemId} → ${leg.toSystemId} · " +
                        strings.route.jumpDistanceLy(leg.distanceLy),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        is CapitalRouteOutcome.SameSystem -> Text(strings.route.sameCapitalSystem)
        is CapitalRouteOutcome.Unreachable -> Text(strings.route.capitalRouteUnreachable, color = EveColors.Warning)
        is CapitalRouteOutcome.InvalidEndpoint -> Text(strings.route.invalidCapitalEndpoints, color = EveColors.Error)
        is CapitalRouteOutcome.IneligibleEndpoint -> Text(
            strings.route.capitalEndpointVerdict(outcome.endpoint.name, outcome.verdict.userFacingReason()),
            color = EveColors.Warning,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun EligibilityVerdict.userFacingReason(): String = when (this) {
    EligibilityVerdict.Eligible -> error("Eligible endpoint must not produce an ineligible route outcome")
    is EligibilityVerdict.Ineligible -> reason
    is EligibilityVerdict.Unknown -> reason
}
