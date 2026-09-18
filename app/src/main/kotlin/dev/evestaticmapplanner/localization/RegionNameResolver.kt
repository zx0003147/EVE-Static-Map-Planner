package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.core.model.Region

object RegionNameResolver {
    fun resolve(region: Region, locale: AppLocale): String = resolve(region.nameEn, region.nameZh, locale)

    fun resolve(nameEn: String, nameZh: String?, locale: AppLocale): String = when (locale) {
        AppLocale.EN_US -> nameEn
        AppLocale.ZH_CN -> nameZh?.trim()?.takeIf(String::isNotEmpty) ?: nameEn
    }
}
