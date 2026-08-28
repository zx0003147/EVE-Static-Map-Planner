package dev.evestaticmapplanner.ansiblex

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AnsiblexManagerDialogTest {
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
