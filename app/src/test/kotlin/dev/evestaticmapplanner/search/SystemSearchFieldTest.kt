package dev.evestaticmapplanner.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
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
    fun `editing synchronization preserves user selection until external text really changes`() {
        val userEditingValue = TextFieldValue("1DQ1-A", selection = TextRange(3))

        assertSame(userEditingValue, synchronizeSearchFieldValue(userEditingValue, "1DQ1-A"))

        val programmaticSelection = synchronizeSearchFieldValue(userEditingValue, "4-HWWF")
        assertEquals("4-HWWF", programmaticSelection.text)
        assertEquals(TextRange(6), programmaticSelection.selection)
        assertEquals(null, programmaticSelection.composition)
    }

    @Test
    fun `non compact dropdown keeps selection when suggestions arrive`() = runComposeUiTest {
        val acceptanceNames = listOf("1DQ1-A", "4-HWWF", "ULX-3A", "DQA-11")
        var query by mutableStateOf("")
        var results by mutableStateOf(emptyList<SolarSystem>())

        setContent {
            MaterialTheme {
                SystemSearchField(
                    value = query,
                    label = "Search system",
                    results = results,
                    onValueChange = { query = it },
                    onSelect = {},
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
            }
        }

        val textField = onNode(hasSetTextAction())
        acceptanceNames.forEachIndexed { systemIndex, systemName ->
            runOnIdle {
                query = ""
                results = emptyList()
            }
            waitForIdle()
            textField.performClick()
            textField.assertSelection(0)

            systemName.forEachIndexed { characterIndex, character ->
                textField.performTextInput(character.toString())
                waitForIdle()
                textField.assertSelection(characterIndex + 1)

                if (characterIndex == 0) {
                    runOnIdle {
                        results = listOf(system(30_000_100 + systemIndex, systemName))
                    }
                    waitForIdle()
                    textField.assertIsFocused()
                    textField.assertSelection(1)
                }
            }

            textField.assertTextEquals("Search system", systemName)
            assertEquals(systemName, query)
        }
    }

    @Test
    fun `dropdown preserves a manually moved selection through results recomposition`() =
        verifyManualSelection(SearchSuggestionsPresentation.DROPDOWN)

    @Test
    fun `inline preserves a manually moved selection through results recomposition`() =
        verifyManualSelection(SearchSuggestionsPresentation.INLINE)

    @Test
    fun `escape dismisses dropdown until the user edits again`() = runComposeUiTest {
        val c0nd2 = system(30_000_001, "C-0ND2")
        var query by mutableStateOf("")
        setContent {
            MaterialTheme {
                SystemSearchField(
                    value = query,
                    label = "Search system",
                    results = if (query.isBlank()) emptyList() else listOf(c0nd2),
                    onValueChange = { query = it },
                    onSelect = {},
                    suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                )
            }
        }

        val textField = onNode(hasSetTextAction())
        textField.performClick().performTextInput("C")
        waitForIdle()
        onNodeWithText("C-0ND2  ·  30000001").assertExists()

        textField.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        textField.assertIsFocused().assertSelection(1)
        onNodeWithText("C-0ND2  ·  30000001").assertDoesNotExist()

        textField.performTextInput("-")
        waitForIdle()
        textField.assertTextEquals("Search system", "C-").assertSelection(2)
        onNodeWithText("C-0ND2  ·  30000001").assertExists()
    }

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
                    compact = true,
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
                    compact = true,
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
    fun `compact mode keeps its label inside a readable minimum height`() = runComposeUiTest {
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

        val field = onNodeWithTag(COMPACT_SEARCH_FIELD_TEST_TAG)
        val fieldBounds = field.fetchSemanticsNode().boundsInRoot
        val placeholderBounds = onNodeWithTag(
            COMPACT_SEARCH_PLACEHOLDER_TEST_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(placeholderBounds.top >= fieldBounds.top)
        assertTrue(placeholderBounds.bottom <= fieldBounds.bottom)
        assertEquals(fieldBounds.center.y, placeholderBounds.center.y, absoluteTolerance = 1f)
        assertTrue(fieldBounds.height >= with(density) { COMPACT_SEARCH_FIELD_MIN_HEIGHT.toPx() })
        assertEquals(44.dp, COMPACT_SEARCH_FIELD_MIN_HEIGHT)
    }

    @Test
    fun `compact numeric field keeps representative values readable at common Windows scales`() = runComposeUiTest {
        var densityScale by mutableStateOf(1f)
        var value by mutableStateOf("1")
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(densityScale, fontScale = 1f)) {
                MaterialTheme {
                    CompactOutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = "Effective maximum LY",
                        modifier = Modifier.fillMaxWidth().testTag(COMPACT_SEARCH_FIELD_TEST_TAG),
                    )
                }
            }
        }

        for (scale in listOf(1f, 1.25f, 1.5f)) {
            for (candidate in listOf("1", "12", "123.45")) {
                runOnIdle {
                    densityScale = scale
                    value = candidate
                }
                waitForIdle()

                val field = onNodeWithTag(COMPACT_SEARCH_FIELD_TEST_TAG)
                field.assertTextEquals("Effective maximum LY", candidate)
                val minimumHeightPx = COMPACT_SEARCH_FIELD_MIN_HEIGHT.value * scale
                assertTrue(field.fetchSemanticsNode().boundsInRoot.height >= minimumHeightPx)
            }
        }
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

    private fun SemanticsNodeInteraction.assertSelection(offset: Int) {
        val actual = fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(offset), actual)
    }

    private fun verifyManualSelection(presentation: SearchSuggestionsPresentation) = runComposeUiTest {
        val ulx3a = system(30_000_200, "ULX-3A")
        var query by mutableStateOf("")
        var results by mutableStateOf(emptyList<SolarSystem>())
        setContent {
            MaterialTheme {
                SystemSearchField(
                    value = query,
                    label = "Search system",
                    results = results,
                    onValueChange = { query = it },
                    onSelect = {},
                    suggestionsPresentation = presentation,
                )
            }
        }

        val textField = onNode(hasSetTextAction())
        textField.performClick().performTextInput("ULX-3A")
        waitForIdle()
        textField.performTextInputSelection(TextRange(3))
        textField.assertSelection(3)

        runOnIdle { results = listOf(ulx3a) }
        waitForIdle()
        textField.assertIsFocused().assertSelection(3)

        textField.performTextInput("Q")
        waitForIdle()
        textField.assertTextEquals("Search system", "ULXQ-3A")
        textField.assertSelection(4)

        textField.performTextInputSelection(TextRange(0, query.length))
        textField.performTextInput("DQA-11")
        waitForIdle()
        textField.assertTextEquals("Search system", "DQA-11")
        textField.assertSelection(6)
    }
}
