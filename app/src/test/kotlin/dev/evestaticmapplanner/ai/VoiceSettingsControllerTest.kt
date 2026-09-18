package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.ALIBABA_SPEECH_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.InMemoryAiCredentialStore
import dev.evestaticmapplanner.embeddedai.MapSpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.OPENAI_VOICE_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderCapability
import dev.evestaticmapplanner.embeddedai.SpeechRecognitionConfig
import dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig
import dev.evestaticmapplanner.embeddedai.SpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.SpeechTranscript
import dev.evestaticmapplanner.embeddedai.SynthesizedAudio
import dev.evestaticmapplanner.embeddedai.TextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.shared.auth.SecretValue
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSettingsControllerTest {
    @Test
    fun `save persists config and independent secure voice credentials`() = runTest {
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val resolver = AiCredentialResolver(secure, session) { null }
        val persisted = mutableListOf<VoiceConfig>()
        val speechRoot = Files.createTempDirectory("voice-settings")
        val controller = VoiceSettingsController(
            secureStore = secure,
            sessionStore = session,
            credentialResolver = resolver,
            speechPackManager = SpeechPackManager(speechRoot = speechRoot),
            localSynthesizer = object : SpeechSynthesizer {
                override suspend fun synthesize(text: String, voice: String?, rate: Int, volume: Int) = ByteArray(0)
                override suspend fun voices() = listOf("Fixture Voice")
            },
            providerFactory = MapSpeechProviderFactory(emptyMap(), emptyMap()),
            audioPlayer = object : VoiceAudioPlayer {
                override fun play(wav: ByteArray, onFinished: () -> Unit) = onFinished()
                override fun stop() = Unit
                override fun close() = Unit
            },
            persistConfig = { persisted += it; Result.success(Unit) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val config = VoiceConfig(
            inputProvider = VoiceInputProvider.OPENAI,
            outputProvider = VoiceOutputProvider.LOCAL,
        )
        try {
            controller.save(config, SecretValue.from("voice-key"), SecretValue.from("alibaba-key"))
            advanceUntilIdle()

            assertEquals(listOf(config), persisted)
            assertTrue(secure.contains(OPENAI_VOICE_CREDENTIAL_REF))
            assertTrue(secure.contains(ALIBABA_SPEECH_CREDENTIAL_REF))
            assertEquals(AiCredentialSource.SECURE_STORAGE, controller.state.value.openAiCredentialSource)
            assertEquals(AiCredentialSource.SECURE_STORAGE, controller.state.value.alibabaCredentialSource)
            assertEquals("Voice I/O settings saved.", controller.state.value.message?.resolve(ENGLISH))
        } finally {
            controller.close()
            secure.close()
            session.close()
            speechRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `recognition and voice tests use fixed fixtures and the independently selected providers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secure = InMemoryAiCredentialStore()
        val session = InMemoryAiCredentialStore()
        val speechRoot = Files.createTempDirectory("voice-settings-tests")
        val recognitionTexts = mutableListOf<String>()
        val synthesisTexts = mutableListOf<String>()
        var playCount = 0
        val localFixtureProvider = object : TextToSpeechProvider {
            override val capability = SpeechProviderCapability(false, true, false, true, false)
            override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
                recognitionTexts += text
                return SynthesizedAudio(fakeWav())
            }
        }
        val alibabaRecognition = object : SpeechToTextProvider {
            override val capability = SpeechProviderCapability(true, false, true, false, true)
            override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig): SpeechTranscript {
                assertTrue(audio.wav.contentEquals(fakeWav()))
                return SpeechTranscript("你好，这是语音识别测试。")
            }
        }
        val alibabaVoice = object : TextToSpeechProvider {
            override val capability = SpeechProviderCapability(false, true, true, true, true)
            override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
                synthesisTexts += text
                return SynthesizedAudio(fakeWav())
            }
        }
        val controller = VoiceSettingsController(
            secureStore = secure,
            sessionStore = session,
            credentialResolver = AiCredentialResolver(secure, session) { null },
            speechPackManager = SpeechPackManager(speechRoot = speechRoot),
            localSynthesizer = object : SpeechSynthesizer {
                override suspend fun synthesize(text: String, voice: String?, rate: Int, volume: Int) = fakeWav()
                override suspend fun voices() = emptyList<String>()
            },
            providerFactory = MapSpeechProviderFactory(
                speechToTextProviders = mapOf(VoiceInputProvider.ALIBABA to alibabaRecognition),
                textToSpeechProviders = mapOf(
                    VoiceOutputProvider.LOCAL to localFixtureProvider,
                    VoiceOutputProvider.ALIBABA to alibabaVoice,
                ),
            ),
            audioPlayer = object : VoiceAudioPlayer {
                override fun play(wav: ByteArray, onFinished: () -> Unit) { playCount++; onFinished() }
                override fun stop() = Unit
                override fun close() = Unit
            },
            persistConfig = { Result.success(Unit) },
            dispatcher = dispatcher,
        )
        val config = VoiceConfig(
            inputProvider = VoiceInputProvider.ALIBABA,
            outputProvider = VoiceOutputProvider.ALIBABA,
        )
        try {
            controller.testRecognition(config)
            advanceUntilIdle()
            assertEquals(listOf("你好，这是语音识别测试。"), recognitionTexts)
            assertTrue(controller.state.value.message?.resolve(ENGLISH).orEmpty().startsWith("Recognition succeeded:"))

            controller.testVoice(config)
            advanceUntilIdle()
            assertEquals(listOf("你好，这是语音合成测试。"), synthesisTexts)
            assertEquals(1, playCount)
            assertEquals("Voice test played successfully.", controller.state.value.message?.resolve(ENGLISH))
        } finally {
            controller.close()
            secure.close()
            session.close()
            speechRoot.toFile().deleteRecursively()
        }
    }

    private fun fakeWav(): ByteArray = ByteArray(48).apply {
        "RIFF".toByteArray().copyInto(this, 0)
        "WAVE".toByteArray().copyInto(this, 8)
    }
}

private val ENGLISH = AppStringsCatalog.forLocale(AppLocale.EN_US)
