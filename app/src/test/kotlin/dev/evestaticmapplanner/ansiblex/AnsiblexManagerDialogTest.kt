package dev.evestaticmapplanner.ansiblex

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
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

    @Test
    fun `manager exposes file and paste imports as separate entry points`() = runComposeUiTest {
        var fileClicks = 0
        var pasteClicks = 0
        setContent {
            MaterialTheme {
                AnsiblexImportEntryButtons(
                    busy = false,
                    onFileImport = { fileClicks++ },
                    onPasteImport = { pasteClicks++ },
                )
            }
        }

        onNodeWithText("File Import").performClick()
        onNodeWithText("Paste Text Import").performClick()
        assertEquals(1, fileClicks)
        assertEquals(1, pasteClicks)
    }

    @Test
    fun `paste dialog accepts text and forwards it to preview parsing`() = runComposeUiTest {
        var pastedText by mutableStateOf("")
        var parsedText: String? = null
        setContent {
            MaterialTheme {
                AnsiblexPasteImportDialog(
                    pastedText = pastedText,
                    onPastedTextChange = { pastedText = it },
                    onParse = { parsedText = it },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Parse").assertIsNotEnabled()
        onNode(hasSetTextAction()).performClick().performTextInput(PASTED_IMPORT_FIXTURE)
        waitForIdle()
        onNodeWithText("Parse").assertIsEnabled().performClick()

        assertEquals(PASTED_IMPORT_FIXTURE, parsedText)
    }

    @Test
    fun `cancelling paste dialog closes only paste state`() = runComposeUiTest {
        var managerVisible by mutableStateOf(true)
        var pasteVisible by mutableStateOf(true)
        var managerClicks = 0
        setContent {
            MaterialTheme {
                if (managerVisible) {
                    Box {
                        Button(onClick = { managerClicks++ }) { Text("Manager action") }
                        if (pasteVisible) {
                            AnsiblexPasteImportDialog(
                                pastedText = PASTED_IMPORT_FIXTURE,
                                onPastedTextChange = {},
                                onParse = {},
                                onDismiss = { pasteVisible = false },
                            )
                        }
                    }
                }
            }
        }

        onNodeWithText("Cancel").performClick()
        waitForIdle()

        assertTrue(!pasteVisible)
        assertTrue(managerVisible)
        onNodeWithText("Manager action").assertIsDisplayed().performClick()
        assertEquals(1, managerClicks)
    }

    @Test
    fun `paste dialog blocks pointer interaction with manager content`() = runComposeUiTest {
        var managerVisible by mutableStateOf(true)
        var pasteVisible by mutableStateOf(true)
        var managerClicks = 0
        setContent {
            MaterialTheme {
                if (managerVisible) {
                    Box {
                        Button(onClick = { managerClicks++ }) { Text("Manager action") }
                        if (pasteVisible) {
                            AnsiblexPasteImportDialog(
                                pastedText = PASTED_IMPORT_FIXTURE,
                                onPastedTextChange = {},
                                onParse = {},
                                onDismiss = { pasteVisible = false },
                            )
                        }
                    }
                }
            }
        }

        onNodeWithText("Manager action").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        waitForIdle()

        assertEquals(0, managerClicks)
        assertTrue(managerVisible)
    }

    private companion object {
        const val PASTED_IMPORT_FIXTURE = "from,to\n1DQ1-A,T5ZI-S"
    }
}
