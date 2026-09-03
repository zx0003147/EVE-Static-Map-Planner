package dev.evestaticmapplanner.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.feature.api.NavigationSnapshot
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSegment
import dev.evestaticmapplanner.feature.api.RouteSegmentKind
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.feature.api.RouteActionTargetId
import dev.evestaticmapplanner.feature.api.RouteActionTargetOption
import dev.evestaticmapplanner.feature.api.RouteActionTargetSnapshot
import dev.evestaticmapplanner.featurepack.RouteActionKey
import dev.evestaticmapplanner.featurepack.RouteActionUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class RouteActionButtonsTest {
    @Test
    fun `no or unsupported action leaves route UI unchanged`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RouteActionButtons(
                    listOf(action("Capital only", setOf(RouteKind.CAPITAL))),
                    snapshot(RouteKind.NORMAL),
                ) { _, _, _ -> }
            }
        }

        onNodeWithText("Capital only").assertDoesNotExist()
        onNodeWithText("Succeeded").assertDoesNotExist()
    }

    @Test
    fun `registered action appears and click captures the immutable snapshot`() = runComposeUiTest {
        var invoked: RouteSnapshot? = null
        setContent {
            MaterialTheme {
                RouteActionButtons(listOf(action("Send route")), snapshot(RouteKind.NORMAL)) { _, route, _ ->
                    invoked = route
                }
            }
        }

        onNodeWithText("Send route").assertIsDisplayed().assertIsEnabled().performClick()
        onNodeWithText("Draft only — EVE changes only after you press a button below.")
            .assertIsDisplayed()
        waitForIdle()
        assertEquals("normal-route", invoked?.identity?.value)
    }

    @Test
    fun `route draft changes never invoke an action until the button is clicked`() = runComposeUiTest {
        var currentSnapshot by mutableStateOf(snapshot(RouteKind.NORMAL))
        var invocationCount = 0
        setContent {
            MaterialTheme {
                RouteActionButtons(listOf(action("Send Draft to EVE")), currentSnapshot) { _, _, _ ->
                    invocationCount++
                }
            }
        }

        currentSnapshot = RouteSnapshot(
            RouteIdentity("changed-draft"),
            RouteKind.NORMAL,
            2,
            3,
            listOf(2, 3),
            listOf(RouteSegment(2, 3, RouteSegmentKind.STARGATE, null)),
        )
        waitForIdle()
        assertEquals(0, invocationCount)

        onNodeWithText("Send Draft to EVE").performClick()
        waitForIdle()
        assertEquals(1, invocationCount)
    }

    @Test
    fun `navigation-aware action uses current explicit draft instead of stale calculated route`() = runComposeUiTest {
        var invokedNavigation: NavigationSnapshot? = null
        var calculatedInvocationCount = 0
        val currentDraft = NavigationSnapshot(
            RouteIdentity("current-explicit-draft"),
            RouteKind.NORMAL,
            1,
            listOf(4, 6),
            9,
        )
        setContent {
            MaterialTheme {
                NavigationRouteActionButtons(
                    actions = listOf(action("Send Navigation to EVE", supportsNavigationIntent = true)),
                    snapshot = snapshot(RouteKind.NORMAL),
                    navigationSnapshot = currentDraft,
                    selectedTargetIds = emptyMap(),
                    onSelectTarget = { _, _ -> },
                    onInvoke = { _, _, _ -> calculatedInvocationCount++ },
                    onInvokeNavigation = { _, navigation, _ -> invokedNavigation = navigation },
                )
            }
        }

        onNodeWithText("Send Navigation to EVE").assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(0, calculatedInvocationCount)
        assertEquals("current-explicit-draft", invokedNavigation?.identity?.value)
        assertEquals(listOf(4, 6, 9), invokedNavigation?.orderedTargetSystemIds)
    }

    @Test
    fun `busy result and unregister states update without breaking the panel`() = runComposeUiTest {
        var actions by mutableStateOf(listOf(action("Send route", busy = true)))
        setContent {
            MaterialTheme {
                RouteActionButtons(actions, snapshot(RouteKind.NORMAL)) { _, _, _ -> }
            }
        }

        onNodeWithText("Send route…").assertIsDisplayed().assertIsNotEnabled()
        actions = listOf(action(
            "Send route",
            status = RouteActionStatus.FAILED,
            message = "Unable to send",
        ))
        waitForIdle()
        onNodeWithText("Send route").assertIsEnabled()
        onNodeWithText("Failed: Unable to send").assertIsDisplayed()

        actions = emptyList()
        waitForIdle()
        onNodeWithText("Send route").assertDoesNotExist()
        onNodeWithText("Failed: Unable to send").assertDoesNotExist()
    }

    @Test
    fun `shared target selector appears once and passes the explicit selection`() = runComposeUiTest {
        var selectedTargets by mutableStateOf(emptyMap<String, String>())
        var invokedTarget: RouteActionTargetId? = null
        val selector = targetSelector()
        setContent {
            MaterialTheme {
                RouteActionButtons(
                    actions = listOf(
                        action("Send Draft to EVE", id = "send", targetSelector = selector),
                        action("Set EVE Destination", id = "destination", targetSelector = selector),
                    ),
                    snapshot = snapshot(RouteKind.NORMAL),
                    selectedTargetIds = selectedTargets,
                    onSelectTarget = { key, value -> selectedTargets = selectedTargets + (key to checkNotNull(value)) },
                ) { _, _, target -> invokedTarget = target }
            }
        }

        onAllNodesWithText("EVE Character").assertCountEquals(1)
        onNodeWithText("Send Draft to EVE").assertIsNotEnabled()
        onNodeWithText("Select…").performClick()
        onNodeWithText("Pandogodzilla").performClick()
        onNodeWithText("Send Draft to EVE").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(RouteActionTargetId("90000001"), invokedTarget)
    }

    @Test
    fun `disconnected persisted target stays visible and does not fall back`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RouteActionButtons(
                    actions = listOf(action("Send Draft to EVE", targetSelector = targetSelector())),
                    snapshot = snapshot(RouteKind.NORMAL),
                    selectedTargetIds = mapOf("test.pack:eve-character" to "90000002"),
                ) { _, _, _ -> error("Unavailable target must not invoke") }
            }
        }

        onNodeWithText("90000002 (disconnected / unavailable)").assertIsDisplayed()
        onNodeWithText("Send Draft to EVE").assertIsNotEnabled()
    }

    @Test
    fun `narrow route actions stack selector buttons and result at equal full width`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp)) {
                    RouteActionButtons(
                        actions = listOf(
                            action("Send Draft to EVE", id = "send", targetSelector = targetSelector()),
                            action(
                                "Set EVE Destination",
                                id = "destination",
                                targetSelector = targetSelector(),
                                status = RouteActionStatus.REJECTED,
                                message = "Destination was rejected",
                            ),
                        ),
                        snapshot = snapshot(RouteKind.NORMAL),
                        selectedTargetIds = mapOf("test.pack:eve-character" to "90000001"),
                    ) { _, _, _ -> }
                }
            }
        }

        val label = onNodeWithText("EVE Character").fetchSemanticsNode().boundsInRoot
        val selector = onNodeWithText("Pandogodzilla").fetchSemanticsNode().boundsInRoot
        val send = onNodeWithText("Send Draft to EVE").fetchSemanticsNode().boundsInRoot
        val destination = onNodeWithText("Set EVE Destination").fetchSemanticsNode().boundsInRoot
        val result = onNodeWithText("Rejected: Destination was rejected").fetchSemanticsNode().boundsInRoot

        assertTrue(label.bottom <= selector.top)
        assertTrue(selector.bottom <= send.top)
        assertTrue(send.bottom <= destination.top)
        assertTrue(destination.bottom <= result.top)
        assertTrue(abs(selector.width - send.width) < 1f)
        assertTrue(abs(send.width - destination.width) < 1f)
        assertTrue(send.height < 70f && destination.height < 70f, "route action labels wrapped excessively")
    }

    private fun action(
        label: String,
        kinds: Set<RouteKind> = setOf(RouteKind.NORMAL),
        busy: Boolean = false,
        status: RouteActionStatus? = null,
        message: String? = null,
        id: String = "send",
        targetSelector: RouteActionTargetSnapshot? = null,
        supportsNavigationIntent: Boolean = false,
    ) = RouteActionUiState(
        RouteActionKey(PackId("test.pack"), id),
        label,
        null,
        kinds,
        busy,
        status,
        message,
        targetSelector,
        supportsNavigationIntent,
    )

    private fun targetSelector() = RouteActionTargetSnapshot(
        "eve-character",
        "EVE Character",
        listOf(RouteActionTargetOption(RouteActionTargetId("90000001"), "Pandogodzilla")),
    )

    private fun snapshot(kind: RouteKind) = RouteSnapshot(
        RouteIdentity("${kind.name.lowercase()}-route"),
        kind,
        1,
        2,
        listOf(1, 2),
        listOf(RouteSegment(
            1,
            2,
            if (kind == RouteKind.CAPITAL) RouteSegmentKind.CAPITAL_JUMP else RouteSegmentKind.STARGATE,
            if (kind == RouteKind.CAPITAL) 1.0 else null,
        )),
    )
}
