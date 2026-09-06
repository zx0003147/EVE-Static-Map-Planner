package dev.evestaticmapplanner.minimap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.preferences.MiniMapFollowMode
import dev.evestaticmapplanner.preferences.MiniMapInteractionMode
import dev.evestaticmapplanner.preferences.MiniMapPreferences
import dev.evestaticmapplanner.ui.EveTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MiniMapWindowTest {
    @Test
    fun `normal AUTO header is compact and diagnostics and bind live behind overflow`() = runComposeUiTest {
        val character = character(TrackedCharacterLocationStatus.CURRENT)
        val state = state(character, MiniMapFollowMode.AUTO)
        setContent {
            EveTheme {
                Box(Modifier.size(420.dp, 360.dp)) {
                    MiniMapContent(
                        state,
                        MiniMapViewModel(),
                        automaticFollowDiagnostic = "Matched foreground client by process identity",
                        onBindCurrentWindow = { "Bound" },
                    )
                }
            }
        }

        onNodeWithText("Pandogodzilla").assertIsDisplayed()
        onNodeWithText("S1 · CURRENT").assertIsDisplayed()
        onNodeWithText("AUTO").assertIsDisplayed()
        onNodeWithContentDescription("Pandogodzilla portrait").assertIsDisplayed()
        onNodeWithText("Tracking", substring = true).assertDoesNotExist()
        onNodeWithText("Matched foreground", substring = true).assertDoesNotExist()
        onNodeWithText("Bind current EVE client…").assertDoesNotExist()
        onNodeWithText("JB visual", substring = true).assertDoesNotExist()

        onNodeWithContentDescription("Mini-map options").performClick()
        onNodeWithText("Bind current EVE client…").assertIsDisplayed()
        onNodeWithText("Show diagnostics").performClick()
        onNodeWithText("Tracking 1/1", substring = true).assertIsDisplayed()
        onNodeWithText("Matched foreground client by process identity").assertIsDisplayed()
    }

    @Test
    fun `PINNED stale header keeps last-known system and does not expose toolbar settings`() = runComposeUiTest {
        val state = state(character(TrackedCharacterLocationStatus.STALE), MiniMapFollowMode.PINNED)
        setContent {
            EveTheme {
                Box(Modifier.size(420.dp, 360.dp)) {
                    MiniMapContent(state, MiniMapViewModel(), "unused") { "unused" }
                }
            }
        }

        onNodeWithText("PINNED").assertIsDisplayed()
        onNodeWithText("Last known: S1 · STALE").assertIsDisplayed()
        onNodeWithText("1 Stargate hop", substring = true).assertDoesNotExist()
        onNodeWithText("Ansiblex", substring = true).assertDoesNotExist()
        onNodeWithText("Validated", substring = true).assertDoesNotExist()
    }

    @Test
    fun `location presentation distinguishes current stale degraded and unknown`() {
        assertEquals("S1 · CURRENT", miniMapLocationLine(state(character(TrackedCharacterLocationStatus.CURRENT))))
        assertEquals("Last known: S1 · STALE", miniMapLocationLine(state(character(TrackedCharacterLocationStatus.STALE))))
        assertEquals("S1 · DEGRADED", miniMapLocationLine(state(character(TrackedCharacterLocationStatus.DEGRADED))))
        assertEquals(
            "Location unavailable · UNKNOWN",
            miniMapLocationLine(state(character(TrackedCharacterLocationStatus.UNKNOWN))),
        )
    }

    @Test
    fun `locked HUD keeps core identity and mode while hiding interactive chrome`() = runComposeUiTest {
        val state = state(character(TrackedCharacterLocationStatus.CURRENT), MiniMapFollowMode.AUTO)
        setContent {
            EveTheme {
                Box(Modifier.size(420.dp, 360.dp)) {
                    MiniMapContent(
                        state = state,
                        viewModel = MiniMapViewModel(),
                        automaticFollowDiagnostic = "unused",
                        hudPresentation = true,
                        hudOpacity = 0.6f,
                        interactionMode = MiniMapInteractionMode.HUD_LOCKED,
                        onBindCurrentWindow = { "unused" },
                    )
                }
            }
        }

        onNodeWithText("Pandogodzilla").assertIsDisplayed()
        onNodeWithText("S1 · CURRENT").assertIsDisplayed()
        onNodeWithText("AUTO").assertIsDisplayed()
        onNodeWithContentDescription("Mini-map options").assertDoesNotExist()
        onNodeWithText("Tracking", substring = true).assertDoesNotExist()
    }

    @Test
    fun `available capability with no authorized characters shows connection guidance`() = runComposeUiTest {
        val viewModel = MiniMapViewModel()
        viewModel.updateCharacters(emptyList())
        setContent {
            EveTheme {
                Box(Modifier.size(420.dp, 360.dp)) {
                    MiniMapContent(
                        state = viewModel.state.value,
                        viewModel = viewModel,
                        automaticFollowDiagnostic = "unused",
                        onBindCurrentWindow = { "unused" },
                    )
                }
            }
        }

        onNodeWithText("No tracked characters", substring = true).assertIsDisplayed()
        onNodeWithText("Connect a character in ESI Pack first.", substring = true).assertIsDisplayed()
    }

    private fun state(
        character: TrackedCharacterSnapshot,
        mode: MiniMapFollowMode = MiniMapFollowMode.AUTO,
    ) = MiniMapUiState(
        preferences = MiniMapPreferences(followMode = mode, pinnedCharacterId = character.characterId),
        characters = listOf(character),
        followedCharacterId = character.characterId,
        followedSystemName = character.solarSystemId?.let { "S1" },
        diagnostic = "Map unavailable",
    )

    private fun character(status: TrackedCharacterLocationStatus): TrackedCharacterSnapshot {
        val systemId = if (status == TrackedCharacterLocationStatus.UNKNOWN) null else 1
        return TrackedCharacterSnapshot(
            90_000_001,
            "Pandogodzilla",
            TrackedCharacterAuthorizationState.CONNECTED,
            true,
            systemId,
            status,
            systemId?.let { Instant.EPOCH },
            systemId?.let { Instant.EPOCH },
            systemId?.let { Instant.EPOCH },
            TrackedCharacterOnlineState.UNKNOWN,
            null,
            null,
        )
    }
}
