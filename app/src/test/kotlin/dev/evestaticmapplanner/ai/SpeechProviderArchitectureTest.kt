package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.ALIBABA_SPEECH_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechClient
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.AlibabaTextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.InMemoryAiCredentialStore
import dev.evestaticmapplanner.embeddedai.MapSpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.OPENAI_VOICE_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.OpenAiSpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.OpenAiTextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.OpenAiVoiceClient
import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.SpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.TextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.VoiceErrorCode
import dev.evestaticmapplanner.embeddedai.VoiceException
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpeechProviderArchitectureTest {
    @Test
    fun `all desktop and cloud adapters implement the provider contracts`() {
        val root = Files.createTempDirectory("speech-provider-contract")
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        try {
            val resolver = AiCredentialResolver(secure, session) { null }
            assertIs<SpeechToTextProvider>(WhisperCppTranscriber(SpeechPackManager(speechRoot = root)))
            assertIs<SpeechToTextProvider>(OpenAiSpeechToTextProvider(resolver, OpenAiVoiceClient()))
            assertIs<SpeechToTextProvider>(AlibabaSpeechToTextProvider(resolver, AlibabaSpeechClient()))
            assertIs<TextToSpeechProvider>(WindowsSpeechSynthesizer())
            assertIs<TextToSpeechProvider>(OpenAiTextToSpeechProvider(resolver, OpenAiVoiceClient()))
            assertIs<TextToSpeechProvider>(AlibabaTextToSpeechProvider(resolver, AlibabaSpeechClient()))
        } finally {
            secure.close()
            session.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `voice controller depends on provider factory rather than vendor clients`() {
        val dependencyTypes = VoiceController::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asList() }
            .map(Class<*>::getName)

        assertTrue(dependencyTypes.contains(SpeechProviderFactory::class.java.name))
        assertFalse(dependencyTypes.any { it.endsWith("OpenAiVoiceClient") || it.endsWith("AlibabaSpeechClient") })
        assertFalse(dependencyTypes.any { it.endsWith("AiCredentialResolver") })
    }

    @Test
    fun `openai and alibaba credentials are isolated`() {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        try {
            SecretValue.from("openai-only").use { secure.save(OPENAI_VOICE_CREDENTIAL_REF, it) }
            val resolver = AiCredentialResolver(secure, session) { null }
            val alibaba = AlibabaSpeechToTextProvider(resolver, AlibabaSpeechClient())
            val missingAlibaba = assertFailsWith<VoiceException> {
                runBlocking {
                    alibaba.transcribe(
                        RecordedAudio(fakeWav()),
                        dev.evestaticmapplanner.embeddedai.SpeechRecognitionConfig(
                            model = "qwen3-asr-flash",
                            region = dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion.CHINA_BEIJING,
                        ),
                    )
                }
            }
            assertEquals(VoiceErrorCode.NO_VOICE_CREDENTIAL, missingAlibaba.code)

            secure.delete(OPENAI_VOICE_CREDENTIAL_REF)
            SecretValue.from("alibaba-only").use { secure.save(ALIBABA_SPEECH_CREDENTIAL_REF, it) }
            val openAi = OpenAiSpeechToTextProvider(resolver, OpenAiVoiceClient())
            val missingOpenAi = assertFailsWith<VoiceException> {
                runBlocking {
                    openAi.transcribe(
                        RecordedAudio(fakeWav()),
                        dev.evestaticmapplanner.embeddedai.SpeechRecognitionConfig(model = "gpt-4o-mini-transcribe"),
                    )
                }
            }
            assertEquals(VoiceErrorCode.NO_VOICE_CREDENTIAL, missingOpenAi.code)
        } finally {
            secure.close()
            session.close()
        }
    }

    @Test
    fun `factory preserves independent stt and tts provider mixing`() {
        val localStt = object : SpeechToTextProvider {
            override val capability = dev.evestaticmapplanner.embeddedai.SpeechProviderCapability(true, false, false, false, false)
            override suspend fun transcribe(
                audio: RecordedAudio,
                config: dev.evestaticmapplanner.embeddedai.SpeechRecognitionConfig,
            ) = dev.evestaticmapplanner.embeddedai.SpeechTranscript("fixture")
        }
        val localTts = object : TextToSpeechProvider {
            override val capability = dev.evestaticmapplanner.embeddedai.SpeechProviderCapability(false, true, false, true, false)
            override suspend fun synthesize(
                text: String,
                config: dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig,
            ) = dev.evestaticmapplanner.embeddedai.SynthesizedAudio(fakeWav())
        }
        val factory = MapSpeechProviderFactory(
            speechToTextProviders = mapOf(
                VoiceInputProvider.LOCAL to localStt,
                VoiceInputProvider.OPENAI to localStt,
                VoiceInputProvider.ALIBABA to localStt,
            ),
            textToSpeechProviders = mapOf(
                VoiceOutputProvider.LOCAL to localTts,
                VoiceOutputProvider.OPENAI to localTts,
                VoiceOutputProvider.ALIBABA to localTts,
            ),
        )

        val combinations = listOf(
            VoiceInputProvider.ALIBABA to VoiceOutputProvider.LOCAL,
            VoiceInputProvider.LOCAL to VoiceOutputProvider.ALIBABA,
            VoiceInputProvider.OPENAI to VoiceOutputProvider.ALIBABA,
            VoiceInputProvider.ALIBABA to VoiceOutputProvider.OPENAI,
        )
        combinations.forEach { (input, output) ->
            assertTrue(factory.speechToText(input) === localStt)
            assertTrue(factory.textToSpeech(output) === localTts)
        }
    }

    private fun fakeWav(): ByteArray = ByteArray(48).apply {
        "RIFF".toByteArray().copyInto(this, 0)
        "WAVE".toByteArray().copyInto(this, 8)
    }
}
