package dev.evestaticmapplanner.route

import dev.evestaticmapplanner.core.jump.JumpRangePreset
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class JumpRangeUiTest {
    @Test
    fun `jump range presets and manual prompt are localized in English and Chinese`() {
        val english = AppStringsCatalog.forLocale(AppLocale.EN_US).route
        val chinese = AppStringsCatalog.forLocale(AppLocale.ZH_CN).route

        assertEquals(
            listOf(
                "Dreadnought / Carrier / Force Auxiliary",
                "Command Carrier",
                "Lancer Dreadnought",
                "Titan / Supercarrier",
                "Black Ops",
                "Capital Industrial Ship (Rorqual)",
                "Manual Range",
            ),
            JumpRangePreset.entries.map(english::jumpRangePreset),
        )
        assertEquals("Enter distance in LY", english.manualRangePrompt)
        assertEquals(
            listOf(
                "无畏舰 / 航母 / 战力辅助舰",
                "指挥航母",
                "枪骑兵无畏舰",
                "泰坦 / 超级航母",
                "黑隐特勤舰",
                "旗舰级工业舰（长须鲸）",
                "手动距离",
            ),
            JumpRangePreset.entries.map(chinese::jumpRangePreset),
        )
        assertEquals("请输入距离 LY", chinese.manualRangePrompt)
    }

    @Test
    fun `jump range configuration displays compact distances`() {
        assertEquals("7 LY", formatJumpRangeDistance(7.0))
        assertEquals("7.5 LY", formatJumpRangeDistance(7.5))
        assertEquals("6 LY", formatJumpRangeDistance(6.0))
    }
}
