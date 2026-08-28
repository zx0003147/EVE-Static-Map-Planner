package dev.evestaticmapplanner.marker

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkerManagerWindowTest {
    @Test
    fun `delete confirmation supports remove and cancel actions`() = runComposeUiTest {
        var removeCount = 0
        var cancelCount = 0
        setContent {
            MaterialTheme {
                SavedMarkerDeleteConfirmationDialog(
                    row = row(),
                    operationError = null,
                    isBusy = false,
                    onRemove = { removeCount++ },
                    onCancel = { cancelCount++ },
                    onDismissRequest = {},
                )
            }
        }

        onNodeWithText("Remove").assertIsEnabled().performClick()
        onNodeWithText("Cancel").assertIsEnabled().performClick()

        assertEquals(1, removeCount)
        assertEquals(1, cancelCount)
    }

    @Test
    fun `busy delete confirmation blocks duplicate actions without changing its design`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SavedMarkerDeleteConfirmationDialog(
                    row = row(),
                    operationError = null,
                    isBusy = true,
                    onRemove = {},
                    onCancel = {},
                    onDismissRequest = {},
                )
            }
        }

        onNodeWithText("Removing…").assertIsNotEnabled()
        onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun `manager close is blocked while either owned dialog is open`() {
        assertTrue(markerManagerCanClose(editorOpen = false, deleteConfirmationOpen = false))
        assertFalse(markerManagerCanClose(editorOpen = true, deleteConfirmationOpen = false))
        assertFalse(markerManagerCanClose(editorOpen = false, deleteConfirmationOpen = true))
        assertFalse(markerManagerCanClose(editorOpen = true, deleteConfirmationOpen = true))
    }

    @Test
    fun `AI provenance badge is visible while USER provenance stays quiet`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    SavedMarkerProvenanceBadge(SavedMarkerCreatedBy.USER)
                    SavedMarkerProvenanceBadge(SavedMarkerCreatedBy.AI)
                }
            }
        }

        onNodeWithText("Created by AI").assertIsDisplayed()
    }

    private fun row() = SavedMarkerRowPresentation(
        systemId = 30_000_142,
        systemName = "Jita",
        markerName = "Market",
        color = MarkerColor.BLUE,
        notes = null,
        createdBy = SavedMarkerCreatedBy.USER,
    )
}
