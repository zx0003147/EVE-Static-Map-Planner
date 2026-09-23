package dev.evestaticmapplanner.route

import dev.evestaticmapplanner.localization.AnsiblexDataUnavailableUiMessage
import dev.evestaticmapplanner.core.identity.CurrentIdentityContext
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NormalRouteWormholeUiTest {
    @Test
    fun `Use Wormholes is wired between Ansiblex options and remains enabled without user database`() =
        runComposeUiTest {
            var toggled: Boolean? = null
            setContent {
                MaterialTheme {
                    Column {
                        NormalRouteConnectionOptions(
                            state = RoutePlannerUiState(
                                isLoading = false,
                                userDatabaseError = AnsiblexDataUnavailableUiMessage,
                                useWormholes = true,
                            ),
                            onUseAnsiblexChanged = {},
                            onUseWormholesChanged = { toggled = it },
                        )
                    }
                }
            }

            onNodeWithText("Use Wormholes").assertIsDisplayed()
            onNodeWithTag(USE_WORMHOLES_CHECKBOX_TAG).assertIsEnabled().assertIsOn().performClick()
            waitForIdle()
            assertFalse(checkNotNull(toggled))

            val useAnsiblex = onNodeWithText("Use Ansiblex").fetchSemanticsNode().boundsInRoot
            val useWormholes = onNodeWithText("Use Wormholes").fetchSemanticsNode().boundsInRoot
            assertTrue(useAnsiblex.bottom <= useWormholes.top)
        }

    @Test
    fun `route summary omits zero Wormholes and reports one or mixed Wormhole jumps`() {
        val noWormholes = route(listOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX))
        val oneWormhole = route(listOf(RouteEdgeType.WORMHOLE))
        val mixed = route(listOf(RouteEdgeType.STARGATE, RouteEdgeType.ANSIBLEX, RouteEdgeType.WORMHOLE))

        assertEquals("2 jumps · 1 Stargate · 1 Ansiblex", normalRouteSummaryText(noWormholes))
        assertEquals("1 jump · 0 Stargates · 0 Ansiblex · 1 Wormhole", normalRouteSummaryText(oneWormhole))
        assertEquals("3 jumps · 1 Stargate · 1 Ansiblex · 1 Wormhole", normalRouteSummaryText(mixed))
    }

    @Test
    fun `Wormhole Manager remains enabled when routing use is off and Ansiblex is unavailable`() = runComposeUiTest {
        var openCount = 0
        setContent {
            MaterialTheme {
                Column {
                    RouteManagerButtons(
                        state = RoutePlannerUiState(
                            isLoading = false,
                            userDatabaseError = AnsiblexDataUnavailableUiMessage,
                            useWormholes = false,
                        ),
                        onOpenWormholeManager = { openCount++ },
                    )
                }
            }
        }

        onNodeWithText("Wormhole Manager (0)").assertIsEnabled().performClick()
        assertEquals(1, openCount)
    }

    @Test
    fun `Ansiblex section exposes identity existing visibility preference and manager`() = runComposeUiTest {
        var showUnavailable: Boolean? = null
        var switchCount = 0
        var managerCount = 0
        setContent {
            MaterialTheme {
                AnsiblexSectionContent(
                    state = RoutePlannerUiState(
                        isLoading = false,
                        showAnsiblexLayer = true,
                        currentIdentityContext = CurrentIdentityContext.manual(99, "Selected Alliance", "SEL"),
                    ),
                    onSwitchIdentity = { switchCount++ },
                    onShowUnavailableChanged = { showUnavailable = it },
                    onOpenManager = { managerCount++ },
                )
            }
        }

        onNodeWithText("Manual alliance simulation").assertIsDisplayed()
        onNodeWithText("[SEL] Selected Alliance #99").assertIsDisplayed()
        onNodeWithText("Switch").performClick()
        onNodeWithTag(SHOW_UNAVAILABLE_ANSIBLEX_TAG).assertIsOn().performClick()
        onNodeWithText("Ansiblex Manager").performClick()
        assertEquals(1, switchCount)
        assertEquals(false, showUnavailable)
        assertEquals(1, managerCount)
    }

    private fun route(types: List<RouteEdgeType>): RouteResult {
        val systems = (1..types.size + 1).toList()
        val edges = types.mapIndexed { index, type ->
            val from = systems[index]
            val to = systems[index + 1]
            RouteEdge(
                RouteEdgeId("${type.name.lowercase()}:$from:$to"),
                RouteConnectionId("${type.name.lowercase()}:$from:$to"),
                from,
                to,
                type,
            )
        }
        return RouteResult(systems.first(), systems.last(), systems, edges)
    }
}
