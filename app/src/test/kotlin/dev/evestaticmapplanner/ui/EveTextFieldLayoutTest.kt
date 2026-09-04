package dev.evestaticmapplanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EveTextFieldLayoutTest {
    @Test
    fun `outlined fields keep labels values placeholders and multiline text inside at common Windows scales`() =
        runComposeUiTest {
            var densityScale by mutableStateOf(1f)
            var selectedValue by mutableStateOf("123.45")
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(densityScale, fontScale = 1f)) {
                    EveTheme {
                        Column(Modifier.width(320.dp)) {
                            EveOutlinedTextField(
                                value = selectedValue,
                                onValueChange = { selectedValue = it },
                                label = { Text("Effective maximum LY", Modifier.testTag(SINGLE_LABEL_TAG)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag(SINGLE_FIELD_TAG),
                            )
                            EveOutlinedTextField(
                                value = "",
                                onValueChange = {},
                                label = { Text("Search system", Modifier.testTag(PLACEHOLDER_LABEL_TAG)) },
                                placeholder = { Text("Jita", Modifier.testTag(PLACEHOLDER_TAG)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag(PLACEHOLDER_FIELD_TAG),
                            )
                            EveOutlinedTextField(
                                value = "First line\nSecond line",
                                onValueChange = {},
                                label = { Text("Notes", Modifier.testTag(MULTILINE_LABEL_TAG)) },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth().testTag(MULTILINE_FIELD_TAG),
                            )
                            EveButton(
                                onClick = {},
                                modifier = Modifier.testTag(BUTTON_TAG),
                            ) { Text("Save", Modifier.testTag(BUTTON_TEXT_TAG)) }
                        }
                    }
                }
            }

            for (scale in listOf(1f, 1.25f, 1.5f)) {
                runOnIdle {
                    densityScale = scale
                    selectedValue = "123.45"
                }
                waitForIdle()

                val singleField = onNodeWithTag(SINGLE_FIELD_TAG)
                singleField.assertTextEquals("Effective maximum LY", "123.45")
                assertContained(SINGLE_FIELD_TAG, SINGLE_LABEL_TAG)
                assertMinimumHeight(SINGLE_FIELD_TAG, scale)

                val placeholderField = onNodeWithTag(PLACEHOLDER_FIELD_TAG)
                placeholderField.performClick().assertIsFocused()
                onNodeWithTag(PLACEHOLDER_TAG, useUnmergedTree = true).assertIsDisplayed()
                assertContained(PLACEHOLDER_FIELD_TAG, PLACEHOLDER_LABEL_TAG)
                assertContained(PLACEHOLDER_FIELD_TAG, PLACEHOLDER_TAG)
                assertMinimumHeight(PLACEHOLDER_FIELD_TAG, scale)

                singleField.performClick().performTextInputSelection(TextRange(3))
                val selection = singleField.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
                assertEquals(TextRange(3), selection)

                onNodeWithTag(MULTILINE_FIELD_TAG)
                    .assertTextEquals("Notes", "First line\nSecond line")
                assertContained(MULTILINE_FIELD_TAG, MULTILINE_LABEL_TAG)

                assertContained(BUTTON_TAG, BUTTON_TEXT_TAG)
            }
        }

    private fun androidx.compose.ui.test.ComposeUiTest.assertContained(parentTag: String, childTag: String) {
        val parent = onNodeWithTag(parentTag).fetchSemanticsNode().boundsInRoot
        val child = onNodeWithTag(childTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(child.top >= parent.top, "$childTag top must stay inside $parentTag")
        assertTrue(child.bottom <= parent.bottom, "$childTag bottom must stay inside $parentTag")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertMinimumHeight(fieldTag: String, scale: Float) {
        val actualHeightPx = onNodeWithTag(fieldTag).fetchSemanticsNode().boundsInRoot.height
        val expectedMinimumPx = EveDimensions.InputMinimumHeight.value * scale
        assertTrue(actualHeightPx >= expectedMinimumPx, "$fieldTag must be at least 44dp at ${scale}x density")
    }

    private companion object {
        const val SINGLE_FIELD_TAG = "single-field"
        const val SINGLE_LABEL_TAG = "single-label"
        const val PLACEHOLDER_FIELD_TAG = "placeholder-field"
        const val PLACEHOLDER_LABEL_TAG = "placeholder-label"
        const val PLACEHOLDER_TAG = "placeholder"
        const val MULTILINE_FIELD_TAG = "multiline-field"
        const val MULTILINE_LABEL_TAG = "multiline-label"
        const val BUTTON_TAG = "button"
        const val BUTTON_TEXT_TAG = "button-text"
    }
}
