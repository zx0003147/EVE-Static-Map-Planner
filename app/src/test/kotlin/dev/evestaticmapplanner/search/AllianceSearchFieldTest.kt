package dev.evestaticmapplanner.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.core.alliance.AllianceDirectoryMerger
import dev.evestaticmapplanner.core.alliance.AllianceDirectorySourceSnapshot
import dev.evestaticmapplanner.core.alliance.AllianceReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AllianceSearchFieldTest {
    @Test
    fun `search selection returns stable AllianceReference`() = runComposeUiTest {
        var selected: AllianceReference? = null
        val snapshot = AllianceDirectoryMerger.merge(
            listOf(
                AllianceDirectorySourceSnapshot(
                    "test",
                    listOf(AllianceReference(99, "Goonswarm Federation", "CONDI")),
                ),
            ),
        )
        setContent {
            MaterialTheme {
                AllianceSearchField(snapshot, "Search", { selected = it })
            }
        }

        onNodeWithTag(ALLIANCE_SEARCH_FIELD_TAG).performTextInput("CON")
        onNodeWithTag("$ALLIANCE_SEARCH_RESULT_TAG_PREFIX-99").performClick()
        assertEquals(99L, selected?.allianceId)
    }
}
