package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.ui.EveTheme
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

        onNodeWithText("Mini-map Preferences").assertIsDisplayed()
        onNodeWithText("Visible range").assertIsDisplayed()
        (1..5).forEach { onNodeWithText(it.toString()).assertIsDisplayed() }
        onNodeWithText("Visual only.", substring = true).assertIsDisplayed()

        onNodeWithText("5").performClick()
        assertEquals(5, changes.last().stargateHops)

        onNode(isToggleable()).performClick()
        assertTrue(changes.last().includeAnsiblexEdges)
    }
}
