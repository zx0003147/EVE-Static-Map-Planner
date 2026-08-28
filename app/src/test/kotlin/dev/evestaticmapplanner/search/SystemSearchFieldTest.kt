package dev.evestaticmapplanner.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SystemSearchFieldTest {
    @Test
    fun `dropdown stays focused while query updates and suggestions filter`() = runComposeUiTest {
        val c0nd2 = system(30_000_001, "C-0ND2")
        val cJ6mt = system(30_000_002, "C-J6MT")
        val systems = listOf(c0nd2, cJ6mt)
        val queryUpdates = mutableListOf<String>()
        var query by mutableStateOf("")

        setContent {
            MaterialTheme {
                SystemSearchField(
                    value = query,
                    label = "Search system",
                    results = if (query.isBlank()) emptyList() else systems.filter {
                        it.name.contains(query, ignoreCase = true)
                    },
                    onValueChange = {
                        query = it
                        queryUpdates += it
                    },
                    onSelect = {},
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
            }
        }

        val textField = onNode(hasSetTextAction())
        textField.performClick()

        listOf("C", "-", "0", "N", "D", "2").forEach { character ->
            textField.performTextInput(character)
            waitForIdle()
            textField.assertIsFocused()
        }

        textField.assertTextEquals("Search system", "C-0ND2")
        assertEquals(listOf("C", "C-", "C-0", "C-0N", "C-0ND", "C-0ND2"), queryUpdates)
        onNodeWithText("C-0ND2  ·  30000001").assertExists()
        onNodeWithText("C-J6MT  ·  30000002").assertDoesNotExist()
    }

    @Test
    fun `non focusable dropdown still confirms a mouse selected suggestion`() = runComposeUiTest {
        val jita = system(30_000_142, "Jita")
        var query by mutableStateOf("")
        var selected: SolarSystem? = null

        setContent {
            MaterialTheme {
                SystemSearchField(
                    value = query,
                    label = "Search system",
                    results = if (query.isBlank()) emptyList() else listOf(jita),
                    onValueChange = { query = it },
                    onSelect = { selected = it },
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
            }
        }

        val textField = onNode(hasSetTextAction())
        textField.performClick()
        textField.performTextInput("J")
        waitForIdle()
        textField.assertIsFocused()

        onNodeWithText("Jita  ·  30000142").performClick()

        assertSame(jita, selected)
    }

    @Test
    fun `dropdown popup is non focusable while retaining dismissal properties`() {
        assertFalse(SYSTEM_SEARCH_POPUP_PROPERTIES.focusable)
        assertTrue(SYSTEM_SEARCH_POPUP_PROPERTIES.dismissOnClickOutside)
        assertTrue(SYSTEM_SEARCH_POPUP_PROPERTIES.dismissOnBackPress)
    }

    @Test
    fun `compact mode changes only the requested search field height`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SystemSearchField(
                    value = "",
                    label = "Search system",
                    results = emptyList(),
                    onValueChange = {},
                    onSelect = {},
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                    compact = true,
                )
            }
        }

        onNode(hasSetTextAction()).assertHeightIsEqualTo(COMPACT_SEARCH_FIELD_HEIGHT)
        assertEquals(48.dp, COMPACT_SEARCH_FIELD_HEIGHT)
    }

    private fun system(id: Int, name: String) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 1,
        name = name,
        securityStatus = 0.5,
        securityClass = null,
        position = UniversePosition(1.0, 2.0, 3.0),
        schematicPosition = SchematicPosition(10.0, 20.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )
}
