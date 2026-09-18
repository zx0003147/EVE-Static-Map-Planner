package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.ai.GlobalPushToTalkState
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppLocalization
import dev.evestaticmapplanner.localization.ProvideAppLocalization
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.shortcut.ShortcutKey
import dev.evestaticmapplanner.shortcut.ShortcutModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PushToTalkShortcutPreferenceTest {
    @Test
    fun `captures a modifier chord and resumes only after release`() = runComposeUiTest {
        val saved = mutableListOf<KeyboardShortcut?>()
        val captureStates = mutableListOf<Boolean>()
        setContent {
            ProvideAppLocalization(AppLocalization(AppLocale.EN_US)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(600.dp, 300.dp)) {
                        PushToTalkShortcutPreference(
                            shortcut = null,
                            globalState = GlobalPushToTalkState(),
                            assistantOpen = true,
                            platformSupported = true,
                            onCaptureStateChanged = captureStates::add,
                            onShortcutChange = { saved += it; Result.success(Unit) },
                        )
                    }
                }
            }
        }

        onNodeWithTag(PUSH_TO_TALK_SET_SHORTCUT_TEST_TAG).performClick()
        onNodeWithText("Press the shortcut now…").assertIsDisplayed()
        onNodeWithTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG).performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Spacebar)
            keyUp(Key.CtrlLeft)
        }
        waitForIdle()

        assertEquals(
            listOf<KeyboardShortcut?>(KeyboardShortcut(ShortcutKey.SPACE, setOf(ShortcutModifier.CTRL))),
            saved,
        )
        assertEquals(listOf(true, false), captureStates)
    }

    @Test
    fun `Escape cancels and Chinese strings are localized`() = runComposeUiTest {
        val saved = mutableListOf<KeyboardShortcut?>()
        setContent {
            ProvideAppLocalization(AppLocalization(AppLocale.ZH_CN)) {
                MaterialTheme {
                    PushToTalkShortcutPreference(
                        shortcut = null,
                        globalState = GlobalPushToTalkState(),
                        assistantOpen = false,
                        platformSupported = true,
                        onCaptureStateChanged = {},
                        onShortcutChange = { saved += it; Result.success(Unit) },
                    )
                }
            }
        }

        onNodeWithText("按键说话快捷键").assertIsDisplayed()
        onNodeWithText("未设置").assertIsDisplayed()
        onNodeWithText("设置快捷键").performClick()
        onNodeWithText("请按下快捷键……").assertIsDisplayed()
        onNodeWithTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertTrue(saved.isEmpty())
    }

    @Test
    fun `single key identical shortcut avoids a settings write until release`() = runComposeUiTest {
        val shortcut = KeyboardShortcut(ShortcutKey.F9)
        val saved = mutableListOf<KeyboardShortcut?>()
        val captureStates = mutableListOf<Boolean>()
        setContent {
            ProvideAppLocalization(AppLocalization(AppLocale.EN_US)) {
                MaterialTheme {
                    PushToTalkShortcutPreference(
                        shortcut = shortcut,
                        globalState = GlobalPushToTalkState(),
                        assistantOpen = true,
                        platformSupported = true,
                        onCaptureStateChanged = captureStates::add,
                        onShortcutChange = { saved += it; Result.success(Unit) },
                    )
                }
            }
        }

        onNodeWithTag(PUSH_TO_TALK_SET_SHORTCUT_TEST_TAG).performClick()
        onNodeWithTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG).performKeyInput {
            keyDown(Key.F9)
        }
        waitForIdle()
        assertEquals(listOf(true), captureStates)
        onNodeWithTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG).performKeyInput { keyUp(Key.F9) }
        waitForIdle()

        assertTrue(saved.isEmpty())
        assertEquals(listOf(true, false), captureStates)
    }

    @Test
    fun `modifier only and unsupported keys show errors without saving`() = runComposeUiTest {
        val saved = mutableListOf<KeyboardShortcut?>()
        setContent {
            ProvideAppLocalization(AppLocalization(AppLocale.EN_US)) {
                MaterialTheme {
                    PushToTalkShortcutPreference(
                        shortcut = null,
                        globalState = GlobalPushToTalkState(),
                        assistantOpen = true,
                        platformSupported = true,
                        onCaptureStateChanged = {},
                        onShortcutChange = { saved += it; Result.success(Unit) },
                    )
                }
            }
        }

        onNodeWithTag(PUSH_TO_TALK_SET_SHORTCUT_TEST_TAG).performClick()
        onNodeWithTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG).performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG).performKeyInput { keyUp(Key.CtrlLeft) }
        waitForIdle()
        onNodeWithText("A modifier key alone cannot be used as a shortcut.").assertIsDisplayed()
        onNodeWithTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG).performKeyInput { pressKey(Key.Semicolon) }
        waitForIdle()
        onNodeWithText("That key is not supported for Push-to-Talk.").assertIsDisplayed()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `unmodified Delete clears and save failure preserves the displayed shortcut`() = runComposeUiTest {
        val shortcut = KeyboardShortcut(ShortcutKey.F9)
        val saved = mutableListOf<KeyboardShortcut?>()
        setContent {
            ProvideAppLocalization(AppLocalization(AppLocale.EN_US)) {
                MaterialTheme {
                    PushToTalkShortcutPreference(
                        shortcut = shortcut,
                        globalState = GlobalPushToTalkState(),
                        assistantOpen = true,
                        platformSupported = true,
                        onCaptureStateChanged = {},
                        onShortcutChange = {
                            saved += it
                            Result.failure(IllegalStateException("disk full"))
                        },
                    )
                }
            }
        }

        onNodeWithTag(PUSH_TO_TALK_SET_SHORTCUT_TEST_TAG).performClick()
        onNodeWithTag(PUSH_TO_TALK_SHORTCUT_SECTION_TEST_TAG).performKeyInput { pressKey(Key.Delete) }
        waitForIdle()

        assertEquals(listOf<KeyboardShortcut?>(null), saved)
        onNodeWithText("F9").assertIsDisplayed()
        onNodeWithText("The Push-to-Talk shortcut could not be saved.\ndisk full").assertIsDisplayed()
    }
}
