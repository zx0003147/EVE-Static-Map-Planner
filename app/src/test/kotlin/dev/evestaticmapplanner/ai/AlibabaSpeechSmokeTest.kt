package dev.evestaticmapplanner.ai

import com.sun.jna.Platform
import dev.evestaticmapplanner.ApplicationDirectories
import dev.evestaticmapplanner.embeddedai.ALIBABA_SPEECH_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechClient
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.DEFAULT_ALIBABA_STT_MODEL
import dev.evestaticmapplanner.embeddedai.DEFAULT_ALIBABA_TTS_MODEL
import dev.evestaticmapplanner.embeddedai.DEFAULT_ALIBABA_VOICE
import dev.evestaticmapplanner.embeddedai.MapSpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderCapability
import dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig
import dev.evestaticmapplanner.embeddedai.SynthesizedAudio
import dev.evestaticmapplanner.embeddedai.TextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.preferences.PropertiesPreferencesStore
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class AlibabaSpeechSmokeTest {
    @Test
    fun `real Alibaba speech performs at most one STT and one TTS request`() {
        assumeTrue(
            System.getenv(STANDARD_SMOKE_ENVIRONMENT_VARIABLE) == "1",
            "$STANDARD_SMOKE_ENVIRONMENT_VARIABLE=1 is required for the paid Alibaba speech smoke test.",
        )
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
                    workspaceId = null,
                    timeout = Duration.ofSeconds(90),
                    secret = secret,
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

    @Test
    fun `real Alibaba diagnostic captures four isolated chunks and transcribes each returned audio`() {
        assumeTrue(
            System.getenv(DIAGNOSTIC_SMOKE_ENVIRONMENT_VARIABLE) == "1" ||
                System.getProperty(DIAGNOSTIC_SMOKE_SYSTEM_PROPERTY) == "1",
            "$DIAGNOSTIC_SMOKE_ENVIRONMENT_VARIABLE=1 is required for the paid diagnostic smoke test.",
        )
        val applicationRoot = ApplicationDirectories.root()
        val voice = PropertiesPreferencesStore(applicationRoot.resolve("settings.properties")).load().voice
        val profile = voice.profiles.alibaba
        assumeTrue(profile.workspaceId != null, "Alibaba Workspace ID is not configured.")
        val credential = smokeCredential()
        assumeTrue(credential != null, "No Alibaba credential is configured.")
        credential!!.use { secret ->
            val clientDiagnostics = mutableListOf<String>()
            val client = AlibabaSpeechClient(diagnostics = { message ->
                synchronized(clientDiagnostics) { clientDiagnostics += message }
                println("DIAGNOSTIC: $message")
            })
            val provider = object : TextToSpeechProvider {
                override val capability = SpeechProviderCapability(false, true, true, true, true, true)
                override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio =
                    client.synthesize(
                        text = text,
                        model = requireNotNull(config.model),
                        voice = requireNotNull(config.voice),
                        region = requireNotNull(config.region),
                        workspaceId = config.workspaceId,
                        timeout = config.timeout,
                        secret = secret,
                        requestContext = config.requestContext,
                    )
            }
            val capture = TtsDiagnosticCapture(
                root = applicationRoot.resolve("logs").resolve("tts-debug"),
                isolatePlayback = true,
            )
            val messageId = "live-diagnostic-${UUID.randomUUID()}"
            val controller = VoiceController(
                configSource = {
                    voice.copy(
                        outputProvider = VoiceOutputProvider.ALIBABA,
                        readAssistantRepliesAloud = true,
                    )
                },
                providerFactory = MapSpeechProviderFactory(
                    speechToTextProviders = emptyMap(),
                    textToSpeechProviders = mapOf(VoiceOutputProvider.ALIBABA to provider),
                ),
                recorder = object : VoiceRecorder {
                    override val isRecording: Boolean = false
                    override fun start() = error("not used")
                    override fun stop() = error("not used")
                    override fun cancel() = Unit
                    override fun close() = Unit
                },
                audioPlayer = object : VoiceAudioPlayer {
                    override fun play(wav: ByteArray, onFinished: () -> Unit) =
                        error("Diagnostic isolation must not invoke the audio player")
                    override fun stop() = Unit
                    override fun close() = Unit
                },
                ioDispatcher = Dispatchers.IO,
                uiDispatcher = Dispatchers.Unconfined,
                diagnostics = { println(it) },
                ttsDiagnosticCapture = capture,
            )
            try {
                val first = "**诊断甲。第一段只应朗读一次。**"
                val second = "$first\n[诊断乙。第二段包含链接但只保留文字。](https://example.com/tts-proof)"
                val table = "$second\n\n" + """
                    | 名称 | securityStatus |
                    |---|---|
                    | 诊断丙 Atioth | -0.018471 |
                """.trimIndent() + "\n\n"
                val complete = table + """
                    诊断丁。已找到并标注路线：起点 C-J6MT，systemId 30000772；终点 Atioth，systemId 30002489。
                    路线类型为普通路线，途经 L-TOFR。此段随后依次说明：第一，路线已经生成；第二，地图标记已经更新；第三，本段到此结束。
                    第四，旗舰跳跃规划没有启用；第五，本次结果只使用星门连接；第六，请在地图上检查蓝色路线；第七，以上各项均为不同内容。
                    第八，诊断文本不会返回前文；第九，这是最后一条独立说明；第十，长段诊断现在唯一结束。
                """.trimIndent()

                controller.assistantMessageUpdated(messageId, first, complete = false)
                controller.assistantMessageUpdated(messageId, second, complete = false)
                controller.assistantMessageUpdated(messageId, table, complete = false)
                controller.assistantMessageUpdated(messageId, complete, complete = true)

                val evidence = runBlocking {
                    withTimeout(Duration.ofMinutes(6).toMillis()) {
                        while (true) {
                            val current = capture.snapshot(messageId)
                            if (current.size == 4 && current.all { it.successfulSynthesisCount == 1 }) return@withTimeout current
                            controller.state.value.errorCode?.let { code ->
                                val details = synchronized(clientDiagnostics) { clientDiagnostics.joinToString(" | ") }
                                error("Diagnostic synthesis failed: code=$code, details=$details")
                            }
                            delay(100)
                        }
                        error("unreachable")
                    }
                }
                assertEquals(4, evidence.size)
                assertTrue(evidence.last().normalizedLength in 220..260)
                assertTrue(evidence.all { it.httpRequestCount == 2 && it.httpResponseCount == 2 })
                assertTrue(evidence.all { it.synthesisQueueEnqueueCount == 1 })
                assertTrue(evidence.all { it.audioPlaybackEnqueueCount == 0 && it.playStartCount == 0 })
                assertTrue(evidence.all { it.violations.isEmpty() })

                val transcripts = evidence.map { chunk ->
                    val audioPath = checkNotNull(chunk.audioPath)
                    val transcript = runBlocking {
                        client.transcribe(
                            audio = RecordedAudio(
                                wav = Files.readAllBytes(audioPath),
                                sampleRateHz = 24_000,
                            ),
                            model = profile.sttModel,
                            region = profile.sttRegion,
                            workspaceId = profile.workspaceId,
                            timeout = Duration.ofSeconds(profile.sttTimeoutSeconds),
                            secret = secret,
                        ).text
                    }
                    capture.recordVerificationTranscript(messageId, chunk.chunkId, transcript)
                    transcript
                }
                assertTrue(transcripts.all(String::isNotBlank))
                println("TTS_DIAGNOSTIC_MESSAGE_ID=$messageId")
                println("TTS_DIAGNOSTIC_DIRECTORY=${capture.messagePath(messageId).toAbsolutePath().normalize()}")
                evidence.zip(transcripts).forEach { (chunk, transcript) ->
                    println(
                        "TTS_DIAGNOSTIC_CHUNK chunkId=${chunk.chunkId} raw=${chunk.rawStartOffset}..${chunk.rawEndOffset} " +
                            "normalizedLength=${chunk.normalizedLength} textSha=${chunk.textSha256} " +
                            "http=${chunk.httpRequestCount}/${chunk.httpResponseCount} audioSha=${chunk.audioSha256} " +
                            "enqueue=${chunk.synthesisQueueEnqueueCount} play=${chunk.playStartCount} " +
                            "transcript=${transcript.replace(Regex("\\s+"), " ")}",
                    )
                }
            } finally {
                controller.close()
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
        const val STANDARD_SMOKE_ENVIRONMENT_VARIABLE = "EVE_ALIBABA_SPEECH_SMOKE"
        const val DIAGNOSTIC_SMOKE_ENVIRONMENT_VARIABLE = "EVE_ALIBABA_TTS_DIAGNOSTIC_SMOKE"
        const val DIAGNOSTIC_SMOKE_SYSTEM_PROPERTY = "eve.alibaba.tts.diagnostic.smoke"
        const val RECOGNITION_TEXT = "你好，这是语音识别测试。"
        const val SYNTHESIS_TEXT = "你好，这是语音合成测试。"
    }
}
