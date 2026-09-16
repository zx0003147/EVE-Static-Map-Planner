package dev.evestaticmapplanner.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.map.MapProjectionId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SidebarControlClusterTest {
    @Test
    fun `expanded controls are equal width in 2D Aura Collapse order`() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(270.dp, 44.dp)) {
                    SidebarControlCluster(
                        expanded = true,
                        projectionId = MapProjectionId.OFFICIAL_2D,
                        onToggleProjection = { calls += "projection" },
                        onOpenEmbeddedAi = { calls += "ai" },
                        onToggleSidebar = { calls += "collapse" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        val projection = onNodeWithTag(SIDEBAR_PROJECTION_TOGGLE_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val ai = onNodeWithTag(SIDEBAR_AI_BUTTON_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val collapse = onNodeWithTag(SIDEBAR_TOGGLE_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(projection.left < ai.left && ai.left < collapse.left)
        assertTrue(abs(projection.width - ai.width) <= 1.1f)
        assertTrue(abs(ai.width - collapse.width) <= 1.1f)

        onNodeWithTag(SIDEBAR_PROJECTION_TOGGLE_TEST_TAG).performClick()
        onNodeWithTag(SIDEBAR_AI_BUTTON_TEST_TAG).performClick()
        onNodeWithTag(SIDEBAR_TOGGLE_TEST_TAG).performClick()
        assertEquals(listOf("projection", "ai", "collapse"), calls)
    }

    @Test
    fun `collapsed controls are equal height in 3D Aura Expand order`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(48.dp, 132.dp)) {
                    SidebarControlCluster(
                        expanded = false,
                        projectionId = MapProjectionId.REAL_3D,
                        onToggleProjection = {},
                        onOpenEmbeddedAi = {},
                        onToggleSidebar = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        val projection = onNodeWithTag(SIDEBAR_PROJECTION_TOGGLE_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val ai = onNodeWithTag(SIDEBAR_AI_BUTTON_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val expand = onNodeWithTag(SIDEBAR_TOGGLE_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(projection.top < ai.top && ai.top < expand.top)
        assertTrue(abs(projection.height - ai.height) < 1f)
        assertTrue(abs(ai.height - expand.height) < 1f)
    }
}
