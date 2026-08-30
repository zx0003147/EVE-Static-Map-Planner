package dev.evestaticmapplanner.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.view.PlanningView
import dev.evestaticmapplanner.view.PlanningViewId
import dev.evestaticmapplanner.view.PlanningViewsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MapToolbarTest {
    @Test
    fun `compact toolbar is forty dp and preserves every action`() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val state = state(2)
        setContent {
            MapToolbarContent(
                projectionId = MapProjectionId.OFFICIAL_2D,
                fitEnabled = true,
                planningViewsState = state,
                onSwitchProjection = { calls += "projection:${it.name}" },
                onSwitchView = { calls += "switch:${it.value}"; true },
                onCreateView = { calls += "create"; PlanningViewId("created") },
                onRenameView = { calls += "rename:${it.id.value}" },
                onDeleteView = { calls += "delete:${it.value}"; true },
                onFitMap = { calls += "fit" },
                modifier = Modifier.testTag(TOOLBAR_TAG),
            )
        }

        onNodeWithTag(TOOLBAR_TAG).assertHeightIsEqualTo(MAP_TOOLBAR_EXPECTED_HEIGHT)
        assertEquals(40, MAP_TOOLBAR_EXPECTED_HEIGHT.value.toInt())
        assertTrue(MAP_TOOLBAR_EXPECTED_HEIGHT < 56.dp)
        listOf("Official 2D", "Real X-Z", "View 1", "View 2", "✎", "×", "+", "Fit Map").forEach {
            onNodeWithText(it).assertIsDisplayed()
        }

        onNodeWithText("Real X-Z").performClick()
        onNodeWithText("View 2").performClick()
        onNodeWithText("✎").performClick()
        onNodeWithText("×").performClick()
        onNodeWithText("+").performClick()
        onNodeWithText("Fit Map").performClick()

        assertEquals(
            listOf("projection:REAL_XZ", "switch:view-2", "rename:view-1", "delete:view-1", "create", "fit"),
            calls,
        )
    }

    @Test
    fun `narrow toolbar keeps projections and fit fixed while wheel scrolls views horizontally`() = runComposeUiTest {
        var createCount = 0
        val viewScrollState = ScrollState(0)
        setContent {
            Box(Modifier.width(360.dp)) {
                MapToolbarContent(
                    projectionId = MapProjectionId.OFFICIAL_2D,
                    fitEnabled = true,
                    planningViewsState = state(8),
                    onSwitchProjection = {},
                    onSwitchView = { true },
                    onCreateView = { createCount += 1; PlanningViewId("created-$createCount") },
                    onRenameView = {},
                    onDeleteView = { true },
                    onFitMap = {},
                    viewScrollState = viewScrollState,
                    modifier = Modifier.testTag(TOOLBAR_TAG),
                )
            }
        }

        onNodeWithTag(TOOLBAR_TAG)
            .assertWidthIsEqualTo(360.dp)
            .assertHeightIsEqualTo(MAP_TOOLBAR_EXPECTED_HEIGHT)
        onNodeWithText("Official 2D").assertIsDisplayed()
        onNodeWithText("Real X-Z").assertIsDisplayed()
        onNodeWithText("Fit Map").assertIsDisplayed().performClick()

        onNode(hasScrollAction())
        runOnIdle { repeat(20) { dispatchViewWheelScroll(viewScrollState, 3f) } }
        assertTrue(
            viewScrollState.value > 0,
            "mouse wheel did not advance horizontal View scrolling: value=${viewScrollState.value}, max=${viewScrollState.maxValue}",
        )
        onNodeWithText("View 8").assertIsDisplayed()
        onNodeWithText("+").assertIsDisplayed().performClick()
        assertEquals(1, createCount)
    }

    private fun state(count: Int): PlanningViewsState {
        val views = (1..count).map { index -> PlanningView(PlanningViewId("view-$index"), "View $index") }
        return PlanningViewsState(views, views.first().id)
    }

    private companion object {
        const val TOOLBAR_TAG = "map-toolbar"
    }
}
