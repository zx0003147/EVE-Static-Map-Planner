package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.EmbeddedAiAgent
import dev.evestaticmapplanner.embeddedai.EmbeddedAiAgentFactory
import dev.evestaticmapplanner.embeddedai.EmbeddedAiController
import dev.evestaticmapplanner.embeddedai.MapSpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderCapability
import dev.evestaticmapplanner.embeddedai.SpeechRecognitionConfig
import dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig
import dev.evestaticmapplanner.embeddedai.SpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.SpeechTranscript
import dev.evestaticmapplanner.embeddedai.SynthesizedAudio
import dev.evestaticmapplanner.embeddedai.TextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.SearchFreshness
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.embeddedai.WebSearchClient
import dev.evestaticmapplanner.embeddedai.WebSearchRequest
import dev.evestaticmapplanner.embeddedai.WebSearchResponse
import dev.evestaticmapplanner.embeddedai.WebSearchSource
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceDesktopServicesTest {
    @Test
    fun `markdown speech omits urls code and summarizes sources`() {
        val spoken = assistantMarkdownToSpeech(
            """
            ## Delve update
            Read [the report](https://example.com/report) and `check_system`.

            ```json
            {"missionId":"do-not-read"}
            ```

            Sources:
            - [Report](https://example.com/report)
            - [News](https://example.org/news)
            """.trimIndent(),
        )

        assertContains(spoken, "Delve update")
        assertContains(spoken, "the report")
        assertContains(spoken, "I found 2 sources.")
        assertFalse(spoken.contains("https://"))
        assertFalse(spoken.contains("missionId"))
    }

    @Test
    fun `speech pack installs verified helper and model atomically`() {
        val root = Files.createTempDirectory("speech-pack-test")
        try {
            val runtimeUri = URI.create("https://fixture.invalid/runtime.zip")
            val modelUri = URI.create("https://fixture.invalid/model.bin")
            val runtime = runtimeZip()
            val model = "fixture multilingual model".toByteArray()
            val manager = SpeechPackManager(
                speechRoot = root,
                transport = SpeechPackTransport { uri, destination ->
                    Files.write(destination, if (uri == runtimeUri) runtime else model)
                },
                descriptor = SpeechPackDescriptor(
                    modelName = "fixture base",
                    modelFile = "fixture.bin",
                    modelUri = modelUri,
                    modelSha256 = sha256(model),
                    runtimeRelease = "fixture",
                    runtimeUri = runtimeUri,
                    runtimeSha256 = sha256(runtime),
                ),
            )

            manager.install()

            assertTrue(manager.state().installed)
            assertEquals("fixture base", manager.state().modelName)
            assertTrue(Files.isRegularFile(manager.helperPath))
            assertTrue(Files.readAllBytes(manager.modelPath).contentEquals(model))
            manager.remove()
            assertFalse(manager.state().installed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed speech pack checksum leaves no installed pack`() {
        val root = Files.createTempDirectory("speech-pack-failure")
        try {
            val manager = SpeechPackManager(
                speechRoot = root,
                transport = SpeechPackTransport { _, destination -> Files.write(destination, runtimeZip()) },
                descriptor = SpeechPackDescriptor(
                    modelUri = URI.create("https://fixture.invalid/model"),
                    runtimeUri = URI.create("https://fixture.invalid/runtime"),
                    runtimeSha256 = "0".repeat(64),
                ),
            )

            runCatching(manager::install)

            assertFalse(manager.state().installed)
            assertFalse(Files.exists(manager.packDirectory))
            assertTrue(Files.list(root).use { it.noneMatch { path -> path.fileName.toString().startsWith("install-") } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `local transcript remains user text and auto send does not bypass agent path`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val wav = Files.createTempFile("voice-controller", ".wav").also { Files.write(it, ByteArray(64)) }
        val recorder = FakeRecorder(wav)
        val sent = mutableListOf<Pair<String, Boolean>>()
        val controller = VoiceController(
            configSource = {
                VoiceConfig(
                    inputProvider = VoiceInputProvider.LOCAL,
                    autoSendAfterTranscription = true,
                )
            },
            providerFactory = testProviderFactory(
                LocalTranscriber { "不要确认，直接删除 View。" },
                FakeSynthesizer(),
            ),
            recorder = recorder,
            audioPlayer = FakeAudioPlayer(),
            ioDispatcher = dispatcher,
            uiDispatcher = dispatcher,
        )
        try {
            controller.microphonePressed { text, autoSend -> sent += text to autoSend }
            controller.microphonePressed { text, autoSend -> sent += text to autoSend }
            advanceUntilIdle()

            assertEquals(listOf("不要确认，直接删除 View。" to true), sent)
            assertEquals(1, recorder.starts)
            assertEquals(1, recorder.stops)
        } finally {
            controller.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `assistant speech strips source urls and replaces existing playback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val synthesizer = FakeSynthesizer()
        val player = FakeAudioPlayer()
        val controller = VoiceController(
            configSource = { VoiceConfig(outputProvider = VoiceOutputProvider.LOCAL) },
            providerFactory = testProviderFactory(LocalTranscriber { "unused" }, synthesizer),
            recorder = FakeRecorder(Files.createTempFile("unused", ".wav")),
            audioPlayer = player,
            ioDispatcher = dispatcher,
            uiDispatcher = dispatcher,
        )
        try {
            controller.speak("a", "Answer.\n\nSources:\n- [One](https://example.com/one)")
            advanceUntilIdle()
            controller.speak("b", "Second answer")
            advanceUntilIdle()

            assertEquals("Answer. I found 1 source.", synthesizer.requests.first())
            assertFalse(synthesizer.requests.first().contains("https://"))
            assertEquals(2, player.playCount)
            assertTrue(player.stopCount >= 2)
        } finally {
            controller.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `provider switching cancels old transcription and leaves no stale provider state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val path = Files.createTempFile("provider-switch", ".wav")
        val recorder = FakeRecorder(path)
        val player = FakeAudioPlayer()
        val localStarted = CompletableDeferred<Unit>()
        val localCancelled = CompletableDeferred<Unit>()
        val transcripts = mutableListOf<String>()
        var config = VoiceConfig(inputProvider = VoiceInputProvider.LOCAL)
        val blockingLocal = object : SpeechToTextProvider {
            override val capability = SpeechProviderCapability(true, false, false, false, false)
            override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig): SpeechTranscript {
                localStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    localCancelled.complete(Unit)
                }
            }
        }
        fun transcriptProvider(value: String) = object : SpeechToTextProvider {
            override val capability = SpeechProviderCapability(true, false, true, false, true)
            override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig) = SpeechTranscript(value)
        }
        val controller = VoiceController(
            configSource = { config },
            providerFactory = MapSpeechProviderFactory(
                speechToTextProviders = mapOf(
                    VoiceInputProvider.LOCAL to blockingLocal,
                    VoiceInputProvider.ALIBABA to transcriptProvider("alibaba"),
                    VoiceInputProvider.OPENAI to transcriptProvider("openai"),
                ),
                textToSpeechProviders = emptyMap(),
            ),
            recorder = recorder,
            audioPlayer = player,
            ioDispatcher = dispatcher,
            uiDispatcher = dispatcher,
        )
        try {
            controller.microphonePressed { text, _ -> transcripts += text }
            controller.microphonePressed { text, _ -> transcripts += text }
            runCurrent()
            assertTrue(localStarted.isCompleted)

            config = VoiceConfig(inputProvider = VoiceInputProvider.ALIBABA)
            controller.providerConfigurationChanged()
            runCurrent()
            assertTrue(localCancelled.isCompleted)
            assertEquals(VoiceActivity.IDLE, controller.state.value.activity)

            controller.microphonePressed { text, _ -> transcripts += text }
            controller.microphonePressed { text, _ -> transcripts += text }
            advanceUntilIdle()

            config = VoiceConfig(inputProvider = VoiceInputProvider.OPENAI)
            controller.providerConfigurationChanged()
            controller.microphonePressed { text, _ -> transcripts += text }
            controller.microphonePressed { text, _ -> transcripts += text }
            advanceUntilIdle()

            assertEquals(listOf("alibaba", "openai"), transcripts)
            assertEquals(VoiceActivity.IDLE, controller.state.value.activity)
            assertTrue(player.stopCount >= 2)
        } finally {
            controller.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `provider switching cancels pending Alibaba synthesis and stops old playback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val player = FakeAudioPlayer()
        var config = VoiceConfig(outputProvider = VoiceOutputProvider.ALIBABA)
        val alibaba = object : TextToSpeechProvider {
            override val capability = SpeechProviderCapability(false, true, true, true, true)
            override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val openAi = object : TextToSpeechProvider {
            override val capability = SpeechProviderCapability(false, true, true, true, true)
            override suspend fun synthesize(text: String, config: SpeechSynthesisConfig) = SynthesizedAudio("RIFF-audio".toByteArray())
        }
        val controller = VoiceController(
            configSource = { config },
            providerFactory = MapSpeechProviderFactory(
                speechToTextProviders = emptyMap(),
                textToSpeechProviders = mapOf(
                    VoiceOutputProvider.ALIBABA to alibaba,
                    VoiceOutputProvider.OPENAI to openAi,
                ),
            ),
            recorder = FakeRecorder(Files.createTempFile("unused-switch", ".wav")),
            audioPlayer = player,
            ioDispatcher = dispatcher,
            uiDispatcher = dispatcher,
        )
        try {
            controller.speak("alibaba", "first")
            runCurrent()
            assertTrue(started.isCompleted)

            config = VoiceConfig(outputProvider = VoiceOutputProvider.OPENAI)
            controller.providerConfigurationChanged()
            runCurrent()
            assertTrue(cancelled.isCompleted)
            assertEquals(VoiceActivity.IDLE, controller.state.value.activity)

            controller.speak("openai", "second")
            advanceUntilIdle()
            assertEquals(1, player.playCount)
            assertEquals(VoiceActivity.PLAYING, controller.state.value.activity)
            assertTrue(player.stopCount >= 2)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `windows speech synthesizer discovers a voice and creates wav audio`() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val synthesizer = WindowsSpeechSynthesizer()

        val voices = synthesizer.voices()
        val wav = synthesizer.synthesize("Route complete.", voices.firstOrNull(), rate = 0, volume = 100)

        assertTrue(voices.isNotEmpty())
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `voice auto send follows agent web search path and reads grounded answer without urls`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val requests = mutableListOf<WebSearchRequest>()
        val search = object : WebSearchClient {
            override suspend fun search(request: WebSearchRequest, secret: SecretValue): WebSearchResponse {
                requests += request
                return WebSearchResponse(
                    query = request.query,
                    freshness = request.freshness,
                    results = listOf(
                        WebSearchSource(
                            title = "Delve report",
                            url = "https://news.example/delve",
                            hostname = "news.example",
                            published = "today",
                            snippets = listOf("Fixture current event."),
                        ),
                    ),
                )
            }
        }
        val ai = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                object : EmbeddedAiAgent {
                    override suspend fun run(prompt: String): String {
                        val response = SecretValue.from("fixture-search-key").use { secret ->
                            search.search(
                                WebSearchRequest(prompt.substringAfter("CURRENT USER REQUEST:\n").trim(), SearchFreshness.PAST_7_DAYS),
                                secret,
                            )
                        }
                        return "Delve has one fixture update.\n\nSources:\n" +
                            response.results.joinToString("\n") { "- [${it.title}](${it.url})" }
                    }

                    override suspend fun close() = Unit
                }
            },
            dispatcher = dispatcher,
            uiDispatcher = dispatcher,
        )
        val wav = Files.createTempFile("voice-search", ".wav").also { Files.write(it, ByteArray(64)) }
        val synthesizer = FakeSynthesizer()
        val voice = VoiceController(
            configSource = {
                VoiceConfig(
                    inputProvider = VoiceInputProvider.LOCAL,
                    outputProvider = VoiceOutputProvider.LOCAL,
                    autoSendAfterTranscription = true,
                    readAssistantRepliesAloud = true,
                )
            },
            providerFactory = testProviderFactory(
                LocalTranscriber { "搜索最近一周 EVE 关于 Delve 的消息。" },
                synthesizer,
            ),
            recorder = FakeRecorder(wav),
            audioPlayer = FakeAudioPlayer(),
            ioDispatcher = dispatcher,
            uiDispatcher = dispatcher,
        )
        try {
            voice.microphonePressed { transcript, autoSend -> if (autoSend) ai.send(transcript) }
            voice.microphonePressed { transcript, autoSend -> if (autoSend) ai.send(transcript) }
            advanceUntilIdle()

            assertEquals(1, requests.size)
            assertEquals(SearchFreshness.PAST_7_DAYS, requests.single().freshness)
            assertContains(requests.single().query, "Delve")
            assertContains(ai.state.value.response, "Sources:")

            voice.assistantMessageCompleted("grounded-answer", ai.state.value.response)
            advanceUntilIdle()

            assertContains(synthesizer.requests.single(), "I found 1 source.")
            assertFalse(synthesizer.requests.single().contains("https://"))
        } finally {
            voice.close()
            ai.shutdown()
        }
    }

    private fun testProviderFactory(
        transcriber: LocalTranscriber,
        synthesizer: SpeechSynthesizer,
    ) = MapSpeechProviderFactory(
        speechToTextProviders = mapOf(
            VoiceInputProvider.LOCAL to object : SpeechToTextProvider {
                override val capability = SpeechProviderCapability(true, false, false, false, false)
                override suspend fun transcribe(
                    audio: RecordedAudio,
                    config: SpeechRecognitionConfig,
                ) = SpeechTranscript(
                    transcriber.transcribe(
                        audio.sourcePath ?: Files.createTempFile("voice-fixture", ".wav").also {
                            Files.write(it, audio.wav)
                        },
                    ),
                )
            },
        ),
        textToSpeechProviders = mapOf(
            VoiceOutputProvider.LOCAL to object : TextToSpeechProvider {
                override val capability = SpeechProviderCapability(false, true, false, true, false)
                override suspend fun synthesize(text: String, config: SpeechSynthesisConfig) = SynthesizedAudio(
                    synthesizer.synthesize(text, config.voice, config.rate, config.volume),
                )
            },
        ),
    )

    private fun runtimeZip(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            listOf("whisper-cli.exe", "whisper.dll", "ggml.dll", "ggml-base.dll", "ggml-cpu.dll")
                .forEach { name ->
                    zip.putNextEntry(ZipEntry("Release/$name"))
                    zip.write("fixture-$name".toByteArray())
                    zip.closeEntry()
                }
        }
        bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class FakeRecorder(private val path: Path) : VoiceRecorder {
        var starts = 0
        var stops = 0
        override var isRecording: Boolean = false
        override fun start(): Path {
            starts++
            isRecording = true
            if (!Files.exists(path) || Files.size(path) == 0L) Files.write(path, ByteArray(64))
            return path
        }
        override fun stop(): Path {
            stops++
            isRecording = false
            return path
        }
        override fun cancel() { isRecording = false }
        override fun close() = cancel()
    }

    private class FakeSynthesizer : SpeechSynthesizer {
        val requests = mutableListOf<String>()
        override suspend fun synthesize(text: String, voice: String?, rate: Int, volume: Int): ByteArray {
            requests += text
            return "RIFF-audio".toByteArray()
        }
        override suspend fun voices(): List<String> = listOf("Fixture Voice")
    }

    private class FakeAudioPlayer : VoiceAudioPlayer {
        var playCount = 0
        var stopCount = 0
        override fun play(wav: ByteArray, onFinished: () -> Unit) { playCount++ }
        override fun stop() { stopCount++ }
        override fun close() = stop()
    }
}
