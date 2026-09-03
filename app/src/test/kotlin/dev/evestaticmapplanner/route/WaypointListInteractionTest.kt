package dev.evestaticmapplanner.route

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WaypointListInteractionTest {
    @Test
    fun `direct mouse drag reorders only from the handle and remove remains independent`() = runComposeUiTest {
        val alpha = waypointSystem(1, "Alpha")
        val bravo = waypointSystem(2, "Bravo")
        var waypoints by mutableStateOf(listOf(alpha, bravo))
        val moves = mutableListOf<Pair<Int, Int>>()

        setContent {
            MaterialTheme {
                WaypointList(
                    waypoints = waypoints,
                    onMove = { fromIndex, toIndex ->
                        moves += fromIndex to toIndex
                        waypoints = waypoints.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                    },
                    onRemove = { index -> waypoints = waypoints.filterIndexed { itemIndex, _ -> itemIndex != index } },
                )
            }
        }

        val firstCenter = onNodeWithTag("$WAYPOINT_DRAG_HANDLE_TEST_TAG_PREFIX-0")
            .fetchSemanticsNode().boundsInRoot.center
        val secondCenter = onNodeWithTag("$WAYPOINT_DRAG_HANDLE_TEST_TAG_PREFIX-1")
            .fetchSemanticsNode().boundsInRoot.center
        val upwardDrag = firstCenter.y - secondCenter.y - 8f

        onNodeWithText("Bravo").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, upwardDrag))
            release()
        }
        runOnIdle {
            assertTrue(moves.isEmpty())
            assertEquals(listOf(alpha, bravo), waypoints)
        }

        onNodeWithTag("$WAYPOINT_DRAG_HANDLE_TEST_TAG_PREFIX-1").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, upwardDrag / 2f))
            moveBy(Offset(0f, upwardDrag / 2f))
            release()
        }
        runOnIdle {
            assertEquals(listOf(1 to 0), moves)
            assertEquals(listOf(bravo, alpha), waypoints)
        }

        onNodeWithTag("$WAYPOINT_REMOVE_TEST_TAG_PREFIX-0").performClick()
        runOnIdle { assertEquals(listOf(alpha), waypoints) }
    }
}

private fun waypointSystem(id: Int, name: String) = SolarSystem(
    id = id,
    constellationId = 10,
    regionId = 1,
    name = name,
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, 0.0),
    schematicPosition = null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)
