package dev.evestaticmapplanner.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EveMenuBarTest {
    @Test
    fun `top menu keeps its action and dismisses after selection`() = runComposeUiTest {
        val actions = mutableListOf<String>()
        setContent {
            EveTheme {
                EveTopMenuBar(
                    listOf(
                        EveMenuSpec(
                            "Marker",
                            listOf(EveMenuItemSpec("Marker Manager…") { actions += "marker" }),
                        ),
                        EveMenuSpec("Preferences", emptyList()),
                        EveMenuSpec("Static Data", emptyList()),
                    ),
                )
            }
        }

        listOf("Marker", "Preferences", "Static Data").forEach {
            onNodeWithText(it).assertIsDisplayed()
        }
        onNodeWithText("Marker").performClick()
        onNodeWithText("Marker Manager…").assertIsDisplayed().performClick()

        assertEquals(listOf("marker"), actions)
        onNodeWithText("Marker Manager…").assertDoesNotExist()
    }
}
