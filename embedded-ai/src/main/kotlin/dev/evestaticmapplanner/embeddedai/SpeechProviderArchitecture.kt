package dev.evestaticmapplanner.embeddedai

import java.time.Duration
import java.nio.file.Path

data class RecordedAudio(
    val wav: ByteArray,
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val sourcePath: Path? = null,
) {
    init {
        require(wav.isNotEmpty() && wav.size <= MAX_VOICE_AUDIO_BYTES)
        require(sampleRateHz > 0)
        require(channels > 0)
    }
}

data class SpeechTranscript(val text: String) {
    init {
        require(text.isNotBlank())
    }
}

data class SynthesizedAudio(
    val wav: ByteArray,
    val contentType: String = "audio/wav",
) {
    init {
        require(wav.isNotEmpty() && wav.size <= MAX_SYNTHESIZED_AUDIO_BYTES)
    }
}

data class SpeechRecognitionConfig(
    val model: String? = null,
    val region: AlibabaSpeechRegion? = null,
    val timeout: Duration = DEFAULT_VOICE_TIMEOUT,
)

data class SpeechSynthesisConfig(
    val model: String? = null,
    val voice: String? = null,
    val region: AlibabaSpeechRegion? = null,
    val workspaceId: String? = null,
    val timeout: Duration = DEFAULT_VOICE_TIMEOUT,
    val rate: Int = 0,
    val volume: Int = 100,
)

data class SpeechProviderCapability(
    val supportsStt: Boolean,
    val supportsTts: Boolean,
    val requiresApiKey: Boolean,
    val supportsVoiceSelection: Boolean,
    val supportsModelSelection: Boolean,
    val requiresBaseUrl: Boolean = false,
    val requiresWorkspaceId: Boolean = false,
)

interface SpeechToTextProvider {
    val capability: SpeechProviderCapability
    suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig): SpeechTranscript
}

interface TextToSpeechProvider {
    val capability: SpeechProviderCapability
    suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio
}

interface SpeechProviderFactory {
    fun speechToText(provider: VoiceInputProvider): SpeechToTextProvider?
    fun textToSpeech(provider: VoiceOutputProvider): TextToSpeechProvider?
}

class MapSpeechProviderFactory(
    private val speechToTextProviders: Map<VoiceInputProvider, SpeechToTextProvider>,
    private val textToSpeechProviders: Map<VoiceOutputProvider, TextToSpeechProvider>,
) : SpeechProviderFactory {
    override fun speechToText(provider: VoiceInputProvider): SpeechToTextProvider? = speechToTextProviders[provider]
    override fun textToSpeech(provider: VoiceOutputProvider): TextToSpeechProvider? = textToSpeechProviders[provider]
}

fun VoiceConfig.recognitionConfig(): SpeechRecognitionConfig = when (inputProvider) {
    VoiceInputProvider.LOCAL -> SpeechRecognitionConfig()
    VoiceInputProvider.OPENAI -> SpeechRecognitionConfig(
        model = profiles.openAi.sttModel,
        timeout = Duration.ofSeconds(profiles.openAi.timeoutSeconds),
    )
    VoiceInputProvider.ALIBABA -> SpeechRecognitionConfig(
        model = profiles.alibaba.sttModel,
        region = profiles.alibaba.sttRegion,
        timeout = Duration.ofSeconds(profiles.alibaba.sttTimeoutSeconds),
    )
    VoiceInputProvider.OFF -> SpeechRecognitionConfig()
}

fun VoiceConfig.synthesisConfig(): SpeechSynthesisConfig = when (outputProvider) {
    VoiceOutputProvider.LOCAL -> SpeechSynthesisConfig(
        voice = profiles.local.windowsVoice,
        rate = profiles.local.ttsRate,
        volume = profiles.local.ttsVolume,
    )
    VoiceOutputProvider.OPENAI -> SpeechSynthesisConfig(
        model = profiles.openAi.ttsModel,
        voice = profiles.openAi.voice,
        timeout = Duration.ofSeconds(profiles.openAi.timeoutSeconds),
    )
    VoiceOutputProvider.ALIBABA -> SpeechSynthesisConfig(
        model = profiles.alibaba.ttsModel,
        voice = profiles.alibaba.voice,
        region = profiles.alibaba.ttsRegion,
        workspaceId = profiles.alibaba.workspaceId,
        timeout = Duration.ofSeconds(profiles.alibaba.ttsTimeoutSeconds),
    )
    VoiceOutputProvider.OFF -> SpeechSynthesisConfig()
}
