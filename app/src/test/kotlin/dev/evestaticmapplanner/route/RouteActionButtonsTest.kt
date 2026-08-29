package dev.evestaticmapplanner.route

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.feature.api.RouteSegment
import dev.evestaticmapplanner.feature.api.RouteSegmentKind
import dev.evestaticmapplanner.feature.api.RouteSnapshot
import dev.evestaticmapplanner.featurepack.RouteActionKey
import dev.evestaticmapplanner.featurepack.RouteActionUiState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RouteActionButtonsTest {
    @Test
    fun `no or unsupported action leaves route UI unchanged`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RouteActionButtons(
                    listOf(action("Capital only", setOf(RouteKind.CAPITAL))),
                    snapshot(RouteKind.NORMAL),
                ) { _, _ -> }
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
                RouteActionButtons(listOf(action("Send route")), snapshot(RouteKind.NORMAL)) { _, route ->
                    invoked = route
                }
            }
        }

        onNodeWithText("Send route").assertIsDisplayed().assertIsEnabled().performClick()
        waitForIdle()
        assertEquals("normal-route", invoked?.identity?.value)
    }

    @Test
    fun `busy result and unregister states update without breaking the panel`() = runComposeUiTest {
        var actions by mutableStateOf(listOf(action("Send route", busy = true)))
        setContent {
            MaterialTheme {
                RouteActionButtons(actions, snapshot(RouteKind.NORMAL)) { _, _ -> }
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

    private fun action(
        label: String,
        kinds: Set<RouteKind> = setOf(RouteKind.NORMAL),
        busy: Boolean = false,
        status: RouteActionStatus? = null,
        message: String? = null,
    ) = RouteActionUiState(
        RouteActionKey(PackId("test.pack"), "send"),
        label,
        null,
        kinds,
        busy,
        status,
        message,
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
