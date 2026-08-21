package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.util.Locale

@Composable
fun PreferencesWindow(
    currentZoom: Double?,
    preferences: AppPreferences,
    onMapDisplayChange: (MapDisplayPreferences) -> Unit,
    onMarkerChange: (MarkerPreferences) -> Unit,
    onResetMapDisplay: () -> Unit,
    onResetMarker: () -> Unit,
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
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF314252))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

private enum class PreferencesCategory(val label: String) {
    MAP_DISPLAY("Map Display"),
    MARKER("Marker"),
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
    TextButton(onClick = onReset) { Text("Reset Marker") }
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
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value.coerceIn(sliderRange).toFloat(),
                onValueChange = { onValueChange(it.toDouble()) },
                valueRange = sliderRange.start.toFloat()..sliderRange.endInclusive.toFloat(),
                steps = steps,
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
