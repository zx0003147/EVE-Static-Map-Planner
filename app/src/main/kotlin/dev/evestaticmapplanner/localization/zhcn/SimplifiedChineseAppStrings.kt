package dev.evestaticmapplanner.localization.zhcn

import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStrings
import dev.evestaticmapplanner.localization.CommonStrings
import dev.evestaticmapplanner.localization.PreferencesStrings
import dev.evestaticmapplanner.localization.RouteStrings

object SimplifiedChineseAppStrings : AppStrings {
    override val locale = AppLocale.ZH_CN
    override val appTitle = "EVE 静态地图规划器"
    override val common: CommonStrings = SimplifiedChineseCommonStrings
    override val preferences: PreferencesStrings = SimplifiedChinesePreferencesStrings
    override val route: RouteStrings = SimplifiedChineseRouteStrings
}
