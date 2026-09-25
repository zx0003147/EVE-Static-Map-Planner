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
import dev.evestaticmapplanner.localization.StaticDataStrings

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
    override val staticData: StaticDataStrings = EnglishStaticDataStrings
    override val aiAssistant: dev.evestaticmapplanner.localization.AiAssistantStrings = EnglishAiAssistantStrings
    override val marker: dev.evestaticmapplanner.localization.MarkerStrings = EnglishMarkerStrings
    override val sharedMap: dev.evestaticmapplanner.localization.SharedMapStrings = EnglishSharedMapStrings
    override val wormhole: dev.evestaticmapplanner.localization.WormholeStrings = EnglishWormholeStrings
    override val ansiblex: dev.evestaticmapplanner.localization.AnsiblexStrings = EnglishAnsiblexStrings
}
