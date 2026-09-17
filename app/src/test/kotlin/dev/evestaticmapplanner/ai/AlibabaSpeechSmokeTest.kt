package dev.evestaticmapplanner.ai

import com.sun.jna.Platform
import dev.evestaticmapplanner.ApplicationDirectories
import dev.evestaticmapplanner.embeddedai.ALIBABA_SPEECH_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechClient
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.DEFAULT_ALIBABA_STT_MODEL
import dev.evestaticmapplanner.embeddedai.DEFAULT_ALIBABA_TTS_MODEL
import dev.evestaticmapplanner.embeddedai.DEFAULT_ALIBABA_VOICE
import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class AlibabaSpeechSmokeTest {
    @Test
    fun `real Alibaba speech performs at most one STT and one TTS request`() {
        val credential = smokeCredential()
        assumeTrue(credential != null, "No DPAPI alibaba-speech or DASHSCOPE_API_KEY credential; smoke test skipped.")
        credential!!.use { secret ->
            val region = runCatching {
                AlibabaSpeechRegion.valueOf(System.getenv("EVE_ALIBABA_SPEECH_REGION").orEmpty())
            }.getOrDefault(AlibabaSpeechRegion.CHINA_BEIJING)
            val localFixture = runBlocking {
                WindowsSpeechSynthesizer().synthesize(RECOGNITION_TEXT, voice = null, rate = 0, volume = 100)
            }
            val client = AlibabaSpeechClient()

            val transcript = runBlocking {
                client.transcribe(
                    RecordedAudio(localFixture),
                    DEFAULT_ALIBABA_STT_MODEL,
                    region,
                    Duration.ofSeconds(90),
                    secret,
                )
            }
            assertTrue(transcript.text.isNotBlank())

            val synthesized = runBlocking {
                client.synthesize(
                    SYNTHESIS_TEXT,
                    DEFAULT_ALIBABA_TTS_MODEL,
                    DEFAULT_ALIBABA_VOICE,
                    region,
                    workspaceId = null,
                    timeout = Duration.ofSeconds(90),
                    secret = secret,
                )
            }
            assertEquals("RIFF", synthesized.wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", synthesized.wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))

            val finished = CountDownLatch(1)
            JavaSoundVoiceAudioPlayer().use { player ->
                player.play(synthesized.wav, finished::countDown)
                assertTrue(finished.await(30, TimeUnit.SECONDS), "Alibaba TTS playback did not finish.")
            }
        }
    }

    private fun smokeCredential(): SecretValue? {
        val secure = if (Platform.isWindows()) {
            runCatching {
                WindowsDpapiAiCredentialStore(ApplicationDirectories.root()).load(ALIBABA_SPEECH_CREDENTIAL_REF)
            }.getOrNull()
        } else {
            null
        }
        if (secure != null) return secure
        return System.getenv("DASHSCOPE_API_KEY")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(SecretValue::from)
    }

    private companion object {
        const val RECOGNITION_TEXT = "你好，这是语音识别测试。"
        const val SYNTHESIS_TEXT = "你好，这是语音合成测试。"
    }
}
