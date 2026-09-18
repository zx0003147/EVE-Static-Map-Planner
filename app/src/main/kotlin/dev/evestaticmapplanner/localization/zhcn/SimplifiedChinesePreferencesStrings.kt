package dev.evestaticmapplanner.localization.zhcn

import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.VoiceErrorCode
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.localization.PreferencesMessage
import dev.evestaticmapplanner.localization.PreferencesStrings
import dev.evestaticmapplanner.localization.PreferencesText

internal object SimplifiedChinesePreferencesStrings : PreferencesStrings {
    override val title = "设置"
    override val language = "语言"
    override val english = "English"
    override val simplifiedChinese = "简体中文"

    override fun text(id: PreferencesText, vararg arguments: Any): String = chinesePreferenceText(id, arguments)

    override fun voiceInputProvider(provider: VoiceInputProvider): String = when (provider) {
        VoiceInputProvider.OFF -> "关闭"
        VoiceInputProvider.LOCAL -> "本地"
        VoiceInputProvider.OPENAI -> "OpenAI"
        VoiceInputProvider.ALIBABA -> "Alibaba Cloud"
    }

    override fun voiceOutputProvider(provider: VoiceOutputProvider): String = when (provider) {
        VoiceOutputProvider.OFF -> "关闭"
        VoiceOutputProvider.LOCAL -> "本地"
        VoiceOutputProvider.OPENAI -> "OpenAI"
        VoiceOutputProvider.ALIBABA -> "Alibaba Cloud"
    }

    override fun speechRegion(region: AlibabaSpeechRegion): String = when (region) {
        AlibabaSpeechRegion.CHINA_BEIJING -> "中国（北京）"
        AlibabaSpeechRegion.SINGAPORE -> "新加坡"
    }

    override fun credentialStatus(providerName: String, environmentName: String, source: AiCredentialSource?): String =
        when (source) {
            AiCredentialSource.SECURE_STORAGE -> "$providerName API Key：已安全保存"
            AiCredentialSource.SESSION_ONLY -> "$providerName API Key：仅本次会话"
            AiCredentialSource.ENVIRONMENT -> "凭据：$environmentName"
            null -> "$providerName API Key：未配置"
        }

    override fun message(id: PreferencesMessage, argument: String?, technicalDetail: String?): String {
        val summary = when (id) {
            PreferencesMessage.AI_API_KEY_NOT_CONFIGURED -> "尚未配置 AI API Key。"
            PreferencesMessage.AI_SETTINGS_SAVED -> "设置已保存。更改提供商或模型会启动新的 AI 会话。"
            PreferencesMessage.AI_SETTINGS_SAVED_SESSION_ONLY -> "设置已保存。安全存储不可用，Key 不会保存，仅在本次会话中可用。"
            PreferencesMessage.AI_SETTINGS_UPDATE_FAILED -> "无法更新 AI 设置。"
            PreferencesMessage.AI_API_KEY_DELETED -> "已删除保存的 API Key。"
            PreferencesMessage.WEB_SEARCH_NOT_CONFIGURED -> "尚未配置网页搜索。"
            PreferencesMessage.WEB_SEARCH_TEST_FAILED -> "无法测试网页搜索设置。"
            PreferencesMessage.WEB_SEARCH_SETTINGS_SAVED -> "网页搜索设置已保存。"
            PreferencesMessage.WEB_SEARCH_SETTINGS_SAVED_SESSION_ONLY -> "设置已保存。安全存储不可用，Key 仅在本次会话中可用。"
            PreferencesMessage.WEB_SEARCH_SETTINGS_UPDATE_FAILED -> "无法更新网页搜索设置。"
            PreferencesMessage.WEB_SEARCH_API_KEY_DELETED -> "已删除保存的 Brave Search API Key。"
            PreferencesMessage.WEB_SEARCH_API_KEY_DELETE_FAILED -> "无法删除 Brave Search API Key。"
            PreferencesMessage.VOICE_SETTINGS_SAVED -> "语音输入/输出设置已保存。"
            PreferencesMessage.VOICE_SETTINGS_SAVED_SESSION_ONLY -> "语音设置已保存。安全存储不可用，Key 仅在本次会话中可用。"
            PreferencesMessage.VOICE_SETTINGS_UPDATE_FAILED -> "无法更新语音输入/输出设置。"
            PreferencesMessage.VOICE_API_KEY_DELETED -> "已删除保存的 ${argument.orEmpty()} API Key。"
            PreferencesMessage.VOICE_API_KEY_DELETE_FAILED -> "无法删除 ${argument.orEmpty()} API Key。"
            PreferencesMessage.TESTING_RECOGNITION -> "正在测试语音识别……"
            PreferencesMessage.RECOGNITION_SUCCEEDED -> "识别成功：${argument.orEmpty()}"
            PreferencesMessage.RECOGNITION_TEST_FAILED -> "语音识别测试失败。"
            PreferencesMessage.TESTING_VOICE -> "正在测试语音合成……"
            PreferencesMessage.VOICE_TEST_SUCCEEDED -> "测试语音已成功播放。"
            PreferencesMessage.VOICE_TEST_FAILED -> "语音合成测试失败。"
            PreferencesMessage.DOWNLOADING_SPEECH_PACK -> "正在下载语音包……"
            PreferencesMessage.SPEECH_PACK_INSTALLED -> "语音包已安装。"
            PreferencesMessage.SPEECH_PACK_INSTALL_FAILED -> "语音包下载或验证失败。"
            PreferencesMessage.SPEECH_PACK_REMOVED -> "语音包已移除。"
            PreferencesMessage.SPEECH_PACK_REMOVE_FAILED -> "无法移除语音包。"
        }
        return technicalDetail?.takeIf(String::isNotBlank)?.let { "$summary\n$it" } ?: summary
    }

    override fun voiceFailure(code: VoiceErrorCode?, technicalDetail: String?): String {
        val summary = when (code) {
            VoiceErrorCode.NO_VOICE_CREDENTIAL -> "尚未配置语音提供商 API Key。"
            VoiceErrorCode.INVALID_VOICE_CREDENTIAL -> "语音提供商身份验证失败。"
            VoiceErrorCode.MICROPHONE_UNAVAILABLE -> "麦克风不可用。"
            VoiceErrorCode.RECORDING_FAILED -> "录音失败。"
            VoiceErrorCode.TRANSCRIPTION_FAILED -> "无法识别这段录音。"
            VoiceErrorCode.SYNTHESIS_FAILED -> "无法合成助手回复的语音。"
            VoiceErrorCode.VOICE_NETWORK_ERROR -> "无法连接语音提供商。"
            VoiceErrorCode.VOICE_RATE_LIMITED -> "已达到语音提供商的速率限制。"
            VoiceErrorCode.VOICE_TIMEOUT -> "语音提供商响应超时。"
            VoiceErrorCode.VOICE_MODEL_NOT_FOUND -> "找不到所选语音模型。"
            VoiceErrorCode.VOICE_MODEL_UNAVAILABLE -> "所选语音模型或音色不可用。"
            VoiceErrorCode.VOICE_PROVIDER_ERROR -> "语音提供商返回错误。"
            VoiceErrorCode.AUDIO_DOWNLOAD_FAILED -> "无法下载合成的音频。"
            VoiceErrorCode.AUDIO_PLAYBACK_FAILED -> "音频播放失败。"
            null -> "语音测试失败。"
        }
        return technicalDetail?.takeIf(String::isNotBlank)?.let { "$summary\n$it" } ?: summary
    }
}
