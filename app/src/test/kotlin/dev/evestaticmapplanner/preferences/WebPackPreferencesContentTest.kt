package dev.evestaticmapplanner.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.ui.EveTheme
import dev.evestaticmapplanner.webpack.WebPackCounts
import dev.evestaticmapplanner.webpack.WebPackExportReport
import dev.evestaticmapplanner.webpack.WebPackExportUiState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WebPackPreferencesContentTest {
    @Test
    fun `idle content exposes the read-only export action`() = runComposeUiTest {
        var exports = 0
        setContent {
            EveTheme {
                Column {
                    WebPackPreferencesContent(WebPackExportUiState.Idle) { exports++ }
                }
            }
        }

        onNodeWithText("Export Web Pack").assertIsDisplayed().performClick()
        onNodeWithText("does not modify static.db or user.db", substring = true).assertIsDisplayed()
        assertEquals(1, exports)
    }

    @Test
    fun `success content shows auditable export details`() = runComposeUiTest {
        val directory = Path.of("C:\\fixture\\EVE-Web-Pack")
        val report = WebPackExportReport(
            outputDirectory = directory,
            manifestPath = directory.resolve("manifest.json"),
            packPath = directory.resolve("web-pack-fixture.json.gz"),
            schemaVersion = 1,
            packVersion = "fixture",
            generatedAt = "2026-09-08T01:02:03Z",
            desktopAppVersion = "1.7.0",
            sdeBuild = 3_466_501L,
            counts = WebPackCounts(8_490, 6_989, 114, 1_184, 3),
            packSizeBytes = 123,
            packSha256 = "0".repeat(64),
        )
        setContent {
            EveTheme {
                Column {
                    WebPackPreferencesContent(WebPackExportUiState.Success(report)) {}
                }
            }
        }

        onNodeWithText("Web Pack exported successfully").assertIsDisplayed()
        onNodeWithText("SDE: 3466501").assertIsDisplayed()
        onNodeWithText("Systems: 8490").assertIsDisplayed()
        onNodeWithText("Stargate links: 6989").assertIsDisplayed()
        onNodeWithText("Ansiblex links: 3").assertIsDisplayed()
        onNodeWithText("Pack schema: 1").assertIsDisplayed()
        onNodeWithText("Pack version: fixture").assertIsDisplayed()
    }
}
