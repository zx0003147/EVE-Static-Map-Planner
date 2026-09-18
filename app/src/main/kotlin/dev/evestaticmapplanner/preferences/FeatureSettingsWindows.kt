package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.minimap.MiniMapHudRuntimeState
import dev.evestaticmapplanner.ui.EveDivider
import dev.evestaticmapplanner.ui.EveTextButton
import dev.evestaticmapplanner.ui.EveVerticalScrollColumn
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface

internal data class FeatureSettingsWindowState(
    val isOpen: Boolean = false,
    val focusRequest: Int = 0,
) {
    fun show(): FeatureSettingsWindowState = copy(
        isOpen = true,
        focusRequest = focusRequest + 1,
    )

    fun close(): FeatureSettingsWindowState = copy(isOpen = false)
}

@Composable
internal fun MiniMapSettingsWindow(
    preferences: MiniMapPreferences,
    onChange: (MiniMapPreferences) -> Unit,
    hudRuntimeState: MiniMapHudRuntimeState,
    onReset: () -> Unit,
    focusRequest: Int,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    FeatureSettingsWindow(
        title = strings.mainShell.miniMapSettings,
        width = 500.dp,
        height = 680.dp,
        focusRequest = focusRequest,
        onDismiss = onDismiss,
    ) {
        MiniMapPreferencesContent(
            preferences = preferences,
            onChange = onChange,
            hudRuntimeState = hudRuntimeState,
            onReset = onReset,
        )
    }
}

@Composable
internal fun MarkerSettingsWindow(
    preferences: MarkerPreferences,
    onChange: (MarkerPreferences) -> Unit,
    onReset: () -> Unit,
    focusRequest: Int,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    FeatureSettingsWindow(
        title = strings.mainShell.markerSettings,
        width = 500.dp,
        height = 620.dp,
        focusRequest = focusRequest,
        onDismiss = onDismiss,
    ) {
        MarkerPreferencesContent(
            preferences = preferences,
            onChange = onChange,
            onReset = onReset,
        )
    }
}

@Composable
private fun FeatureSettingsWindow(
    title: String,
    width: Dp,
    height: Dp,
    focusRequest: Int,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val strings = LocalAppStrings.current
    val windowState = rememberWindowState(width = width, height = height)
    Window(
        onCloseRequest = onDismiss,
        title = title,
        state = windowState,
    ) {
        EveWindowChrome(window)
        LaunchedEffect(focusRequest) {
            windowState.isMinimized = false
            window.isVisible = true
            window.toFront()
            window.requestFocus()
        }
        EveWindowSurface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EveVerticalScrollColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content()
                }
                EveDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EveTextButton(onClick = onDismiss) { Text(strings.common.close) }
                }
            }
        }
    }
}
