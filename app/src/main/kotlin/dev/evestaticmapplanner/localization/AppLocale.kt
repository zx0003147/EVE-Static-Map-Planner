package dev.evestaticmapplanner.localization

import java.util.Locale

enum class AppLocale(val tag: String) {
    EN_US("en-US"),
    ZH_CN("zh-CN"),
    ;

    companion object {
        fun fromTagOrNull(tag: String?): AppLocale? = entries.firstOrNull { it.tag == tag }
    }
}

fun interface SystemLocaleSource {
    fun languageTag(): String
}

object JvmSystemLocaleSource : SystemLocaleSource {
    override fun languageTag(): String = Locale.getDefault().toLanguageTag()
}

class AppLocaleDetector(
    private val systemLocaleSource: SystemLocaleSource = JvmSystemLocaleSource,
) {
    fun detect(): AppLocale {
        val language = Locale.forLanguageTag(systemLocaleSource.languageTag()).language
        return if (language.equals("zh", ignoreCase = true)) AppLocale.ZH_CN else AppLocale.EN_US
    }
}
