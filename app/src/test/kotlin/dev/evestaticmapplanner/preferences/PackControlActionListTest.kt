package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.featurepack.PackControlActionKey
import dev.evestaticmapplanner.featurepack.PackControlActionUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PackControlActionListTest {
    @Test
    fun `narrow generic controls keep every dynamic action visible`() = runComposeUiTest {
        val labels = listOf(
            "Add Character",
            "Refresh Locations",
            "Disconnect Pandogodzilla",
            "Disconnect Pandolilia",
            "Disconnect Oijghr",
        )
        val actions = labels.mapIndexed { index, label ->
            PackControlActionUiState(
                PackControlActionKey(PackId("test.pack"), "action.$index"),
                label,
                null,
                true,
            )
        }
        var invoked: PackControlActionKey? = null
        setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp)) {
                    PackControlActionList(actions, busyActionId = null, onInvoke = { invoked = it })
                }
            }
        }

        labels.forEach { onNodeWithText(it).assertIsDisplayed() }
        val firstTop = onNodeWithText(labels.first()).fetchSemanticsNode().boundsInRoot.top
        val lastTop = onNodeWithText(labels.last()).fetchSemanticsNode().boundsInRoot.top
        assertTrue(lastTop > firstTop, "actions must use reachable rows instead of one clipped horizontal line")
        onNodeWithText(labels.last()).performClick()
        assertEquals(actions.last().key, invoked)
    }
}
