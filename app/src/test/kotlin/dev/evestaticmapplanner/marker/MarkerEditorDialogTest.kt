package dev.evestaticmapplanner.marker

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MarkerEditorDialogTest {
    @Test
    fun `AI-created saved marker editor shows immutable provenance metadata`() = runComposeUiTest {
        val marker = Marker.saved(
            1,
            MarkerDraft.create(name = "AI staging"),
            Instant.EPOCH,
            Instant.EPOCH,
            SavedMarkerCreatedBy.AI,
        )
        setContent {
            MaterialTheme {
                MarkerEditorDialog(
                    request = MarkerEditorRequest(MarkerEditorMode.EDIT_SAVED, 1, "Jita", marker),
                    isBusy = false,
                    error = null,
                    onSave = { _, _, _ -> },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Created by AI").assertIsDisplayed()
    }

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
                    onSave = { _, _, _ -> },
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

    @Test
    fun `manager add editor accepts focus typing and cancel`() = runComposeUiTest {
        var systemQuery by mutableStateOf("")
        var dismissCount = 0
        setContent {
            MaterialTheme {
                MarkerEditorDialog(
                    request = MarkerEditorRequest(MarkerEditorMode.CREATE_SAVED, null, null),
                    isBusy = false,
                    error = null,
                    systemSearch = MarkerEditorSystemSearch(systemQuery, emptyList(), null),
                    onSystemQueryChange = { systemQuery = it },
                    onSave = { _, _, _ -> },
                    onDismiss = { dismissCount++ },
                )
            }
        }

        onAllNodes(hasSetTextAction())[0].performClick()
        onAllNodes(hasSetTextAction())[0].assertIsFocused()
        onAllNodes(hasSetTextAction())[0].performTextInput("Jita")
        onAllNodes(hasSetTextAction())[0].assertTextEquals("Search system", "Jita")
        onAllNodes(hasSetTextAction())[1].performClick()
        onAllNodes(hasSetTextAction())[1].assertIsFocused()
        onAllNodes(hasSetTextAction())[1].performTextInput("Market staging")
        onAllNodes(hasSetTextAction())[1].assertTextEquals("Name", "Market staging")
        onAllNodes(hasSetTextAction())[2].performClick()
        onAllNodes(hasSetTextAction())[2].assertIsFocused()
        onAllNodes(hasSetTextAction())[2].performTextInput("Move supplies here")
        onAllNodes(hasSetTextAction())[2].assertTextEquals("Notes", "Move supplies here")
        onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissCount)
    }

    @Test
    fun `create saved marker reuses tag picker and submits initial tags with the draft`() = runComposeUiTest {
        var submittedTags = emptyList<SavedMarkerChildType>()
        setContent {
            MaterialTheme {
                MarkerEditorDialog(
                    request = MarkerEditorRequest(MarkerEditorMode.CREATE_SAVED, 1, "Jita"),
                    isBusy = false,
                    error = null,
                    onSave = { _, _, tags -> submittedTags = tags },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("+ Add Tag").performScrollTo().performClick()
        onNodeWithText("Staging").performClick()
        onNodeWithText("Staging").assertIsDisplayed()
        onNodeWithText("Save").performClick()

        assertEquals(listOf(SavedMarkerChildType.STAGING), submittedTags)
    }
}
