package dev.evestaticmapplanner.localization.zhcn

import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStrings
import dev.evestaticmapplanner.localization.CommonStrings
import dev.evestaticmapplanner.localization.MainShellStrings
import dev.evestaticmapplanner.localization.MapStrings
import dev.evestaticmapplanner.localization.PreferencesStrings
import dev.evestaticmapplanner.localization.RouteStrings
import dev.evestaticmapplanner.localization.SearchStrings
import dev.evestaticmapplanner.localization.SystemInfoStrings

object SimplifiedChineseAppStrings : AppStrings {
    override val locale = AppLocale.ZH_CN
    override val appTitle = "EVE 静态地图规划器"
    override val common: CommonStrings = SimplifiedChineseCommonStrings
    override val mainShell: MainShellStrings = SimplifiedChineseMainShellStrings
    override val map: MapStrings = SimplifiedChineseMapStrings
    override val search: SearchStrings = SimplifiedChineseSearchStrings
    override val systemInfo: SystemInfoStrings = SimplifiedChineseSystemInfoStrings
    override val preferences: PreferencesStrings = SimplifiedChinesePreferencesStrings
    override val route: RouteStrings = SimplifiedChineseRouteStrings
}
