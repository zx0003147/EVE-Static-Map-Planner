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
    fun `legend is collapsed by default and expands without changing presentation data`() = runComposeUiTest {
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
                    ),
                )
            }
        }

        onNodeWithText("Sovereignty ▸").assertIsDisplayed()
        onNodeWithText("Alliance A").assertDoesNotExist()
        onNodeWithText("Alliance B").assertDoesNotExist()

        onNodeWithText("Sovereignty ▸").performClick()

        onNodeWithText("Sovereignty ▾").assertIsDisplayed()
        onNodeWithText("Alliance A").assertIsDisplayed()
        onNodeWithText("Alliance B").assertIsDisplayed()
    }
}
