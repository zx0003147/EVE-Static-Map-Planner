package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.ai.GlobalPushToTalkState
import dev.evestaticmapplanner.ai.GlobalPushToTalkStatus
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.localization.PreferencesMessage
import dev.evestaticmapplanner.localization.PreferencesText
import dev.evestaticmapplanner.localization.PreferencesUiMessage
import dev.evestaticmapplanner.localization.UiMessage
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.shortcut.ShortcutKey
import dev.evestaticmapplanner.shortcut.ShortcutModifier
import dev.evestaticmapplanner.shortcut.displayLabel
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlinx.coroutines.launch

@Composable
internal fun PushToTalkShortcutPreference(
    shortcut: KeyboardShortcut?,
    globalState: GlobalPushToTalkState,
    assistantOpen: Boolean,
    platformSupported: Boolean,
    onCaptureStateChanged: (Boolean) -> Unit,
    onShortcutChange: suspend (KeyboardShortcut?) -> Result<Unit>,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.preferences
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var captureActive by remember { mutableStateOf(false) }
    var awaitingRelease by remember { mutableStateOf(false) }
    var saveComplete by remember { mutableStateOf(false) }
    var keysDown by remember { mutableStateOf(emptySet<Key>()) }
    var inlineMessage by remember { mutableStateOf<UiMessage?>(null) }
    var captureError by remember { mutableStateOf<PreferencesText?>(null) }
    val captureActiveAtDispose by rememberUpdatedState(captureActive)

    fun finishCapture() {
        captureActive = false
        awaitingRelease = false
        saveComplete = false
        keysDown = emptySet()
        onCaptureStateChanged(false)
    }

    fun finishWhenReady() {
        if (awaitingRelease && saveComplete && keysDown.isEmpty()) finishCapture()
    }

    fun saveCaptured(value: KeyboardShortcut?) {
        awaitingRelease = true
        captureError = null
        if (value == shortcut) {
            saveComplete = true
            finishWhenReady()
            return
        }
        scope.launch {
            try {
                val result = runCatching { onShortcutChange(value) }.getOrElse(Result.Companion::failure)
                inlineMessage = result.fold(
                    onSuccess = {
                        PreferencesUiMessage(
                            if (value == null) PreferencesMessage.PUSH_TO_TALK_SHORTCUT_CLEARED
                            else PreferencesMessage.PUSH_TO_TALK_SHORTCUT_SAVED,
                        )
                    },
                    onFailure = { failure ->
                        PreferencesUiMessage(
                            PreferencesMessage.PUSH_TO_TALK_SHORTCUT_SAVE_FAILED,
                            technicalDetail = failure.message,
                        )
                    },
                )
            } finally {
                saveComplete = true
                finishWhenReady()
            }
        }
    }

    fun handleCaptureEvent(event: KeyEvent): Boolean {
        if (!captureActive) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.key in keysDown) return true
                keysDown = keysDown + event.key
                if (awaitingRelease) return true
                if (event.key == Key.Escape) {
                    awaitingRelease = true
                    saveComplete = true
                    captureError = null
                    true
                } else if (event.key.isShortcutModifier()) {
                    true
                } else {
                    val mapped = event.key.toShortcutKey()
                    if (mapped == null) {
                        captureError = PreferencesText.SHORTCUT_UNSUPPORTED_KEY
                    } else {
                        val modifiers = buildSet {
                            if (event.isCtrlPressed) add(ShortcutModifier.CTRL)
                            if (event.isAltPressed) add(ShortcutModifier.ALT)
                            if (event.isShiftPressed) add(ShortcutModifier.SHIFT)
                            if (event.isMetaPressed) add(ShortcutModifier.META)
                        }
                        val value = if (
                            modifiers.isEmpty() &&
                            mapped in setOf(ShortcutKey.BACKSPACE, ShortcutKey.DELETE)
                        ) null else KeyboardShortcut(mapped, modifiers)
                        saveCaptured(value)
                    }
                    true
                }
            }
            KeyEventType.KeyUp -> {
                val releasedModifier = event.key.isShortcutModifier()
                keysDown = keysDown - event.key
                if (!awaitingRelease && releasedModifier && keysDown.isEmpty()) {
                    captureError = PreferencesText.SHORTCUT_MODIFIER_ONLY_INVALID
                }
                finishWhenReady()
                true
            }
            else -> true
        }
    }

    LaunchedEffect(captureActive) {
        if (captureActive) focusRequester.requestFocus()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (captureActiveAtDispose) onCaptureStateChanged(false)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent(::handleCaptureEvent)
            .focusable(captureActive)
            .testTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG),
    ) {
        Text(strings.text(PreferencesText.PUSH_TO_TALK_SHORTCUT), style = MaterialTheme.typography.titleSmall)
        Text(
            if (captureActive) strings.text(PreferencesText.PRESS_SHORTCUT_NOW)
            else shortcut?.displayLabel() ?: strings.text(PreferencesText.SHORTCUT_NOT_SET),
            modifier = Modifier.testTag(PUSH_TO_TALK_SHORTCUT_VALUE_TEST_TAG),
        )
        if (captureActive) {
            Text(strings.text(PreferencesText.SHORTCUT_CAPTURE_HELP), color = EveColors.SecondaryText)
        } else {
            val statusText = when {
                !platformSupported || globalState.status == GlobalPushToTalkStatus.UNSUPPORTED ->
                    PreferencesText.SHORTCUT_UNAVAILABLE
                globalState.status == GlobalPushToTalkStatus.FAILED -> PreferencesText.SHORTCUT_UNAVAILABLE
                shortcut != null && assistantOpen -> PreferencesText.SHORTCUT_LISTENING_WHILE_ASSISTANT_OPEN
                else -> PreferencesText.SHORTCUT_INACTIVE_ASSISTANT_CLOSED
            }
            Text(strings.text(statusText), color = EveColors.SecondaryText)
        }
        captureError?.let { Text(strings.text(it), color = MaterialTheme.colorScheme.error) }
        inlineMessage?.let { Text(it.resolve(appStrings), color = EveColors.SecondaryText) }
        if (globalState.status == GlobalPushToTalkStatus.FAILED) {
            Text(
                PreferencesUiMessage(
                    PreferencesMessage.PUSH_TO_TALK_LISTENER_UNAVAILABLE,
                    technicalDetail = globalState.diagnostic,
                ).resolve(appStrings),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    captureError = null
                    inlineMessage = null
                    keysDown = emptySet()
                    awaitingRelease = false
                    saveComplete = false
                    captureActive = true
                    onCaptureStateChanged(true)
                },
                enabled = platformSupported && !captureActive,
                modifier = Modifier.testTag(PUSH_TO_TALK_SET_SHORTCUT_TEST_TAG),
            ) { Text(strings.text(PreferencesText.SET_SHORTCUT)) }
            TextButton(
                onClick = {
                    onCaptureStateChanged(true)
                    scope.launch {
                        try {
                            val result = onShortcutChange(null)
                            inlineMessage = result.fold(
                                onSuccess = {
                                    PreferencesUiMessage(PreferencesMessage.PUSH_TO_TALK_SHORTCUT_CLEARED)
                                },
                                onFailure = { failure ->
                                    PreferencesUiMessage(
                                        PreferencesMessage.PUSH_TO_TALK_SHORTCUT_SAVE_FAILED,
                                        technicalDetail = failure.message,
                                    )
                                },
                            )
                        } finally {
                            onCaptureStateChanged(false)
                        }
                    }
                },
                enabled = shortcut != null && !captureActive,
                modifier = Modifier.testTag(PUSH_TO_TALK_CLEAR_SHORTCUT_TEST_TAG),
            ) { Text(strings.text(PreferencesText.CLEAR_SHORTCUT)) }
        }
    }
}

private fun Key.isShortcutModifier(): Boolean = this in setOf(
    Key.CtrlLeft,
    Key.CtrlRight,
    Key.AltLeft,
    Key.AltRight,
    Key.ShiftLeft,
    Key.ShiftRight,
    Key.MetaLeft,
    Key.MetaRight,
)

private fun Key.toShortcutKey(): ShortcutKey? = when (this) {
    Key.A -> ShortcutKey.A
    Key.B -> ShortcutKey.B
    Key.C -> ShortcutKey.C
    Key.D -> ShortcutKey.D
    Key.E -> ShortcutKey.E
    Key.F -> ShortcutKey.F
    Key.G -> ShortcutKey.G
    Key.H -> ShortcutKey.H
    Key.I -> ShortcutKey.I
    Key.J -> ShortcutKey.J
    Key.K -> ShortcutKey.K
    Key.L -> ShortcutKey.L
    Key.M -> ShortcutKey.M
    Key.N -> ShortcutKey.N
    Key.O -> ShortcutKey.O
    Key.P -> ShortcutKey.P
    Key.Q -> ShortcutKey.Q
    Key.R -> ShortcutKey.R
    Key.S -> ShortcutKey.S
    Key.T -> ShortcutKey.T
    Key.U -> ShortcutKey.U
    Key.V -> ShortcutKey.V
    Key.W -> ShortcutKey.W
    Key.X -> ShortcutKey.X
    Key.Y -> ShortcutKey.Y
    Key.Z -> ShortcutKey.Z
    Key.Zero -> ShortcutKey.DIGIT_0
    Key.One -> ShortcutKey.DIGIT_1
    Key.Two -> ShortcutKey.DIGIT_2
    Key.Three -> ShortcutKey.DIGIT_3
    Key.Four -> ShortcutKey.DIGIT_4
    Key.Five -> ShortcutKey.DIGIT_5
    Key.Six -> ShortcutKey.DIGIT_6
    Key.Seven -> ShortcutKey.DIGIT_7
    Key.Eight -> ShortcutKey.DIGIT_8
    Key.Nine -> ShortcutKey.DIGIT_9
    Key.F1 -> ShortcutKey.F1
    Key.F2 -> ShortcutKey.F2
    Key.F3 -> ShortcutKey.F3
    Key.F4 -> ShortcutKey.F4
    Key.F5 -> ShortcutKey.F5
    Key.F6 -> ShortcutKey.F6
    Key.F7 -> ShortcutKey.F7
    Key.F8 -> ShortcutKey.F8
    Key.F9 -> ShortcutKey.F9
    Key.F10 -> ShortcutKey.F10
    Key.F11 -> ShortcutKey.F11
    Key.F12 -> ShortcutKey.F12
    Key(AwtKeyEvent.VK_F13) -> ShortcutKey.F13
    Key(AwtKeyEvent.VK_F14) -> ShortcutKey.F14
    Key(AwtKeyEvent.VK_F15) -> ShortcutKey.F15
    Key(AwtKeyEvent.VK_F16) -> ShortcutKey.F16
    Key(AwtKeyEvent.VK_F17) -> ShortcutKey.F17
    Key(AwtKeyEvent.VK_F18) -> ShortcutKey.F18
    Key(AwtKeyEvent.VK_F19) -> ShortcutKey.F19
    Key(AwtKeyEvent.VK_F20) -> ShortcutKey.F20
    Key(AwtKeyEvent.VK_F21) -> ShortcutKey.F21
    Key(AwtKeyEvent.VK_F22) -> ShortcutKey.F22
    Key(AwtKeyEvent.VK_F23) -> ShortcutKey.F23
    Key(AwtKeyEvent.VK_F24) -> ShortcutKey.F24
    Key.Spacebar -> ShortcutKey.SPACE
    Key.Tab -> ShortcutKey.TAB
    Key.Enter -> ShortcutKey.ENTER
    Key.Backspace -> ShortcutKey.BACKSPACE
    Key.Delete -> ShortcutKey.DELETE
    Key.Insert -> ShortcutKey.INSERT
    Key.DirectionUp -> ShortcutKey.ARROW_UP
    Key.DirectionDown -> ShortcutKey.ARROW_DOWN
    Key.DirectionLeft -> ShortcutKey.ARROW_LEFT
    Key.DirectionRight -> ShortcutKey.ARROW_RIGHT
    Key.MoveHome -> ShortcutKey.HOME
    Key.MoveEnd -> ShortcutKey.END
    Key.PageUp -> ShortcutKey.PAGE_UP
    Key.PageDown -> ShortcutKey.PAGE_DOWN
    else -> null
}

internal const val PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG = "push-to-talk-shortcut-section"
internal const val PUSH_TO_TALK_SHORTCUT_VALUE_TEST_TAG = "push-to-talk-shortcut-value"
internal const val PUSH_TO_TALK_SET_SHORTCUT_TEST_TAG = "push-to-talk-set-shortcut"
internal const val PUSH_TO_TALK_CLEAR_SHORTCUT_TEST_TAG = "push-to-talk-clear-shortcut"
