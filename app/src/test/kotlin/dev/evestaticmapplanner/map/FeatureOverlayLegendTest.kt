package dev.evestaticmapplanner.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FeatureOverlayLegendTest {
    @Test
    fun `sovereignty shortcut is hidden while other legend sections remain available`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FeatureOverlayLegend(
                    sections = listOf(
                        FeatureOverlayLegendSection(
                            title = "Sovereignty",
                            entries = listOf(
                                FeatureOverlayLegendEntry("Alliance A", Color.Red),
                                FeatureOverlayLegendEntry("Alliance B", Color.Blue),
                            ),
                        ),
                        FeatureOverlayLegendSection(
                            title = "Characters",
                            entries = listOf(FeatureOverlayLegendEntry("Pilot A", Color.Green)),
                        ),
                    ),
                )
            }
        }

        onNodeWithText("Sovereignty ▸").assertDoesNotExist()
        onNodeWithText("Alliance A").assertDoesNotExist()
        onNodeWithText("Alliance B").assertDoesNotExist()
        onNodeWithText("Characters ▸").assertIsDisplayed()
        onNodeWithText("Pilot A").assertDoesNotExist()

        onNodeWithText("Characters ▸").performClick()

        onNodeWithText("Characters ▾").assertIsDisplayed()
        onNodeWithText("Pilot A").assertIsDisplayed()
        onNodeWithText("Alliance A").assertDoesNotExist()
        onNodeWithText("Alliance B").assertDoesNotExist()
    }
}
