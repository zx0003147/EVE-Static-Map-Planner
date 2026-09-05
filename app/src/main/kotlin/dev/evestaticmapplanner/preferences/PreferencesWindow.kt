package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.control.AiControlStatus
import dev.evestaticmapplanner.featurepack.FeaturePackInstallationState
import dev.evestaticmapplanner.featurepack.FeaturePackManagerItem
import dev.evestaticmapplanner.featurepack.FeaturePackManagerViewModel
import dev.evestaticmapplanner.featurepack.FeaturePackRuntimeState
import dev.evestaticmapplanner.featurepack.PackControlActionKey
import dev.evestaticmapplanner.featurepack.PackControlActionUiState
import dev.evestaticmapplanner.feature.api.PackControlActionStatus
import dev.evestaticmapplanner.feature.api.PackControlSeverity
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.shared.SharedAdminUiState
import dev.evestaticmapplanner.shared.SharedMapMembersDialog
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.ui.EveCheckbox as Checkbox
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDivider as HorizontalDivider
import dev.evestaticmapplanner.ui.EveDropdownMenu as DropdownMenu
import dev.evestaticmapplanner.ui.EveDropdownMenuItem as DropdownMenuItem
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveVerticalScrollColumn
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun PreferencesWindow(
    currentZoom: Double?,
    preferences: AppPreferences,
    onMapDisplayChange: (MapDisplayPreferences) -> Unit,
    onMarkerChange: (MarkerPreferences) -> Unit,
    aiControlStatus: AiControlStatus,
    aiControlError: String?,
    featurePackManagerViewModel: FeaturePackManagerViewModel,
    overlayState: OverlayState,
    sharedMapState: SharedMapState,
    sharedMapOperationError: String?,
    sharedAdminState: SharedAdminUiState,
    onSharedMapConnect: (String, SecretValue, String) -> Unit,
    onSharedMapWorkspaceChange: (String) -> Unit,
    onSharedMapRefresh: () -> Unit,
    onSharedMapDisconnect: () -> Unit,
    onSharedMapClearError: () -> Unit,
    onSharedMapLoadMembers: () -> Unit,
    onSharedMapCreateMember: (String, SharedWorkspaceRole) -> Boolean,
    onSharedMapChangeMemberRole: (String, Long, SharedWorkspaceRole) -> Boolean,
    onSharedMapRemoveMember: (String, Long) -> Boolean,
    onSharedMapCreateInvite: (String, Long) -> Boolean,
    onSharedMapClearAdminError: () -> Unit,
    onSharedMapClearInvite: () -> Unit,
    onOverlayVisibilityChange: (OverlayVisibilityPreferences) -> Unit,
    onAiControlChange: (Boolean) -> Unit,
    onAiSavedMarkerAccessChange: (Boolean) -> Unit,
    onResetMapDisplay: () -> Unit,
    onResetMarker: () -> Unit,
    onResetAiControl: () -> Unit,
    onResetOverlayVisibility: () -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember { mutableStateOf(PreferencesCategory.MAP_DISPLAY) }
    Window(
        onCloseRequest = onDismiss,
        title = "Preferences",
        state = rememberWindowState(width = 650.dp, height = 720.dp),
    ) {
        EveWindowChrome(window)
        EveWindowSurface(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(18.dp),
            ) {
                Text("Preferences", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PreferencesCategory.entries.forEach { item ->
                            TextButton(
                                onClick = { category = item },
                                selected = category == item,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(item.label) }
                        }
                    }
                    EveVerticalScrollColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .padding(start = PREFERENCES_CONTENT_START_GUTTER),
                    ) {
                        when (category) {
                            PreferencesCategory.MAP_DISPLAY -> MapDisplayPreferencesContent(
                                currentZoom,
                                preferences.mapDisplay,
                                onMapDisplayChange,
                                onResetMapDisplay,
                            )
                            PreferencesCategory.MARKER -> MarkerPreferencesContent(
                                preferences.marker,
                                onMarkerChange,
                                onResetMarker,
                            )
                            PreferencesCategory.AI_CONTROL -> AiControlPreferencesContent(
                                preferences.aiControl,
                                aiControlStatus,
                                aiControlError,
                                onAiControlChange,
                                onAiSavedMarkerAccessChange,
                                onResetAiControl,
                            )
                            PreferencesCategory.FEATURE_PACKS -> FeaturePacksPreferencesContent(
                                featurePackManagerViewModel,
                            )
                            PreferencesCategory.OVERLAYS -> OverlayPreferencesContent(
                                overlayState,
                                preferences.overlayVisibility,
                                preferences.mapDisplay,
                                onOverlayVisibilityChange,
                                onMapDisplayChange,
                                onResetOverlayVisibility,
                            )
                            PreferencesCategory.SHARED_MAP -> SharedMapPreferencesContent(
                                preferences.sharedMap,
                                sharedMapState,
                                sharedMapOperationError,
                                sharedAdminState,
                                onSharedMapConnect,
                                onSharedMapWorkspaceChange,
                                onSharedMapRefresh,
                                onSharedMapDisconnect,
                                onSharedMapClearError,
                                onSharedMapLoadMembers,
                                onSharedMapCreateMember,
                                onSharedMapChangeMemberRole,
                                onSharedMapRemoveMember,
                                onSharedMapCreateInvite,
                                onSharedMapClearAdminError,
                                onSharedMapClearInvite,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onResetAll) { Text("Reset All Preferences") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

private enum class PreferencesCategory(val label: String) {
    MAP_DISPLAY("Map Display"),
    MARKER("Marker"),
    AI_CONTROL("AI Control"),
    FEATURE_PACKS("Feature Packs"),
    OVERLAYS("Overlays"),
    SHARED_MAP("Shared Map"),
}

internal val PREFERENCES_CONTENT_START_GUTTER = 24.dp

@Composable
private fun SharedMapPreferencesContent(
    preferences: SharedMapPreferences,
    state: SharedMapState,
    operationError: String?,
    adminState: SharedAdminUiState,
    onConnect: (String, SecretValue, String) -> Unit,
    onWorkspaceChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onClearError: () -> Unit,
    onLoadMembers: () -> Unit,
    onCreateMember: (String, SharedWorkspaceRole) -> Boolean,
    onChangeMemberRole: (String, Long, SharedWorkspaceRole) -> Boolean,
    onRemoveMember: (String, Long) -> Boolean,
    onCreateInvite: (String, Long) -> Boolean,
    onClearAdminError: () -> Unit,
    onClearInvite: () -> Unit,
) {
    var serverUrl by remember(preferences.serverUrl) { mutableStateOf(preferences.serverUrl.orEmpty()) }
    var deviceName by remember(preferences.deviceName) { mutableStateOf(preferences.deviceName) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var workspaceMenuExpanded by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    val canConnect = state.connectionState in setOf(
        SharedConnectionState.DISCONNECTED,
        SharedConnectionState.AUTH_REQUIRED,
        SharedConnectionState.FORBIDDEN,
        SharedConnectionState.PROTOCOL_UNSUPPORTED,
    ) || (state.connectionState == SharedConnectionState.OFFLINE && state.selectedWorkspaceId == null)
    val canAdmin = state.connectionState == SharedConnectionState.ONLINE &&
        state.identity?.workspace?.role == SharedWorkspaceRole.ADMIN
    LaunchedEffect(canAdmin, state.selectedWorkspaceId) {
        if (!canAdmin) {
            showMembers = false
            onClearInvite()
        }
    }

    Text("Shared Map", style = MaterialTheme.typography.titleMedium)
    Text(
        "Connect to a Shared Map Server and keep its read-only marker snapshot synchronized.",
        color = EveColors.SecondaryText,
    )
    OutlinedTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it },
        label = { Text("Server URL") },
        placeholder = { Text("https://map.example.com") },
        singleLine = true,
        enabled = canConnect,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = deviceName,
        onValueChange = { if (it.codePointCount(0, it.length) <= 80) deviceName = it },
        label = { Text("Device name") },
        singleLine = true,
        enabled = canConnect,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Status: ${sharedMapStatusLabel(state)}")
    state.statusMessage?.let { Text(it, color = EveColors.SecondaryText) }
    operationError?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onClearError) { Text("Dismiss") }
    }

    if (state.workspaces.isNotEmpty()) {
        Text("Workspace", style = MaterialTheme.typography.titleSmall)
        Box {
            TextButton(
                onClick = { workspaceMenuExpanded = true },
                enabled = state.workspaces.size > 1,
            ) {
                Text(state.selectedWorkspace?.name ?: "Select Workspace")
            }
            DropdownMenu(
                expanded = workspaceMenuExpanded,
                onDismissRequest = { workspaceMenuExpanded = false },
            ) {
                state.workspaces.forEach { workspace ->
                    DropdownMenuItem(
                        text = { Text(workspace.name) },
                        onClick = {
                            workspaceMenuExpanded = false
                            onWorkspaceChange(workspace.workspaceId)
                        },
                    )
                }
            }
        }
    }
    state.identity?.workspace?.role?.let { role ->
        Text("Role: ${role.name.lowercase().replaceFirstChar(Char::uppercase)}")
    }
    Text("Last sync: ${state.lastSuccessfulSyncAt?.let(::formatLocalInstant) ?: "Never"}")
    Text("Shared markers: ${state.markerCount}")
    state.snapshot?.let { Text("Revision: ${it.revision}", color = EveColors.SecondaryText) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { showInviteDialog = true },
            enabled = canConnect && serverUrl.isNotBlank(),
        ) { Text("Connect with Invite…") }
        TextButton(
            onClick = onRefresh,
            enabled = state.selectedWorkspaceId != null && state.connectionState in setOf(
                SharedConnectionState.ONLINE,
                SharedConnectionState.DEGRADED,
                SharedConnectionState.OFFLINE,
            ),
        ) { Text("Refresh Now") }
    }
    TextButton(
        onClick = onDisconnect,
        enabled = state.serverUrl != null && state.connectionState != SharedConnectionState.CONNECTING,
    ) { Text("Disconnect") }
    if (state.identity?.workspace?.role == SharedWorkspaceRole.ADMIN) {
        TextButton(
            onClick = { showMembers = true },
            enabled = canAdmin,
        ) { Text("Manage Members…") }
        if (!canAdmin) {
            Text("Member management is available only while Shared Map is online.", color = EveColors.SecondaryText)
        }
    }

    if (showInviteDialog) {
        InviteConnectDialog(
            serverUrl = serverUrl,
            onDismiss = { showInviteDialog = false },
            onSubmit = { inviteText ->
                val secret = runCatching { SecretValue.from(inviteText) }.getOrNull()
                if (secret != null) {
                    try {
                        onConnect(serverUrl, secret, deviceName)
                    } finally {
                        secret.close()
                    }
                }
                showInviteDialog = false
            },
        )
    }
    if (showMembers) {
        SharedMapMembersDialog(
            workspaceName = state.selectedWorkspace?.name ?: "Shared Map",
            state = adminState,
            canAdmin = canAdmin,
            onLoad = onLoadMembers,
            onCreateMember = onCreateMember,
            onChangeRole = onChangeMemberRole,
            onRemoveMember = onRemoveMember,
            onCreateInvite = onCreateInvite,
            onClearError = onClearAdminError,
            onClearInvite = onClearInvite,
            onDismiss = {
                showMembers = false
                onClearInvite()
            },
        )
    }
}

@Composable
private fun InviteConnectDialog(
    serverUrl: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var invite by remember { mutableStateOf("") }
    fun clearAndDismiss() {
        invite = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::clearAndDismiss,
        title = { Text("Connect to Shared Map") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(serverUrl, color = EveColors.SecondaryText)
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it },
                    label = { Text("One-time invite") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("The invite is used once and is not saved.", color = EveColors.SecondaryText)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val submitted = invite
                    invite = ""
                    onSubmit(submitted)
                },
                enabled = invite.isNotBlank(),
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = ::clearAndDismiss) { Text("Cancel") } },
    )
}

internal fun sharedMapStatusLabel(state: SharedConnectionState): String = when (state) {
    SharedConnectionState.DISCONNECTED -> "Not configured"
    SharedConnectionState.CONNECTING -> "Connecting"
    SharedConnectionState.ONLINE -> "Connected"
    SharedConnectionState.DEGRADED -> "Degraded — showing stale data"
    SharedConnectionState.OFFLINE -> "Offline"
    SharedConnectionState.AUTH_REQUIRED -> "Authentication required"
    SharedConnectionState.FORBIDDEN -> "Access removed"
    SharedConnectionState.PROTOCOL_UNSUPPORTED -> "Incompatible server"
}

internal fun sharedMapStatusLabel(state: SharedMapState): String =
    if (state.connectionState == SharedConnectionState.DISCONNECTED && state.serverUrl != null) {
        "Disconnected"
    } else {
        sharedMapStatusLabel(state.connectionState)
    }

private val SHARED_MAP_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

private fun formatLocalInstant(instant: java.time.Instant): String =
    SHARED_MAP_TIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))

@Composable
private fun OverlayPreferencesContent(
    overlayState: OverlayState,
    preferences: OverlayVisibilityPreferences,
    mapDisplay: MapDisplayPreferences,
    onChange: (OverlayVisibilityPreferences) -> Unit,
    onMapDisplayChange: (MapDisplayPreferences) -> Unit,
    onReset: () -> Unit,
) {
    val uiState = remember(overlayState, preferences) {
        OverlayManagementUiStateBuilder.build(overlayState, preferences)
    }

    Text("Map Overlays", style = MaterialTheme.typography.titleMedium)
    Text(
        "Visibility changes affect map presentation only. Feature Packs remain enabled.",
        color = EveColors.SecondaryText,
    )
    if (uiState.overlays.isEmpty()) {
        Text("No Feature Pack overlays are currently available.", color = EveColors.SecondaryText)
    }
    uiState.overlays.forEach { item ->
        HorizontalDivider()
        PreferenceCheckbox(item.name, item.enabled) { enabled ->
            onChange(preferences.withEnabled(item.key, enabled))
        }
        Text("Source: ${item.providerName}", color = EveColors.SecondaryText)
        item.description?.let { Text(it, color = EveColors.SecondaryText) }
        item.providerDescription?.let { Text("Provider: $it", color = EveColors.DisabledText) }
    }
    TextButton(onClick = onReset, enabled = preferences.disabledLayers.isNotEmpty()) {
        Text("Enable All Overlays")
    }
    HorizontalDivider()
    Text("Real 3D Stargate Visibility", style = MaterialTheme.typography.titleSmall)
    Text(
        "Controls normal stargate connection visibility in Real 3D mode.",
        color = EveColors.SecondaryText,
    )
    PreferenceCheckbox(
        "Focused Region + Adjacent Regions",
        mapDisplay.real3DStargateVisibilityFilteringEnabled,
    ) { enabled ->
        onMapDisplayChange(mapDisplay.copy(real3DStargateVisibilityFilteringEnabled = enabled))
    }
    if (uiState.showSovereigntyLogoPreferences) {
        HorizontalDivider()
        Text("Sovereignty", style = MaterialTheme.typography.titleSmall)
        Text("Sovereignty Logo Emphasis Zoom", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Choose the zoom level where alliance logos transition between background watermarks and " +
                "bright political-map emblems.",
            color = EveColors.SecondaryText,
        )
        SovereigntyLogoEmphasisZoomPreference(mapDisplay.sovereigntyLogoEmphasisZoom) { emphasisZoom ->
            onMapDisplayChange(mapDisplay.copy(sovereigntyLogoEmphasisZoom = emphasisZoom))
        }
    }
}

@Composable
private fun SovereigntyLogoEmphasisZoomPreference(
    value: Double,
    onValueChange: (Double) -> Unit,
) {
    var draft by remember { mutableStateOf(formatValue(value, 2)) }
    var showError by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value, isFocused, showError) {
        if (!isFocused && !showError) {
            draft = formatValue(value, 2)
        }
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text
            val parsed = text.toDoubleOrNull()
            val valid = parsed != null && parsed.isFinite() &&
                parsed in MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM..MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM
            showError = false
            if (valid) onValueChange(checkNotNull(parsed))
        },
        suffix = { Text("x") },
        supportingText = if (showError) ({
            Text(
                "Enter ${formatValue(MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM, 2)}–" +
                    "${formatValue(MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM, 2)}.",
            )
        }) else null,
        singleLine = true,
        isError = showError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.width(160.dp).onFocusChanged { focusState ->
            val wasFocused = isFocused
            isFocused = focusState.isFocused
            if (!wasFocused && focusState.isFocused) {
                showError = false
            } else if (wasFocused && !focusState.isFocused) {
                val parsed = draft.toDoubleOrNull()
                val valid = parsed != null && parsed.isFinite() &&
                    parsed in MIN_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM..MAX_SOVEREIGNTY_LOGO_EMPHASIS_ZOOM
                showError = !valid
                if (valid) draft = formatValue(checkNotNull(parsed), 2)
            }
        },
    )
    Text(
        "Full map range 0.01x–250x; 0.05x steps are recommended near overview zoom.",
        color = EveColors.DisabledText,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun FeaturePacksPreferencesContent(viewModel: FeaturePackManagerViewModel) {
    val state by viewModel.state.collectAsState()
    val controls by viewModel.controlsState.collectAsState()
    var removePending by remember { mutableStateOf<FeaturePackManagerItem?>(null) }
    LaunchedEffect(viewModel) { viewModel.refresh() }

    Text("Feature Packs", style = MaterialTheme.typography.titleMedium)
    Text(
        "Installed Packs are local to this Windows user. New Packs are disabled by default.",
        color = EveColors.SecondaryText,
    )
    state.discoveryErrors.forEach { Text(it, color = EveColors.Error) }
    if (state.initialized && state.packs.isEmpty() && state.discoveryErrors.isEmpty()) {
        Text("No Feature Packs are installed.", color = EveColors.SecondaryText)
    }
    state.packs.forEach { item ->
        val pack = item.pack
        HorizontalDivider()
        Text(pack.displayName, style = MaterialTheme.typography.titleSmall)
        Text("ID: ${pack.packId.value}", color = EveColors.SecondaryText)
        Text("Version: ${pack.version?.value ?: "Unavailable"}")
        Text("Publisher: ${pack.publisher ?: "Unavailable"}")
        Text("Path: ${pack.path}", color = EveColors.SecondaryText)
        Text(
            "Status: " + when {
                pack.installationState == FeaturePackInstallationState.MISSING_JAR -> "Missing pack.jar"
                pack.installationState == FeaturePackInstallationState.INVALID_PACK -> "Invalid Pack"
                pack.installationState == FeaturePackInstallationState.INCOMPATIBLE -> "Incompatible"
                item.runtimeState == FeaturePackRuntimeState.ENABLED -> "Enabled"
                else -> "Disabled"
            },
        )
        pack.lastError?.let { Text(it, color = EveColors.Error) }
        controls.firstOrNull { it.packId == pack.packId }?.let { control ->
            Text("Controls", style = MaterialTheme.typography.titleSmall)
            Text(
                control.primaryText,
                color = when (control.severity) {
                    PackControlSeverity.NORMAL -> EveColors.PrimaryText
                    PackControlSeverity.WARNING -> EveColors.Warning
                    PackControlSeverity.ERROR -> EveColors.Error
                },
            )
            control.secondaryText?.let { secondary ->
                Text(secondary, color = EveColors.SecondaryText)
            }
            PackControlActionList(control.actions, control.busyActionId, viewModel::invokeControl)
            control.actions.mapNotNull { it.description }.distinct().forEach { description ->
                Text(description, color = EveColors.DisabledText, style = MaterialTheme.typography.bodySmall)
            }
            control.lastMessage?.let { message ->
                Text(
                    message,
                    color = if (control.lastStatus == PackControlActionStatus.FAILED) {
                        EveColors.Error
                    } else {
                        EveColors.SecondaryText
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.runtimeState == FeaturePackRuntimeState.ENABLED) {
                TextButton(onClick = { viewModel.setEnabled(pack.packId, false) }) { Text("Disable") }
            } else {
                TextButton(
                    enabled = pack.installationState == FeaturePackInstallationState.INSTALLED,
                    onClick = { viewModel.setEnabled(pack.packId, true) },
                ) { Text("Enable") }
            }
            TextButton(onClick = { removePending = item }) { Text("Remove") }
        }
    }

    removePending?.let { item ->
        AlertDialog(
            onDismissRequest = { removePending = null },
            title = { Text("Remove ${item.pack.displayName}?") },
            text = { Text("This deletes the installed Pack directory. Pack-owned data is retained.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(item.pack.packId)
                    removePending = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removePending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun PackControlActionList(
    actions: List<PackControlActionUiState>,
    busyActionId: String?,
    onInvoke: (PackControlActionKey) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        actions.forEach { action ->
            TextButton(
                enabled = action.enabled,
                onClick = { onInvoke(action.key) },
            ) {
                Text(if (busyActionId == action.key.actionId) "${action.label}…" else action.label)
            }
        }
    }
}

@Composable
private fun MapDisplayPreferencesContent(
    currentZoom: Double?,
    mapDisplay: MapDisplayPreferences,
    onChange: (MapDisplayPreferences) -> Unit,
    onReset: () -> Unit,
) {
    Text("Map Display", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Current Zoom", color = EveColors.SecondaryText)
        Text(currentZoom?.let { formatValue(it, 2) + "x" } ?: "—")
    }
    NumericPreferenceSlider(
        "2D Constellation Zoom Threshold",
        mapDisplay.constellationZoomThreshold,
        "x",
        2,
        THRESHOLD_MIN..(mapDisplay.systemZoomThreshold - THRESHOLD_MIN_GAP).coerceAtLeast(THRESHOLD_MIN),
        isValid = { it >= THRESHOLD_MIN && it < mapDisplay.systemZoomThreshold },
    ) { onChange(mapDisplay.copy(constellationZoomThreshold = it)) }
    NumericPreferenceSlider(
        "2D System Zoom Threshold",
        mapDisplay.systemZoomThreshold,
        "x",
        2,
        (mapDisplay.constellationZoomThreshold + THRESHOLD_MIN_GAP).coerceAtMost(THRESHOLD_MAX)..THRESHOLD_MAX,
        isValid = { it > mapDisplay.constellationZoomThreshold && it <= THRESHOLD_MAX },
    ) { onChange(mapDisplay.copy(systemZoomThreshold = it)) }
    NumericPreferenceSlider(
        "3D Constellation Zoom Threshold",
        mapDisplay.real3DConstellationScaleThreshold,
        "x",
        2,
        THRESHOLD_MIN..(mapDisplay.real3DSystemScaleThreshold - THRESHOLD_MIN_GAP).coerceAtLeast(THRESHOLD_MIN),
        isValid = { it >= THRESHOLD_MIN && it < mapDisplay.real3DSystemScaleThreshold },
    ) { onChange(mapDisplay.copy(real3DConstellationScaleThreshold = it)) }
    NumericPreferenceSlider(
        "3D System Zoom Threshold",
        mapDisplay.real3DSystemScaleThreshold,
        "x",
        2,
        (mapDisplay.real3DConstellationScaleThreshold + THRESHOLD_MIN_GAP).coerceAtMost(THRESHOLD_MAX)..THRESHOLD_MAX,
        isValid = { it > mapDisplay.real3DConstellationScaleThreshold && it <= THRESHOLD_MAX },
    ) { onChange(mapDisplay.copy(real3DSystemScaleThreshold = it)) }
    HorizontalDivider()
    FontPreferenceSliders(mapDisplay, onChange)
    TextButton(onClick = onReset) { Text("Reset Map Display") }
}

@Composable
private fun FontPreferenceSliders(mapDisplay: MapDisplayPreferences, onChange: (MapDisplayPreferences) -> Unit) {
    NumericPreferenceSlider("Region Primary Font Size", mapDisplay.regionPrimaryFontSizeSp.toDouble(), "sp", 0,
        FONT_SIZE_MIN..FONT_SIZE_MAX, FONT_SIZE_STEPS, ::validFontSize) {
        onChange(mapDisplay.copy(regionPrimaryFontSizeSp = it.toFloat()))
    }
    NumericPreferenceSlider("Region Background Font Size", mapDisplay.regionBackgroundFontSizeSp.toDouble(), "sp", 0,
        FONT_SIZE_MIN..FONT_SIZE_MAX, FONT_SIZE_STEPS, ::validFontSize) {
        onChange(mapDisplay.copy(regionBackgroundFontSizeSp = it.toFloat()))
    }
    NumericPreferenceSlider("Region Background Alpha", mapDisplay.regionBackgroundAlpha.toDouble(), decimals = 2,
        sliderRange = BACKGROUND_ALPHA_MIN..BACKGROUND_ALPHA_MAX, steps = BACKGROUND_ALPHA_STEPS,
        isValid = { it in BACKGROUND_ALPHA_MIN..BACKGROUND_ALPHA_MAX }) {
        onChange(mapDisplay.copy(regionBackgroundAlpha = it.toFloat()))
    }
    NumericPreferenceSlider("Constellation Font Size", mapDisplay.constellationFontSizeSp.toDouble(), "sp", 0,
        FONT_SIZE_MIN..FONT_SIZE_MAX, FONT_SIZE_STEPS, ::validFontSize) {
        onChange(mapDisplay.copy(constellationFontSizeSp = it.toFloat()))
    }
    NumericPreferenceSlider("System Font Size", mapDisplay.systemFontSizeSp.toDouble(), "sp", 0,
        FONT_SIZE_MIN..FONT_SIZE_MAX, FONT_SIZE_STEPS, ::validFontSize) {
        onChange(mapDisplay.copy(systemFontSizeSp = it.toFloat()))
    }
}

@Composable
private fun MarkerPreferencesContent(
    preferences: MarkerPreferences,
    onChange: (MarkerPreferences) -> Unit,
    onReset: () -> Unit,
) {
    Text("Marker", style = MaterialTheme.typography.titleMedium)
    PreferenceCheckbox("Show Local Markers", preferences.showMarkers) {
        onChange(preferences.copy(showMarkers = it))
    }
    PreferenceCheckbox("Show Shared Markers", preferences.showSharedMarkers) {
        onChange(preferences.copy(showSharedMarkers = it))
    }
    PreferenceCheckbox("Show Local Marker Names", preferences.showMarkerNames, preferences.showMarkers) {
        onChange(preferences.copy(showMarkerNames = it))
    }
    HorizontalDivider()
    Text("Saved Marker Appearance", style = MaterialTheme.typography.titleSmall)
    val appearance = preferences.savedMarkerAppearance
    NumericPreferenceSlider(
        label = "Outer Ring Radius",
        value = appearance.ringRadiusDp.toDouble(),
        suffix = "dp",
        decimals = 1,
        sliderRange = MIN_SAVED_MARKER_RING_RADIUS_DP.toDouble()..MAX_SAVED_MARKER_RING_RADIUS_DP.toDouble(),
        steps = 39,
        isValid = { it in MIN_SAVED_MARKER_RING_RADIUS_DP.toDouble()..MAX_SAVED_MARKER_RING_RADIUS_DP.toDouble() },
    ) { onChange(preferences.copy(savedMarkerAppearance = appearance.copy(ringRadiusDp = it.toFloat()))) }
    NumericPreferenceSlider(
        label = "Outer Ring Line Width",
        value = appearance.lineWidthDp.toDouble(),
        suffix = "dp",
        decimals = 1,
        sliderRange = MIN_SAVED_MARKER_LINE_WIDTH_DP.toDouble()..MAX_SAVED_MARKER_LINE_WIDTH_DP.toDouble(),
        steps = 39,
        isValid = { it in MIN_SAVED_MARKER_LINE_WIDTH_DP.toDouble()..MAX_SAVED_MARKER_LINE_WIDTH_DP.toDouble() },
    ) { onChange(preferences.copy(savedMarkerAppearance = appearance.copy(lineWidthDp = it.toFloat()))) }
    PreferenceCheckbox("Glow", appearance.glowEnabled) {
        onChange(preferences.copy(savedMarkerAppearance = appearance.copy(glowEnabled = it)))
    }
    NumericPreferenceSlider(
        label = "Glow Strength",
        value = appearance.glowStrength.toDouble(),
        decimals = 2,
        sliderRange = MIN_SAVED_MARKER_GLOW_STRENGTH.toDouble()..MAX_SAVED_MARKER_GLOW_STRENGTH.toDouble(),
        steps = 19,
        isValid = { it in MIN_SAVED_MARKER_GLOW_STRENGTH.toDouble()..MAX_SAVED_MARKER_GLOW_STRENGTH.toDouble() },
        enabled = appearance.glowEnabled,
    ) { onChange(preferences.copy(savedMarkerAppearance = appearance.copy(glowStrength = it.toFloat()))) }
    TextButton(onClick = onReset) { Text("Reset Marker") }
}

@Composable
private fun AiControlPreferencesContent(
    preferences: AiControlPreferences,
    status: AiControlStatus,
    preferenceError: String?,
    onChange: (Boolean) -> Unit,
    onSavedMarkerAccessChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    Text("AI Map Control", style = MaterialTheme.typography.titleMedium)
    PreferenceCheckbox("Enable AI Map Control", preferences.enabled, onCheckedChange = onChange)
    PreferenceCheckbox(
        "Allow AI to access saved markers",
        preferences.savedMarkerAccessEnabled,
        onCheckedChange = onSavedMarkerAccessChange,
    )
    Text(
        "Allows AI tools to read and create saved markers. AI cannot modify or delete existing saved markers.",
        color = EveColors.SecondaryText,
    )
    Text(
        when (status) {
            AiControlStatus.Disabled -> "Disabled"
            AiControlStatus.Starting -> "Starting…"
            AiControlStatus.Listening -> "Listening on localhost"
            AiControlStatus.AlreadyActive -> "Already Active in another app instance"
            is AiControlStatus.Error -> status.message
        },
        color = when (status) {
            is AiControlStatus.Error, AiControlStatus.AlreadyActive -> EveColors.Error
            else -> EveColors.SecondaryText
        },
    )
    if (preferenceError != null) Text(preferenceError, color = EveColors.Error)
    Text(
        "When enabled, a new authenticated local-only control session starts after the map is ready.",
        color = EveColors.SecondaryText,
    )
    TextButton(onClick = onReset) { Text("Reset AI Control") }
}

@Composable
private fun PreferenceCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(label, color = if (enabled) EveColors.PrimaryText else EveColors.DisabledText)
    }
}

@Composable
private fun NumericPreferenceSlider(
    label: String,
    value: Double,
    suffix: String = "",
    decimals: Int,
    sliderRange: ClosedFloatingPointRange<Double>,
    steps: Int = 0,
    isValid: (Double) -> Boolean,
    enabled: Boolean = true,
    onValueChange: (Double) -> Unit,
) {
    var draft by remember { mutableStateOf(formatValue(value, decimals)) }
    var invalid by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(value, decimals, isFocused) {
        if (!isFocused) {
            draft = formatValue(value, decimals)
            invalid = false
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) EveColors.PrimaryText else EveColors.DisabledText,
            )
            Slider(
                value = value.coerceIn(sliderRange).toFloat(),
                onValueChange = { onValueChange(it.toDouble()) },
                valueRange = sliderRange.start.toFloat()..sliderRange.endInclusive.toFloat(),
                steps = steps,
                enabled = enabled,
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { text ->
                draft = text
                val parsed = text.toDoubleOrNull()
                invalid = parsed == null || !isValid(parsed)
                if (!invalid) onValueChange(checkNotNull(parsed))
            },
            suffix = if (suffix.isEmpty()) null else ({ Text(suffix) }),
            singleLine = true,
            isError = invalid,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(104.dp).onFocusChanged { focusState ->
                val wasFocused = isFocused
                isFocused = focusState.isFocused
                if (wasFocused && !focusState.isFocused) {
                    draft = formatValue(value, decimals)
                    invalid = false
                }
            },
        )
    }
}

private fun validFontSize(value: Double): Boolean = value in FONT_SIZE_MIN..FONT_SIZE_MAX
private fun formatValue(value: Double, decimals: Int): String =
    String.format(Locale.ROOT, "%.${decimals}f", value)

private const val THRESHOLD_MIN = 0.1
private const val THRESHOLD_MAX = MAX_ZOOM_THRESHOLD
private const val THRESHOLD_MIN_GAP = 0.1
private const val FONT_SIZE_MIN = 8.0
private const val FONT_SIZE_MAX = 72.0
private const val FONT_SIZE_STEPS = 63
private const val BACKGROUND_ALPHA_MIN = 0.0
private const val BACKGROUND_ALPHA_MAX = 1.0
private const val BACKGROUND_ALPHA_STEPS = 99
