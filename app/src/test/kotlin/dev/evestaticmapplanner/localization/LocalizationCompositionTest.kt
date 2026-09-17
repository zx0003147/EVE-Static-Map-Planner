package dev.evestaticmapplanner.localization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.map.MapToolbarContent
import dev.evestaticmapplanner.preferences.LanguagePreferenceContent
import dev.evestaticmapplanner.route.NormalRouteConnectionOptions
import dev.evestaticmapplanner.route.RoutePlannerUiState
import dev.evestaticmapplanner.route.SIDEBAR_PROJECTION_TOGGLE_TEST_TAG
import dev.evestaticmapplanner.route.SIDEBAR_TOGGLE_TEST_TAG
import dev.evestaticmapplanner.route.SidebarControlCluster
import dev.evestaticmapplanner.ui.EveAlwaysOnTopButton
import dev.evestaticmapplanner.ui.EveTheme
import dev.evestaticmapplanner.view.PlanningView
import dev.evestaticmapplanner.view.PlanningViewId
import dev.evestaticmapplanner.view.PlanningViewsState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LocalizationCompositionTest {
    @Test
    fun `open map and route controls refresh immediately when locale changes`() = runComposeUiTest {
        var localization by mutableStateOf(AppLocalization(AppLocale.EN_US))
        val viewId = PlanningViewId("localization-view")
        val planningViews = PlanningViewsState(listOf(PlanningView(viewId, "View 1")), viewId)

        setContent {
            ProvideAppLocalization(localization) {
                EveTheme {
                    Column {
                        MapToolbarContent(
                            projectionId = MapProjectionId.OFFICIAL_2D,
                            fitEnabled = true,
                            planningViewsState = planningViews,
                            onSwitchView = { true },
                            onCreateView = { viewId },
                            onRenameView = {},
                            onDeleteView = { false },
                            onFitMap = {},
                        )
                        SidebarControlCluster(
                            expanded = true,
                            projectionId = MapProjectionId.OFFICIAL_2D,
                            onToggleProjection = {},
                            onOpenEmbeddedAi = {},
                            onToggleSidebar = {},
                        )
                        NormalRouteConnectionOptions(RoutePlannerUiState(), {}, {}, {})
                        Row(Modifier.height(40.dp)) {
                            EveAlwaysOnTopButton(isAlwaysOnTop = false, onClick = {})
                        }
                    }
                }
            }
        }

        onNodeWithText("Fit Map").assertIsDisplayed()
        onNodeWithText("Use Ansiblex").assertIsDisplayed()
        onNodeWithTag(SIDEBAR_PROJECTION_TOGGLE_TEST_TAG)
            .assertContentDescriptionEquals("Toggle 2D/3D map mode")
        assertEquals(
            "Official 2D selected",
            onNodeWithTag(SIDEBAR_PROJECTION_TOGGLE_TEST_TAG)
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        onNodeWithTag(SIDEBAR_TOGGLE_TEST_TAG).assertContentDescriptionEquals("Collapse sidebar")
        assertEquals(
            "Off",
            onNodeWithContentDescription("Keep window on top")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )

        runOnIdle { localization = AppLocalization(AppLocale.ZH_CN) }
        waitForIdle()

        onNodeWithText("适配地图").assertIsDisplayed()
        onNodeWithText("使用 Ansiblex").assertIsDisplayed()
        onNodeWithTag(SIDEBAR_PROJECTION_TOGGLE_TEST_TAG)
            .assertContentDescriptionEquals("切换 2D/3D 地图模式")
        assertEquals(
            "已选择官方 2D",
            onNodeWithTag(SIDEBAR_PROJECTION_TOGGLE_TEST_TAG)
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        onNodeWithTag(SIDEBAR_TOGGLE_TEST_TAG).assertContentDescriptionEquals("收起侧边栏")
        assertEquals(
            "已关闭",
            onNodeWithContentDescription("窗口置顶")
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

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
