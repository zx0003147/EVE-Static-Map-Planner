package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStrings
import dev.evestaticmapplanner.localization.CommonStrings
import dev.evestaticmapplanner.localization.MainShellStrings
import dev.evestaticmapplanner.localization.MapStrings
import dev.evestaticmapplanner.localization.PreferencesStrings
import dev.evestaticmapplanner.localization.RouteStrings
import dev.evestaticmapplanner.localization.SearchStrings
import dev.evestaticmapplanner.localization.SystemInfoStrings

object EnglishAppStrings : AppStrings {
    override val locale = AppLocale.EN_US
    override val appTitle = "EVE Static Map Planner"
    override val common: CommonStrings = EnglishCommonStrings
    override val mainShell: MainShellStrings = EnglishMainShellStrings
    override val map: MapStrings = EnglishMapStrings
    override val search: SearchStrings = EnglishSearchStrings
    override val systemInfo: SystemInfoStrings = EnglishSystemInfoStrings
    override val preferences: PreferencesStrings = EnglishPreferencesStrings
    override val route: RouteStrings = EnglishRouteStrings
}
