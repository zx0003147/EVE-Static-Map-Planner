package dev.evestaticmapplanner.ansiblex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.alliance.AllianceDetailResponse
import dev.evestaticmapplanner.alliance.AllianceDetailTransport
import dev.evestaticmapplanner.alliance.PublicAllianceMetadataService
import dev.evestaticmapplanner.core.alliance.AllianceDirectoryMerger
import dev.evestaticmapplanner.core.alliance.AllianceDirectorySourceSnapshot
import dev.evestaticmapplanner.core.alliance.AllianceReference
import dev.evestaticmapplanner.core.identity.CurrentIdentitySource
import dev.evestaticmapplanner.core.identity.EveAllianceIdentity
import dev.evestaticmapplanner.core.identity.EveCharacterIdentity
import dev.evestaticmapplanner.core.identity.EveCorporationIdentity
import dev.evestaticmapplanner.core.identity.EveIdentity
import dev.evestaticmapplanner.preferences.AnsiblexIdentitySource
import dev.evestaticmapplanner.preferences.AnsiblexPreferences
import dev.evestaticmapplanner.search.ALLIANCE_SEARCH_FIELD_TAG
import dev.evestaticmapplanner.search.ALLIANCE_SEARCH_RESULT_TAG_PREFIX
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class AnsiblexIdentityDialogTest {
    @Test
    fun `manual selection persists stable ID and display metadata`() {
        val preferences = manualIdentityPreferences(AllianceReference(99, "Selected Alliance", "SEL"))

        assertEquals(AnsiblexIdentitySource.MANUAL, preferences.identitySource)
        assertEquals(99L, preferences.manualAllianceId)
        assertEquals("Selected Alliance", preferences.manualAllianceName)
        assertEquals("SEL", preferences.manualAllianceTicker)
        val context = preferences.currentIdentityContext(null)
        assertEquals(CurrentIdentitySource.MANUAL, context?.source)
        assertEquals(99L, context?.allianceId)
    }

    @Test
    fun `ESI source can be selected while unavailable without inventing a manual identity`() {
        val preferences = manualIdentityPreferences(AllianceReference(99, "Selected Alliance", "SEL"))
            .copy(identitySource = AnsiblexIdentitySource.ESI)

        assertNull(preferences.currentIdentityContext(null))
    }

    @Test
    fun `dialog defaults to ESI and manual search selection stores stable ID then can switch back`() = runComposeUiTest {
        var preferences = AnsiblexPreferences()
        val directory = AllianceDirectoryMerger.merge(
            listOf(
                AllianceDirectorySourceSnapshot(
                    "test",
                    listOf(AllianceReference(99, "Goonswarm Federation", "CONDI")),
                ),
            ),
        )
        setContent {
            MaterialTheme {
                AnsiblexIdentityDialog(
                    esiIdentity = identity(),
                    directorySnapshot = directory,
                    preferences = preferences,
                    metadataService = metadataService(),
                    onVerifiedAlliance = {},
                    onPreferencesChange = { preferences = it },
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Pilot").assertIsDisplayed()
        onNodeWithText("[CORP] Corporation").assertIsDisplayed()
        onNodeWithText("[ESI] ESI Alliance").assertIsDisplayed()
        onNodeWithText("Simulate another alliance").performClick()
        onNodeWithTag(ALLIANCE_SEARCH_FIELD_TAG).performTextInput("CON")
        onNodeWithTag("$ALLIANCE_SEARCH_RESULT_TAG_PREFIX-99").performClick()
        assertEquals(AnsiblexIdentitySource.MANUAL, preferences.identitySource)
        assertEquals(99L, preferences.manualAllianceId)
        onNodeWithText("ESI current identity").performClick()
        assertEquals(AnsiblexIdentitySource.ESI, preferences.identitySource)
    }

    @Test
    fun `dialog reports unavailable ESI while manual directory remains usable`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AnsiblexIdentityDialog(
                    esiIdentity = null,
                    directorySnapshot = AllianceDirectoryMerger.merge(
                        listOf(AllianceDirectorySourceSnapshot("test", listOf(AllianceReference(99, "Alliance", "ALLY")))),
                    ),
                    preferences = AnsiblexPreferences(),
                    metadataService = metadataService(),
                    onVerifiedAlliance = {},
                    onPreferencesChange = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("ESI Identity unavailable").assertIsDisplayed()
        onNodeWithText("Simulate another alliance").performClick()
        onNodeWithTag(ALLIANCE_SEARCH_FIELD_TAG).assertIsDisplayed()
    }

    private fun identity() = EveIdentity(
        EveCharacterIdentity(1, "Pilot"),
        EveCorporationIdentity(2, "Corporation", "CORP"),
        EveAllianceIdentity(3, "ESI Alliance", "ESI"),
        Instant.EPOCH.toEpochMilli(),
    )

    private fun metadataService() = PublicAllianceMetadataService(
        createTempDirectory("identity-dialog-metadata"),
        AllianceDetailTransport { AllianceDetailResponse(404, "") },
    )
}
