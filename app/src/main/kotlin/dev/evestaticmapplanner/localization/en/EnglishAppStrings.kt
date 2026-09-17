package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStrings
import dev.evestaticmapplanner.localization.CommonStrings
import dev.evestaticmapplanner.localization.PreferencesStrings
import dev.evestaticmapplanner.localization.RouteStrings

object EnglishAppStrings : AppStrings {
    override val locale = AppLocale.EN_US
    override val appTitle = "EVE Static Map Planner"
    override val common: CommonStrings = EnglishCommonStrings
    override val preferences: PreferencesStrings = EnglishPreferencesStrings
    override val route: RouteStrings = EnglishRouteStrings
}
