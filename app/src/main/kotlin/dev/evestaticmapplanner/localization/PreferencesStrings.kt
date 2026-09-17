package dev.evestaticmapplanner.localization

interface PreferencesStrings {
    val title: String
    val language: String
    val english: String
    val simplifiedChinese: String

    fun languageName(locale: AppLocale): String = when (locale) {
        AppLocale.EN_US -> english
        AppLocale.ZH_CN -> simplifiedChinese
    }
}
