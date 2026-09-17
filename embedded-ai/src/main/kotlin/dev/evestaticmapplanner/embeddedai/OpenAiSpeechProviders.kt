package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.shared.auth.SecretValue

class OpenAiSpeechToTextProvider(
    private val credentialResolver: AiCredentialResolver,
    private val client: CloudVoiceClient,
) : SpeechToTextProvider {
    override val capability = SpeechProviderCapability(
        supportsStt = true,
        supportsTts = false,
        requiresApiKey = true,
        supportsVoiceSelection = false,
        supportsModelSelection = true,
    )

    override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig): SpeechTranscript {
        val model = config.model?.takeIf(String::isNotBlank)
            ?: throw VoiceException(VoiceErrorCode.VOICE_MODEL_NOT_FOUND, "No OpenAI transcription model is configured.")
        val credential = credentialResolver.resolve(OPENAI_VOICE_CREDENTIAL_REF, OPENAI_VOICE_ENVIRONMENT_VARIABLE)
            ?: throw VoiceException(VoiceErrorCode.NO_VOICE_CREDENTIAL, "OpenAI Voice is not configured.")
        val transcript = credential.use { resolved ->
            resolved.useSecret(SecretValue::copy).use { secret ->
                client.transcribe(audio.wav, model, secret, config.timeout)
            }
        }
        return SpeechTranscript(transcript.trim())
    }
}

class OpenAiTextToSpeechProvider(
    private val credentialResolver: AiCredentialResolver,
    private val client: CloudVoiceClient,
) : TextToSpeechProvider {
    override val capability = SpeechProviderCapability(
        supportsStt = false,
        supportsTts = true,
        requiresApiKey = true,
        supportsVoiceSelection = true,
        supportsModelSelection = true,
    )

    override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
        val model = config.model?.takeIf(String::isNotBlank)
            ?: throw VoiceException(VoiceErrorCode.VOICE_MODEL_NOT_FOUND, "No OpenAI speech model is configured.")
        val voice = config.voice?.takeIf(String::isNotBlank)
            ?: throw VoiceException(VoiceErrorCode.VOICE_MODEL_UNAVAILABLE, "No OpenAI voice is configured.")
        val credential = credentialResolver.resolve(OPENAI_VOICE_CREDENTIAL_REF, OPENAI_VOICE_ENVIRONMENT_VARIABLE)
            ?: throw VoiceException(VoiceErrorCode.NO_VOICE_CREDENTIAL, "OpenAI Voice is not configured.")
        val wav = credential.use { resolved ->
            resolved.useSecret(SecretValue::copy).use { secret ->
                client.synthesize(text, model, voice, secret, config.timeout)
            }
        }
        return SynthesizedAudio(wav)
    }
}
