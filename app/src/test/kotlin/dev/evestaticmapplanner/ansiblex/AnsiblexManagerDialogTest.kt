package dev.evestaticmapplanner.ansiblex

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AnsiblexManagerDialogTest {
    @Test
    fun `default window size meets responsive minimum and content width`() {
        assertTrue(ANSIBLEX_MANAGER_DEFAULT_SIZE.width >= ANSIBLEX_MANAGER_MINIMUM_SIZE.width)
        assertTrue(ANSIBLEX_MANAGER_DEFAULT_SIZE.height >= ANSIBLEX_MANAGER_MINIMUM_SIZE.height)
        assertTrue(ANSIBLEX_MANAGER_MINIMUM_SIZE.width >= ANSIBLEX_MANAGER_FORM_WIDTH + 450.dp)
        assertEquals(960.dp, ANSIBLEX_MANAGER_DEFAULT_SIZE.width)
        assertEquals(760.dp, ANSIBLEX_MANAGER_DEFAULT_SIZE.height)
    }

    @Test
    fun `root surface fills initial and resized client area`() = runComposeUiTest {
        var width by mutableStateOf(960.dp)
        var height by mutableStateOf(760.dp)
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width, height)) {
                    AnsiblexManagerRoot {}
                }
            }
        }

        onNodeWithTag(ANSIBLEX_MANAGER_ROOT_TEST_TAG)
            .assertWidthIsEqualTo(960.dp)
            .assertHeightIsEqualTo(760.dp)

        width = 1_280.dp
        height = 900.dp
        waitForIdle()

        onNodeWithTag(ANSIBLEX_MANAGER_ROOT_TEST_TAG)
            .assertWidthIsEqualTo(1_280.dp)
            .assertHeightIsEqualTo(900.dp)
    }

    @Test
    fun `clear imported confirmation can cancel`() = runComposeUiTest {
        var dismissCount = 0
        setContent {
            MaterialTheme {
                AnsiblexClearConfirmationDialog(
                    kind = ClearConfirmation.IMPORTED,
                    clearAllPhrase = "",
                    onClearAllPhraseChange = {},
                    onConfirm = {},
                    onDismiss = { dismissCount++ },
                )
            }
        }

        onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `clear imported confirmation can delete`() = runComposeUiTest {
        var confirmCount = 0
        setContent {
            MaterialTheme {
                AnsiblexClearConfirmationDialog(
                    kind = ClearConfirmation.IMPORTED,
                    clearAllPhrase = "",
                    onClearAllPhraseChange = {},
                    onConfirm = { confirmCount++ },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Clear Imported").assertIsEnabled().performClick()
        assertEquals(1, confirmCount)
    }

    @Test
    fun `clear all phrase receives focus and enables delete`() = runComposeUiTest {
        var phrase by mutableStateOf("")
        var confirmCount = 0
        setContent {
            MaterialTheme {
                AnsiblexClearConfirmationDialog(
                    kind = ClearConfirmation.ALL,
                    clearAllPhrase = phrase,
                    onClearAllPhraseChange = { phrase = it },
                    onConfirm = { confirmCount++ },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Delete Everything").assertIsNotEnabled()
        onNode(hasSetTextAction()).performClick().assertIsFocused().performTextInput("DELETE MANUAL")
        waitForIdle()
        onNodeWithText("Delete Everything").assertIsEnabled().performClick()
        assertEquals(1, confirmCount)
    }
}
