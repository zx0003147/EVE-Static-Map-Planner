package dev.evestaticmapplanner.localization

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppLocalizationTest {
    @Test
    fun `locale tags are stable persistence values`() {
        assertEquals("en-US", AppLocale.EN_US.tag)
        assertEquals("zh-CN", AppLocale.ZH_CN.tag)
        assertEquals(AppLocale.EN_US, AppLocale.fromTagOrNull("en-US"))
        assertEquals(AppLocale.ZH_CN, AppLocale.fromTagOrNull("zh-CN"))
        assertEquals(null, AppLocale.fromTagOrNull("zh-Hans"))
    }

    @Test
    fun `English and Simplified Chinese catalogs expose the same complete typed structure`() {
        val english = AppStringsCatalog.forLocale(AppLocale.EN_US)
        val chinese = AppStringsCatalog.forLocale(AppLocale.ZH_CN)

        assertEquals(AppLocale.EN_US, english.locale)
        assertEquals(AppLocale.ZH_CN, chinese.locale)
        assertEquals(staticStrings(english).size, staticStrings(chinese).size)
        assertTrue(staticStrings(english).all(String::isNotBlank))
        assertTrue(staticStrings(chinese).all(String::isNotBlank))
    }

    @Test
    fun `parameterized strings preserve typed semantic parameters in both catalogs`() {
        assertEquals("Route found: 4 jumps", AppStringsCatalog.forLocale(AppLocale.EN_US).route.routeFound(4))
        assertEquals("已找到路线：4 跳", AppStringsCatalog.forLocale(AppLocale.ZH_CN).route.routeFound(4))

        val message = RouteFoundUiMessage(4)
        assertEquals("Route found: 4 jumps", message.resolve(AppStringsCatalog.forLocale(AppLocale.EN_US)))
        assertEquals("已找到路线：4 跳", message.resolve(AppStringsCatalog.forLocale(AppLocale.ZH_CN)))
    }

    @Test
    fun `runtime localization changes English to Chinese and back without changing JVM locale`() {
        val jvmLocale = Locale.getDefault()
        val state = AppLocalizationState(AppLocale.EN_US)

        assertEquals("EVE Static Map Planner", state.state.value.strings.appTitle)
        assertEquals("Preferences", state.state.value.strings.preferences.title)

        state.updateLocale(AppLocale.ZH_CN)
        assertEquals("EVE 静态地图规划器", state.state.value.strings.appTitle)
        assertEquals("设置", state.state.value.strings.preferences.title)

        state.updateLocale(AppLocale.EN_US)
        assertEquals("EVE Static Map Planner", state.state.value.strings.appTitle)
        assertEquals("Preferences", state.state.value.strings.preferences.title)
        assertEquals(jvmLocale, Locale.getDefault())
    }

    @Test
    fun `changing application locale does not localize developer diagnostic formatting`() {
        val state = AppLocalizationState(AppLocale.EN_US)
        val before = String.format(Locale.ROOT, "diagnostic.value=%.2f", 1.5)

        state.updateLocale(AppLocale.ZH_CN)
        val after = String.format(Locale.ROOT, "diagnostic.value=%.2f", 1.5)

        assertEquals("diagnostic.value=1.50", before)
        assertEquals(before, after)
        assertNotEquals(state.state.value.strings.preferences.language, "Language")
    }

    private fun staticStrings(strings: AppStrings): List<String> = listOf(
        strings.appTitle,
        strings.common.ok,
        strings.common.cancel,
        strings.common.close,
        strings.common.apply,
        strings.preferences.title,
        strings.preferences.language,
        strings.preferences.english,
        strings.preferences.simplifiedChinese,
    )
}
