package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.ui.EveTheme
import dev.evestaticmapplanner.minimap.MiniMapHudRuntimeState
import dev.evestaticmapplanner.minimap.MiniMapRecoveryHotkeyStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MiniMapPreferencesContentTest {
    @Test
    fun `Mini-map preferences expose one through five Stargate hops and visual-only Ansiblex`() = runComposeUiTest {
        val changes = mutableListOf<MiniMapPreferences>()
        val initial = MiniMapPreferences.Defaults
        setContent {
            EveTheme {
                Column {
                    MiniMapPreferencesContent(initial, changes::add) {}
                }
            }
        }

        onNodeWithText("Mini-map Settings").assertIsDisplayed()
        onNodeWithText("Visible range").assertIsDisplayed()
        (1..5).forEach { onNodeWithText(it.toString()).assertIsDisplayed() }
        onNodeWithText("Visual only.", substring = true).assertIsDisplayed()
        onNodeWithText("Window style").assertIsDisplayed()
        onNodeWithText("Standard").assertIsDisplayed()
        onNodeWithText("HUD").assertIsDisplayed()
        onNodeWithText("Interactive").assertIsDisplayed()
        onNodeWithText("HUD Locked").assertIsDisplayed()
        onNodeWithText("Recovery hotkey: Ctrl + Shift + M", substring = true).assertIsDisplayed()
        onNodeWithText("HUD opacity: 88%").assertIsDisplayed()
        onNodeWithText("Snap to screen edges").assertIsDisplayed()

        onNodeWithText("5").performClick()
        assertEquals(5, changes.last().stargateHops)

        onAllNodes(isToggleable())[0].performClick()
        assertTrue(changes.last().includeAnsiblexEdges)

        onNodeWithText("HUD").performClick()
        assertEquals(MiniMapWindowStyle.HUD, changes.last().windowStyle)
        onNodeWithText("HUD Locked").performClick()
        assertEquals(MiniMapInteractionMode.HUD_LOCKED, changes.last().interactionMode)
        onAllNodes(isToggleable())[1].performClick()
        assertEquals(false, changes.last().snapToScreenEdges)
    }

    @Test
    fun `HUD Locked is disabled when the recovery hotkey is unavailable`() = runComposeUiTest {
        setContent {
            EveTheme {
                Column {
                    MiniMapPreferencesContent(
                        preferences = MiniMapPreferences.Defaults,
                        onChange = {},
                        hudRuntimeState = MiniMapHudRuntimeState(
                            MiniMapRecoveryHotkeyStatus.FAILED,
                            "Registration failed",
                        ),
                        onReset = {},
                    )
                }
            }
        }

        onNodeWithText("HUD Locked").assertIsNotEnabled()
        onNodeWithText("Registration failed").assertIsDisplayed()
    }
}
