package dev.evestaticmapplanner.staticdata

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.StaticDatabaseMode
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppLocalization
import dev.evestaticmapplanner.localization.ProvideAppLocalization
import dev.evestaticmapplanner.ui.EveTheme
import kotlin.io.path.Path
import kotlin.test.Test
import kotlinx.coroutines.test.TestScope

@OptIn(ExperimentalTestApi::class)
class StaticDataManagerScreenTest {
    @Test
    fun `Static Data setup renders Chinese labels without changing build ID or path`() = runComposeUiTest {
        val viewModel = StaticDataManagerViewModel(
            mode = StaticDatabaseMode.EXTERNAL,
            databasePath = Path("fixture-static.db"),
            currentBuild = 3_466_501,
            service = null,
            scope = TestScope(),
            autoCheck = false,
        )
        try {
            setContent {
                ProvideAppLocalization(AppLocalization(AppLocale.ZH_CN)) {
                    EveTheme {
                        Box(Modifier.requiredSize(900.dp, 700.dp)) {
                            StaticDataBootstrapScreen(viewModel.state.value, viewModel)
                        }
                    }
                }
            }

            onNodeWithText("静态数据设置").assertIsDisplayed()
            onNodeWithText("尚未安装静态数据").assertIsDisplayed()
            onNodeWithText("外部数据库").assertIsDisplayed()
            onNodeWithText("当前版本").assertIsDisplayed()
            onNodeWithText("3466501").assertIsDisplayed()
            onNodeWithText("fixture-static.db").assertIsDisplayed()
            onNodeWithText("无法自动替换此外部数据库文件。").assertIsDisplayed()
        } finally {
            viewModel.close()
        }
    }
}
