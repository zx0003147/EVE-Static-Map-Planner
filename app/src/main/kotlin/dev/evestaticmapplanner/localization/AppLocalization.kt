package dev.evestaticmapplanner.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppLocalization(
    val locale: AppLocale,
) {
    val strings: AppStrings = AppStringsCatalog.forLocale(locale)
}

class AppLocalizationState(initialLocale: AppLocale) {
    private val mutableState = MutableStateFlow(AppLocalization(initialLocale))
    val state: StateFlow<AppLocalization> = mutableState.asStateFlow()

    fun updateLocale(locale: AppLocale) {
        if (mutableState.value.locale != locale) mutableState.value = AppLocalization(locale)
    }
}

val LocalAppLocale = staticCompositionLocalOf { AppLocale.EN_US }
val LocalAppStrings = staticCompositionLocalOf<AppStrings> { AppStringsCatalog.forLocale(AppLocale.EN_US) }

@Composable
fun ProvideAppLocalization(localization: AppLocalization, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppLocale provides localization.locale,
        LocalAppStrings provides localization.strings,
        content = content,
    )
}
