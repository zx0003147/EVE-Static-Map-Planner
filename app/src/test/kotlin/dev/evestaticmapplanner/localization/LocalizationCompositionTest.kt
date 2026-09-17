package dev.evestaticmapplanner.localization

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.evestaticmapplanner.preferences.LanguagePreferenceContent
import dev.evestaticmapplanner.ui.EveTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LocalizationCompositionTest {
    @Test
    fun `open main and Preferences content observe runtime locale changes in both directions`() = runComposeUiTest {
        var localization by mutableStateOf(AppLocalization(AppLocale.EN_US))
        var selectedLocale by mutableStateOf(AppLocale.EN_US)

        setContent {
            ProvideAppLocalization(localization) {
                EveTheme {
                    Column {
                        Text(LocalAppStrings.current.appTitle, Modifier.testTag("main-window-title"))
                        Text(LocalAppStrings.current.preferences.title, Modifier.testTag("preferences-window-title"))
                        LanguagePreferenceContent(
                            locale = selectedLocale,
                            onLocaleChange = { locale ->
                                selectedLocale = locale
                                localization = AppLocalization(locale)
                            },
                        )
                    }
                }
            }
        }

        onNodeWithText("EVE Static Map Planner").assertIsDisplayed()
        onNodeWithText("Preferences").assertIsDisplayed()
        onNodeWithText("Language").assertIsDisplayed()

        onNodeWithTag("language-selector").performClick()
        onNodeWithTag("language-option-zh-CN").performClick()
        waitForIdle()
        assertEquals(AppLocale.ZH_CN, selectedLocale)
        onNodeWithText("EVE 静态地图规划器").assertIsDisplayed()
        onNodeWithText("设置").assertIsDisplayed()
        onNodeWithText("语言").assertIsDisplayed()

        onNodeWithTag("language-selector").performClick()
        onNodeWithTag("language-option-en-US").performClick()
        waitForIdle()
        assertEquals(AppLocale.EN_US, selectedLocale)
        onNodeWithText("EVE Static Map Planner").assertIsDisplayed()
        onNodeWithText("Preferences").assertIsDisplayed()
        onNodeWithText("Language").assertIsDisplayed()
    }
}
