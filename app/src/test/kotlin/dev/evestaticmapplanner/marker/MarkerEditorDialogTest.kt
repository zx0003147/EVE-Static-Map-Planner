package dev.evestaticmapplanner.marker

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import java.time.Instant
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MarkerEditorDialogTest {
    @Test
    fun `many tags remain reachable while save and cancel stay in the fixed dialog footer`() = runComposeUiTest {
        val marker = Marker.saved(1, MarkerDraft.create(name = "Home staging"), Instant.EPOCH, Instant.EPOCH)
        val children = SavedMarkerChildVisuals.known.dropLast(1).mapIndexed { index, visual ->
            SavedMarkerChild.create(
                id = "child-$index",
                parentSystemId = 1,
                type = checkNotNull(visual.type),
                orderIndex = index,
            )
        }

        setContent {
            MaterialTheme {
                MarkerEditorDialog(
                    request = MarkerEditorRequest(
                        mode = MarkerEditorMode.EDIT_SAVED,
                        systemId = 1,
                        systemName = "Jita",
                        marker = marker,
                    ),
                    isBusy = false,
                    error = null,
                    children = children,
                    onSave = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Save").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
        onNodeWithText("+ Add Tag").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithText("Keepstar").assertIsDisplayed()
        onNodeWithText("Save").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
    }
}
