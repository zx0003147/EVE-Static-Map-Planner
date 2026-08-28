package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.control.AiControlStatus
import dev.evestaticmapplanner.featurepack.FeaturePackInstallationState
import dev.evestaticmapplanner.featurepack.FeaturePackManagerItem
import dev.evestaticmapplanner.featurepack.FeaturePackManagerViewModel
import dev.evestaticmapplanner.featurepack.FeaturePackRuntimeState
import dev.evestaticmapplanner.feature.api.OverlayState
import java.util.Locale

@Composable
fun PreferencesWindow(
    currentZoom: Double?,
    preferences: AppPreferences,
    onMapDisplayChange: (MapDisplayPreferences) -> Unit,
    onMarkerChange: (MarkerPreferences) -> Unit,
    aiControlStatus: AiControlStatus,
    aiControlError: String?,
    featurePackManagerViewModel: FeaturePackManagerViewModel,
    overlayState: OverlayState,
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
        Surface(
            color = Color(0xFF15212D),
            contentColor = Color(0xFFD7E6F2),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(20.dp),
            ) {
                Text("Preferences", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PreferencesCategory.entries.forEach { item ->
                            TextButton(
                                onClick = { category = item },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(item.label) }
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
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
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF314252))
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
}

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
        color = Color(0xFFAAB9C7),
    )
    if (uiState.overlays.isEmpty()) {
        Text("No Feature Pack overlays are currently available.", color = Color(0xFFAAB9C7))
    }
    uiState.overlays.forEach { item ->
        HorizontalDivider(color = Color(0xFF314252))
        PreferenceCheckbox(item.name, item.enabled) { enabled ->
            onChange(preferences.withEnabled(item.key, enabled))
        }
        Text("Source: ${item.providerName}", color = Color(0xFFAAB9C7))
        item.description?.let { Text(it, color = Color(0xFFAAB9C7)) }
        item.providerDescription?.let { Text("Provider: $it", color = Color(0xFF71808D)) }
    }
    TextButton(onClick = onReset, enabled = preferences.disabledLayers.isNotEmpty()) {
        Text("Enable All Overlays")
    }
    if (uiState.showSovereigntyLogoPreferences) {
        HorizontalDivider(color = Color(0xFF314252))
        Text("Sovereignty", style = MaterialTheme.typography.titleSmall)
        Text("Sovereignty Logo Emphasis Zoom", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Choose the zoom level where alliance logos transition between background watermarks and " +
                "bright political-map emblems.",
            color = Color(0xFFAAB9C7),
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
        color = Color(0xFF71808D),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun FeaturePacksPreferencesContent(viewModel: FeaturePackManagerViewModel) {
    val state by viewModel.state.collectAsState()
    var removePending by remember { mutableStateOf<FeaturePackManagerItem?>(null) }
    LaunchedEffect(viewModel) { viewModel.refresh() }

    Text("Feature Packs", style = MaterialTheme.typography.titleMedium)
    Text(
        "Installed Packs are local to this Windows user. New Packs are disabled by default.",
        color = Color(0xFFAAB9C7),
    )
    state.discoveryErrors.forEach { Text(it, color = Color(0xFFFFB4AB)) }
    if (state.initialized && state.packs.isEmpty() && state.discoveryErrors.isEmpty()) {
        Text("No Feature Packs are installed.", color = Color(0xFFAAB9C7))
    }
    state.packs.forEach { item ->
        val pack = item.pack
        HorizontalDivider(color = Color(0xFF314252))
        Text(pack.displayName, style = MaterialTheme.typography.titleSmall)
        Text("ID: ${pack.packId.value}", color = Color(0xFFAAB9C7))
        Text("Version: ${pack.version?.value ?: "Unavailable"}")
        Text("Publisher: ${pack.publisher ?: "Unavailable"}")
        Text("Path: ${pack.path}", color = Color(0xFFAAB9C7))
        Text(
            "Status: " + when {
                pack.installationState == FeaturePackInstallationState.MISSING_JAR -> "Missing pack.jar"
                pack.installationState == FeaturePackInstallationState.INVALID_PACK -> "Invalid Pack"
                pack.installationState == FeaturePackInstallationState.INCOMPATIBLE -> "Incompatible"
                item.runtimeState == FeaturePackRuntimeState.ENABLED -> "Enabled"
                else -> "Disabled"
            },
        )
        pack.lastError?.let { Text(it, color = Color(0xFFFFB4AB)) }
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
private fun MapDisplayPreferencesContent(
    currentZoom: Double?,
    mapDisplay: MapDisplayPreferences,
    onChange: (MapDisplayPreferences) -> Unit,
    onReset: () -> Unit,
) {
    Text("Map Display", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Current Zoom", color = Color(0xFFAAB9C7))
        Text(currentZoom?.let { formatValue(it, 2) + "x" } ?: "—")
    }
    NumericPreferenceSlider(
        "Constellation Zoom Threshold",
        mapDisplay.constellationZoomThreshold,
        "x",
        2,
        THRESHOLD_MIN..(mapDisplay.systemZoomThreshold - THRESHOLD_MIN_GAP).coerceAtLeast(THRESHOLD_MIN),
        isValid = { it >= THRESHOLD_MIN && it < mapDisplay.systemZoomThreshold },
    ) { onChange(mapDisplay.copy(constellationZoomThreshold = it)) }
    NumericPreferenceSlider(
        "System Zoom Threshold",
        mapDisplay.systemZoomThreshold,
        "x",
        2,
        (mapDisplay.constellationZoomThreshold + THRESHOLD_MIN_GAP).coerceAtMost(THRESHOLD_MAX)..THRESHOLD_MAX,
        isValid = { it > mapDisplay.constellationZoomThreshold && it <= THRESHOLD_MAX },
    ) { onChange(mapDisplay.copy(systemZoomThreshold = it)) }
    HorizontalDivider(color = Color(0xFF314252))
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
    PreferenceCheckbox("Show markers", preferences.showMarkers) {
        onChange(preferences.copy(showMarkers = it))
    }
    PreferenceCheckbox("Show marker names", preferences.showMarkerNames, preferences.showMarkers) {
        onChange(preferences.copy(showMarkerNames = it))
    }
    HorizontalDivider(color = Color(0xFF314252))
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
        color = Color(0xFFAAB9C7),
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
            is AiControlStatus.Error, AiControlStatus.AlreadyActive -> Color(0xFFFFB4AB)
            else -> Color(0xFFAAB9C7)
        },
    )
    if (preferenceError != null) Text(preferenceError, color = Color(0xFFFFB4AB))
    Text(
        "When enabled, a new authenticated local-only control session starts after the map is ready.",
        color = Color(0xFFAAB9C7),
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
        Text(label, color = if (enabled) Color.Unspecified else Color(0xFF71808D))
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
                color = if (enabled) Color.Unspecified else Color(0xFF71808D),
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
