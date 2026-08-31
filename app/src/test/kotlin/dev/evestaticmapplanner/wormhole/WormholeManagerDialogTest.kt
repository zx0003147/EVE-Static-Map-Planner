package dev.evestaticmapplanner.wormhole

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WormholeManagerDialogTest {
    @Test
    fun `manager from and to keep caret and edited selections invalidate immediately`() = runComposeUiTest {
        val systems = searchSystems()
        val viewModel = viewModel(systems)
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                Box(Modifier.requiredSize(860.dp, 640.dp)) {
                    WormholeManagerContent(state, viewModel, onDismiss = {}, onRequestClearAll = {})
                }
            }
        }

        val fields = onAllNodes(hasSetTextAction())
        val from = fields[0]
        val to = fields[1]
        typeSequentially(from, "1DQ1-A")
        from.assertTextEquals("From", "1DQ1-A").assertSelection(6)
        onNodeWithText("1DQ1-A  ·  30000100").performClick()
        runOnIdle { assertEquals(systems[0], viewModel.state.value.selectedManagerFrom) }

        to.performClick().assertIsFocused()
        from.assertTextEquals("From", "1DQ1-A")
        typeSequentially(to, "4-HWWF")
        to.assertTextEquals("To", "4-HWWF").assertSelection(6)
        onNodeWithText("4-HWWF  ·  30000101").performClick()
        runOnIdle { assertEquals(systems[1], viewModel.state.value.selectedManagerTo) }

        from.performClick().performTextInputSelection(TextRange(3))
        from.assertSelection(3).performTextInput("X")
        waitForIdle()
        from.assertTextEquals("From", "1DQX1-A").assertSelection(4)
        runOnIdle {
            assertEquals(null, viewModel.state.value.selectedManagerFrom)
            assertEquals(systems[1], viewModel.state.value.selectedManagerTo)
        }
    }

    @Test
    fun `quick create to keeps caret and edited selection invalidates immediately`() = runComposeUiTest {
        val systems = searchSystems()
        val viewModel = viewModel(systems)
        viewModel.beginQuickCreate(systems[0])
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                CreateWormholeDialog(state, viewModel, onCreated = {}, onDismiss = {})
            }
        }

        val to = onAllNodes(hasSetTextAction())[0]
        typeSequentially(to, "ULX-3A")
        to.assertTextEquals("To", "ULX-3A").assertSelection(6)
        onNodeWithText("ULX-3A  ·  30000102").performClick()
        runOnIdle { assertEquals(systems[2], viewModel.state.value.selectedQuickTo) }

        to.performClick()
        to.performTextInputSelection(TextRange(3))
        to.performTextInput("Q")
        waitForIdle()
        to.assertTextEquals("To", "ULXQ-3A").assertSelection(4)
        runOnIdle { assertEquals(null, viewModel.state.value.selectedQuickTo) }
    }

    @Test
    fun `manager sizing keeps the add form and connection list usable`() {
        assertTrue(WORMHOLE_MANAGER_DEFAULT_SIZE.width >= WORMHOLE_MANAGER_MINIMUM_SIZE.width)
        assertTrue(WORMHOLE_MANAGER_DEFAULT_SIZE.height >= WORMHOLE_MANAGER_MINIMUM_SIZE.height)
        assertTrue(WORMHOLE_MANAGER_MINIMUM_SIZE.width >= WORMHOLE_MANAGER_FORM_WIDTH + 400.dp)
    }

    @Test
    fun `manager root follows a resized window`() = runComposeUiTest {
        var width by mutableStateOf(860.dp)
        var height by mutableStateOf(640.dp)
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width, height)) { WormholeManagerRoot {} }
            }
        }

        onNodeWithTag(WORMHOLE_MANAGER_ROOT_TEST_TAG).assertWidthIsEqualTo(860.dp).assertHeightIsEqualTo(640.dp)
        width = 1_100.dp
        height = 760.dp
        waitForIdle()
        onNodeWithTag(WORMHOLE_MANAGER_ROOT_TEST_TAG).assertWidthIsEqualTo(1_100.dp).assertHeightIsEqualTo(760.dp)
    }

    @Test
    fun `empty manager disables add and clear all independently of route options`() = runComposeUiTest {
        val viewModel = viewModel()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(860.dp, 640.dp)) {
                    WormholeManagerContent(
                        state = WormholeUiState(isLoading = false),
                        viewModel = viewModel,
                        onDismiss = {},
                        onRequestClearAll = {},
                    )
                }
            }
        }

        onNodeWithText("0 active session connections").assertIsDisplayed()
        onNodeWithText("No active Wormhole connections").assertIsDisplayed()
        onNodeWithTag(WORMHOLE_MANAGER_ADD_TEST_TAG).assertIsNotEnabled()
        onNodeWithTag(WORMHOLE_MANAGER_CLEAR_ALL_TEST_TAG).assertIsNotEnabled()
    }

    @Test
    fun `clear all confirmation supports cancel and confirm`() = runComposeUiTest {
        var cancelCount = 0
        var confirmCount = 0
        setContent {
            MaterialTheme {
                WormholeClearAllConfirmationDialog(
                    onConfirm = { confirmCount++ },
                    onDismiss = { cancelCount++ },
                )
            }
        }

        onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelCount)
        onNodeWithText("Clear All").assertIsEnabled().performClick()
        assertEquals(1, confirmCount)
    }

    @Test
    fun `quick create shows fixed clicked origin and blocks self loop`() = runComposeUiTest {
        val systems = systems()
        val viewModel = viewModel(systems)
        viewModel.beginQuickCreate(systems[0])
        viewModel.selectQuickTo(systems[0])
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                CreateWormholeDialog(state, viewModel, onCreated = {}, onDismiss = {})
            }
        }

        onNodeWithTag(WORMHOLE_QUICK_ORIGIN_TEST_TAG).assertIsDisplayed()
        onNodeWithText(SAME_ENDPOINT_MESSAGE).assertIsDisplayed()
        onNodeWithTag(WORMHOLE_QUICK_ADD_TEST_TAG).assertIsNotEnabled()
    }

    @Test
    fun `quick duplicate stays open with business feedback`() = runComposeUiTest {
        val systems = systems()
        val store = WormholeSessionStore().also { it.add(1, 2) }
        val viewModel = viewModel(systems, store)
        viewModel.beginQuickCreate(systems[0])
        viewModel.selectQuickTo(systems[1])
        var createdCount = 0
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                CreateWormholeDialog(state, viewModel, onCreated = { createdCount++ }, onDismiss = {})
            }
        }

        onNodeWithTag(WORMHOLE_QUICK_ADD_TEST_TAG).assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithText(WORMHOLE_DUPLICATE_MESSAGE).assertIsDisplayed()
        assertEquals(0, createdCount)
    }

    @Test
    fun `quick connection dialog lists only other endpoints and closes after last removal`() = runComposeUiTest {
        val systems = systems()
        val store = WormholeSessionStore().also {
            it.add(1, 2)
            it.add(3, 4)
        }
        val viewModel = viewModel(systems, store)
        var dismissCount = 0
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                WormholeConnectionsDialog(1, "Alpha", state, viewModel, onDismiss = { dismissCount++ })
            }
        }

        onNodeWithText("Bravo").assertIsDisplayed()
        onNodeWithText("Charlie").assertDoesNotExist()
        onAllNodesWithText("Remove")[0].performClick()
        waitForIdle()
        assertEquals(1, dismissCount)
        assertEquals(listOf("wormhole:3:4"), store.connections.value.map { it.id })
    }

    private fun viewModel(
        systems: List<SolarSystem> = systems(),
        store: WormholeSessionStore = WormholeSessionStore(),
    ) = WormholeViewModel(
        store = store,
        staticMapRepository = StaticMapRepository { StaticMapData(systems, emptyList()) },
        searchRepository = object : SystemSearchRepository {
            override fun searchSystems(query: String, limit: Int): List<SolarSystem> = systems
                .filter { it.name.startsWith(query, ignoreCase = true) }
                .take(limit)
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
        searchDebounceMillis = 0,
    )

    private fun systems() = listOf(
        system(1, "Alpha"),
        system(2, "Bravo"),
        system(3, "Charlie"),
        system(4, "Delta"),
    )

    private fun searchSystems() = listOf(
        system(30_000_100, "1DQ1-A"),
        system(30_000_101, "4-HWWF"),
        system(30_000_102, "ULX-3A"),
        system(30_000_103, "DQA-11"),
    )

    private fun system(id: Int, name: String) = SolarSystem(
        id = id,
        constellationId = 20_000_001,
        regionId = 10_000_001,
        name = name,
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(id.toDouble(), 0.0, 0.0),
        schematicPosition = SchematicPosition(id.toDouble(), 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.typeSequentially(
        field: SemanticsNodeInteraction,
        text: String,
    ) {
        field.performClick()
        text.forEachIndexed { index, character ->
            field.performTextInput(character.toString())
            waitForIdle()
            field.assertIsFocused().assertSelection(index + 1)
        }
    }

    private fun SemanticsNodeInteraction.assertSelection(offset: Int): SemanticsNodeInteraction {
        val actual = fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(offset), actual)
        return this
    }
}
