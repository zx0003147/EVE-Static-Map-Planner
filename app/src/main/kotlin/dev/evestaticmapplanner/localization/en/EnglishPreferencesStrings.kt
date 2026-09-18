package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.VoiceErrorCode
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.localization.PreferencesMessage
import dev.evestaticmapplanner.localization.PreferencesStrings
import dev.evestaticmapplanner.localization.PreferencesText

internal object EnglishPreferencesStrings : PreferencesStrings {
    override val title = "Preferences"
    override val language = "Language"
    override val english = "English"
    override val simplifiedChinese = "简体中文"

    override fun text(id: PreferencesText, vararg arguments: Any): String = englishPreferenceText(id, arguments)

    override fun voiceInputProvider(provider: VoiceInputProvider): String = when (provider) {
        VoiceInputProvider.OFF -> "Off"
        VoiceInputProvider.LOCAL -> "Local"
        VoiceInputProvider.OPENAI -> "OpenAI"
        VoiceInputProvider.ALIBABA -> "Alibaba Cloud"
    }

    override fun voiceOutputProvider(provider: VoiceOutputProvider): String = when (provider) {
        VoiceOutputProvider.OFF -> "Off"
        VoiceOutputProvider.LOCAL -> "Local"
        VoiceOutputProvider.OPENAI -> "OpenAI"
        VoiceOutputProvider.ALIBABA -> "Alibaba Cloud"
    }

    override fun speechRegion(region: AlibabaSpeechRegion): String = when (region) {
        AlibabaSpeechRegion.CHINA_BEIJING -> "China (Beijing)"
        AlibabaSpeechRegion.SINGAPORE -> "Singapore"
    }

    override fun credentialStatus(providerName: String, environmentName: String, source: AiCredentialSource?): String =
        when (source) {
            AiCredentialSource.SECURE_STORAGE -> "$providerName API Key: Saved securely"
            AiCredentialSource.SESSION_ONLY -> "$providerName API Key: This session only"
            AiCredentialSource.ENVIRONMENT -> "Credential: $environmentName"
            null -> "$providerName API Key: Not configured"
        }

    override fun message(id: PreferencesMessage, argument: String?, technicalDetail: String?): String {
        val summary = when (id) {
            PreferencesMessage.AI_API_KEY_NOT_CONFIGURED -> "AI API Key is not configured."
            PreferencesMessage.AI_SETTINGS_SAVED -> "Settings saved. Changing provider or model starts a new AI session."
            PreferencesMessage.AI_SETTINGS_SAVED_SESSION_ONLY -> "Settings saved. Secure storage is unavailable; Key will not be saved and is available for this session only."
            PreferencesMessage.AI_SETTINGS_UPDATE_FAILED -> "AI settings could not be updated."
            PreferencesMessage.AI_API_KEY_DELETED -> "Saved API Key deleted."
            PreferencesMessage.WEB_SEARCH_NOT_CONFIGURED -> "Web search is not configured."
            PreferencesMessage.WEB_SEARCH_TEST_FAILED -> "Web Search settings could not be tested."
            PreferencesMessage.WEB_SEARCH_SETTINGS_SAVED -> "Web Search settings saved."
            PreferencesMessage.WEB_SEARCH_SETTINGS_SAVED_SESSION_ONLY -> "Settings saved. Secure storage is unavailable; Key is available for this session only."
            PreferencesMessage.WEB_SEARCH_SETTINGS_UPDATE_FAILED -> "Web Search settings could not be updated."
            PreferencesMessage.WEB_SEARCH_API_KEY_DELETED -> "Saved Brave Search API Key deleted."
            PreferencesMessage.WEB_SEARCH_API_KEY_DELETE_FAILED -> "The Brave Search API Key could not be deleted."
            PreferencesMessage.VOICE_SETTINGS_SAVED -> "Voice I/O settings saved."
            PreferencesMessage.VOICE_SETTINGS_SAVED_SESSION_ONLY -> "Voice settings saved. Secure storage is unavailable; the Key is available for this session only."
            PreferencesMessage.VOICE_SETTINGS_UPDATE_FAILED -> "Voice I/O settings could not be updated."
            PreferencesMessage.VOICE_API_KEY_DELETED -> "Saved ${argument.orEmpty()} API Key deleted."
            PreferencesMessage.VOICE_API_KEY_DELETE_FAILED -> "The ${argument.orEmpty()} API Key could not be deleted."
            PreferencesMessage.TESTING_RECOGNITION -> "Testing speech recognition…"
            PreferencesMessage.RECOGNITION_SUCCEEDED -> "Recognition succeeded: ${argument.orEmpty()}"
            PreferencesMessage.RECOGNITION_TEST_FAILED -> "Speech recognition test failed."
            PreferencesMessage.TESTING_VOICE -> "Testing speech synthesis…"
            PreferencesMessage.VOICE_TEST_SUCCEEDED -> "Voice test played successfully."
            PreferencesMessage.VOICE_TEST_FAILED -> "Speech synthesis test failed."
            PreferencesMessage.DOWNLOADING_SPEECH_PACK -> "Downloading Speech Pack…"
            PreferencesMessage.SPEECH_PACK_INSTALLED -> "Speech Pack installed."
            PreferencesMessage.SPEECH_PACK_INSTALL_FAILED -> "Speech Pack download or verification failed."
            PreferencesMessage.SPEECH_PACK_REMOVED -> "Speech Pack removed."
            PreferencesMessage.SPEECH_PACK_REMOVE_FAILED -> "Speech Pack could not be removed."
        }
        return technicalDetail?.takeIf(String::isNotBlank)?.let { "$summary\n$it" } ?: summary
    }

    override fun voiceFailure(code: VoiceErrorCode?, technicalDetail: String?): String {
        val fallback = when (code) {
            VoiceErrorCode.NO_VOICE_CREDENTIAL -> "The speech provider API Key is not configured."
            VoiceErrorCode.INVALID_VOICE_CREDENTIAL -> "Speech provider authentication failed."
            VoiceErrorCode.MICROPHONE_UNAVAILABLE -> "The microphone is unavailable."
            VoiceErrorCode.RECORDING_FAILED -> "Recording failed."
            VoiceErrorCode.TRANSCRIPTION_FAILED -> "The recording could not be transcribed."
            VoiceErrorCode.SYNTHESIS_FAILED -> "The assistant reply could not be synthesized."
            VoiceErrorCode.VOICE_NETWORK_ERROR -> "The speech provider could not be reached."
            VoiceErrorCode.VOICE_RATE_LIMITED -> "The speech provider rate limit was reached."
            VoiceErrorCode.VOICE_TIMEOUT -> "The speech provider timed out."
            VoiceErrorCode.VOICE_MODEL_NOT_FOUND -> "The selected speech model was not found."
            VoiceErrorCode.VOICE_MODEL_UNAVAILABLE -> "The selected speech model or voice is unavailable."
            VoiceErrorCode.VOICE_PROVIDER_ERROR -> "The speech provider returned an error."
            VoiceErrorCode.AUDIO_DOWNLOAD_FAILED -> "The synthesized audio could not be downloaded."
            VoiceErrorCode.AUDIO_PLAYBACK_FAILED -> "Audio playback failed."
            null -> "The speech test failed."
        }
        return technicalDetail?.takeIf(String::isNotBlank) ?: fallback
    }
}
