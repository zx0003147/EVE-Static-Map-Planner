package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.localization.en.EnglishAppStrings
import dev.evestaticmapplanner.localization.zhcn.SimplifiedChineseAppStrings

interface AppStrings {
    val locale: AppLocale
    val appTitle: String
    val common: CommonStrings
    val mainShell: MainShellStrings
    val map: MapStrings
    val search: SearchStrings
    val systemInfo: SystemInfoStrings
    val preferences: PreferencesStrings
    val route: RouteStrings
}

object AppStringsCatalog {
    fun forLocale(locale: AppLocale): AppStrings = when (locale) {
        AppLocale.EN_US -> EnglishAppStrings
        AppLocale.ZH_CN -> SimplifiedChineseAppStrings
    }
}
