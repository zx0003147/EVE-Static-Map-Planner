package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.util.Locale

@Composable
fun PreferencesWindow(
    currentZoom: Double?,
    preferences: AppPreferences,
    onMapDisplayChange: (MapDisplayPreferences) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val mapDisplay = preferences.mapDisplay
    Window(
        onCloseRequest = onDismiss,
        title = "Preferences",
        state = rememberWindowState(width = 600.dp, height = 720.dp),
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    Text("Map Display", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Current Zoom", color = Color(0xFFAAB9C7))
                        Text(currentZoom?.let { formatValue(it, 2) + "x" } ?: "—")
                    }
                    NumericPreferenceSlider(
                        label = "Constellation Zoom Threshold",
                        value = mapDisplay.constellationZoomThreshold,
                        suffix = "x",
                        decimals = 2,
                        sliderRange = THRESHOLD_MIN..(
                            mapDisplay.systemZoomThreshold - THRESHOLD_MIN_GAP
                        ).coerceAtLeast(THRESHOLD_MIN),
                        isValid = { it >= THRESHOLD_MIN && it < mapDisplay.systemZoomThreshold },
                        onValueChange = {
                            onMapDisplayChange(mapDisplay.copy(constellationZoomThreshold = it))
                        },
                    )
                    NumericPreferenceSlider(
                        label = "System Zoom Threshold",
                        value = mapDisplay.systemZoomThreshold,
                        suffix = "x",
                        decimals = 2,
                        sliderRange = (mapDisplay.constellationZoomThreshold + THRESHOLD_MIN_GAP)
                            .coerceAtMost(THRESHOLD_MAX)..THRESHOLD_MAX,
                        isValid = { it > mapDisplay.constellationZoomThreshold && it <= THRESHOLD_MAX },
                        onValueChange = { onMapDisplayChange(mapDisplay.copy(systemZoomThreshold = it)) },
                    )
                    HorizontalDivider(color = Color(0xFF314252))
                    NumericPreferenceSlider(
                        label = "Region Primary Font Size",
                        value = mapDisplay.regionPrimaryFontSizeSp.toDouble(),
                        suffix = "sp",
                        decimals = 0,
                        sliderRange = FONT_SIZE_MIN..FONT_SIZE_MAX,
                        steps = FONT_SIZE_STEPS,
                        isValid = ::validFontSize,
                        onValueChange = { onMapDisplayChange(mapDisplay.copy(regionPrimaryFontSizeSp = it.toFloat())) },
                    )
                    NumericPreferenceSlider(
                        label = "Region Background Font Size",
                        value = mapDisplay.regionBackgroundFontSizeSp.toDouble(),
                        suffix = "sp",
                        decimals = 0,
                        sliderRange = FONT_SIZE_MIN..FONT_SIZE_MAX,
                        steps = FONT_SIZE_STEPS,
                        isValid = ::validFontSize,
                        onValueChange = { onMapDisplayChange(mapDisplay.copy(regionBackgroundFontSizeSp = it.toFloat())) },
                    )
                    NumericPreferenceSlider(
                        label = "Region Background Alpha",
                        value = mapDisplay.regionBackgroundAlpha.toDouble(),
                        decimals = 2,
                        sliderRange = BACKGROUND_ALPHA_MIN..BACKGROUND_ALPHA_MAX,
                        steps = BACKGROUND_ALPHA_STEPS,
                        isValid = { it in BACKGROUND_ALPHA_MIN..BACKGROUND_ALPHA_MAX },
                        onValueChange = { onMapDisplayChange(mapDisplay.copy(regionBackgroundAlpha = it.toFloat())) },
                    )
                    NumericPreferenceSlider(
                        label = "Constellation Font Size",
                        value = mapDisplay.constellationFontSizeSp.toDouble(),
                        suffix = "sp",
                        decimals = 0,
                        sliderRange = FONT_SIZE_MIN..FONT_SIZE_MAX,
                        steps = FONT_SIZE_STEPS,
                        isValid = ::validFontSize,
                        onValueChange = { onMapDisplayChange(mapDisplay.copy(constellationFontSizeSp = it.toFloat())) },
                    )
                    NumericPreferenceSlider(
                        label = "System Font Size",
                        value = mapDisplay.systemFontSizeSp.toDouble(),
                        suffix = "sp",
                        decimals = 0,
                        sliderRange = FONT_SIZE_MIN..FONT_SIZE_MAX,
                        steps = FONT_SIZE_STEPS,
                        isValid = ::validFontSize,
                        onValueChange = { onMapDisplayChange(mapDisplay.copy(systemFontSizeSp = it.toFloat())) },
                    )
                    TextButton(onClick = onResetDefaults) { Text("Reset to Defaults") }
                }
                HorizontalDivider(color = Color(0xFF314252))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
