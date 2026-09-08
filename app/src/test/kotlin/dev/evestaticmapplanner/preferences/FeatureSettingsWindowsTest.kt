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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeatureSettingsWindowsTest {
    @Test
    fun `each feature settings window has one open instance and repeat show requests focus`() {
        val initial = FeatureSettingsWindowState()
        val opened = initial.show()
        val shownAgain = opened.show()

        assertFalse(initial.isOpen)
        assertTrue(opened.isOpen)
        assertTrue(shownAgain.isOpen)
        assertEquals(opened.focusRequest + 1, shownAgain.focusRequest)
        val closed = shownAgain.close()
        val reopened = closed.show()
        assertFalse(closed.isOpen)
        assertTrue(reopened.isOpen)
        assertEquals(shownAgain.focusRequest + 1, reopened.focusRequest)
    }

    @Test
    fun `general Preferences contains only global categories`() {
        assertEquals(
            listOf("Map Display", "AI Control", "Feature Packs", "Overlays", "Web Pack", "Shared Map"),
            PreferencesCategory.entries.map { it.label },
        )
    }

    @Test
    fun `Marker settings content is feature-only and applies changes immediately`() = runComposeUiTest {
        val changes = mutableListOf<MarkerPreferences>()
        setContent {
            EveTheme {
                Column {
                    MarkerPreferencesContent(
                        preferences = MarkerPreferences.Defaults,
                        onChange = changes::add,
                        onReset = {},
                    )
                }
            }
        }

        onNodeWithText("Marker Settings").assertIsDisplayed()
        onNodeWithText("Saved Marker Appearance").assertIsDisplayed()
        onNodeWithText("Map Display").assertDoesNotExist()
        onNodeWithText("Mini-map Settings").assertDoesNotExist()
        onNodeWithText("AI Control").assertDoesNotExist()

        onAllNodes(isToggleable())[0].performClick()
        assertFalse(changes.last().showMarkers)
    }
}
