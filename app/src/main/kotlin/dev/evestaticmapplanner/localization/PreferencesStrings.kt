package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.VoiceErrorCode
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider

interface PreferencesStrings {
    val title: String
    val language: String
    val english: String
    val simplifiedChinese: String

    fun text(id: PreferencesText, vararg arguments: Any): String

    fun languageName(locale: AppLocale): String = when (locale) {
        AppLocale.EN_US -> english
        AppLocale.ZH_CN -> simplifiedChinese
    }

    fun voiceInputProvider(provider: VoiceInputProvider): String
    fun voiceOutputProvider(provider: VoiceOutputProvider): String
    fun speechRegion(region: AlibabaSpeechRegion): String
    fun credentialStatus(providerName: String, environmentName: String, source: AiCredentialSource?): String
    fun message(id: PreferencesMessage, argument: String? = null, technicalDetail: String? = null): String
    fun voiceFailure(code: VoiceErrorCode?, technicalDetail: String? = null): String
}
