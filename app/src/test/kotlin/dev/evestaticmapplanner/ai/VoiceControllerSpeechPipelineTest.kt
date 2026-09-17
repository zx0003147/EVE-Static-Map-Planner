package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.MapSpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.SpeechProviderCapability
import dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig
import dev.evestaticmapplanner.embeddedai.SynthesizedAudio
import dev.evestaticmapplanner.embeddedai.TextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceErrorCode
import dev.evestaticmapplanner.embeddedai.VoiceException
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerSpeechPipelineTest {
    @Test
    fun `diagnostic switch defaults to isolation and requires an explicit runtime flag`() {
        val root = Files.createTempDirectory("tts-diagnostic-switch")

        assertNull(defaultTtsDiagnosticCapture(root, environment = { null }, systemProperty = { null }))
        assertTrue(
            checkNotNull(
                defaultTtsDiagnosticCapture(
                    root,
                    environment = { name -> if (name == TTS_DIAGNOSTIC_ENVIRONMENT_VARIABLE) "true" else null },
                    systemProperty = { null },
                ),
            ).isolatePlayback,
        )
        assertFalse(
            checkNotNull(
                defaultTtsDiagnosticCapture(
                    root,
                    environment = { null },
                    systemProperty = { name -> if (name == TTS_DIAGNOSTIC_PROPERTY) "capture" else null },
                ),
            ).isolatePlayback,
        )
    }

    @Test
    fun `plain completed reply submits every sentence once`() {
        val tracker = AssistantSpeechSubmissionTracker()

        val update = tracker.update("message-1", "你好，这是第一句。这是第二句。", complete = true)
        val duplicate = tracker.update("message-1", "你好，这是第一句。这是第二句。", complete = true)

        assertEquals("你好，这是第一句。这是第二句。", update.chunks.joinToString("") { it.text.replace(" ", "") })
        assertTrue(duplicate.chunks.isEmpty())
    }

    @Test
    fun `cumulative assistant updates submit only new raw ranges`() {
        val tracker = AssistantSpeechSubmissionTracker()

        val chunks = listOf(
            tracker.update("message-1", "第一句。", complete = false),
            tracker.update("message-1", "第一句。第二句。", complete = false),
            tracker.update("message-1", "第一句。第二句。第三句。", complete = true),
        ).flatMap { it.chunks }

        assertEquals(listOf("第一句。", "第二句。", "第三句。"), chunks.map { it.text })
        assertEquals(listOf(0 to 4, 4 to 8, 8 to 12), chunks.map { it.rawStartOffset to it.rawEndOffset })
        assertEquals(listOf("message-1:1", "message-1:2", "message-1:3"), chunks.map { it.chunkId })
    }

    @Test
    fun `partial tokens wait for sentence boundaries and final tail flushes once`() {
        val tracker = AssistantSpeechSubmissionTracker()

        val first = tracker.update("message-2", "这是第", complete = false)
        val second = tracker.update("message-2", "这是第一句。然后是", complete = false)
        val third = tracker.update("message-2", "这是第一句。然后是第二句", complete = true)
        val duplicateComplete = tracker.update("message-2", "这是第一句。然后是第二句", complete = true)

        assertTrue(first.chunks.isEmpty())
        assertEquals(listOf("这是第一句。"), second.chunks.map { it.text })
        assertEquals(listOf("然后是第二句"), third.chunks.map { it.text })
        assertTrue(duplicateComplete.chunks.isEmpty())
    }

    @Test
    fun `markdown length changes never advance or reuse normalized offsets`() {
        val tracker = AssistantSpeechSubmissionTracker()
        val firstRaw = "**第一句。**"
        val secondRaw = "$firstRaw\n[第二句。](https://example.com)"
        val finalRaw = "$secondRaw\n第三句。"

        val first = tracker.update("markdown-offsets", firstRaw, complete = false)
        val second = tracker.update("markdown-offsets", secondRaw, complete = false)
        val final = tracker.update("markdown-offsets", finalRaw, complete = true)
        val duplicateFinal = tracker.update("markdown-offsets", finalRaw, complete = true)
        val chunks = first.chunks + second.chunks + final.chunks

        assertEquals(listOf("第一句。", "第二句。", "第三句。"), chunks.map { it.text })
        assertEquals(
            listOf(0 to firstRaw.length, firstRaw.length to secondRaw.length, secondRaw.length to finalRaw.length),
            chunks.map { it.rawStartOffset to it.rawEndOffset },
        )
        assertTrue(chunks.all { it.text.length != it.rawEndOffset - it.rawStartOffset })
        assertTrue(duplicateFinal.chunks.isEmpty())
    }

    @Test
    fun `streaming markdown table waits for a stable table block and never overlaps final tail`() {
        val tracker = AssistantSpeechSubmissionTracker()
        val header = "| 名称 | securityStatus |\n"
        val divider = "$header|---|---|\n"
        val row = "$divider| Atioth | -0.018471 |\n"
        val closedTable = "$row\n"
        val complete = "${closedTable}第三句。"

        assertTrue(tracker.update("streaming-table", header, complete = false).chunks.isEmpty())
        assertTrue(tracker.update("streaming-table", divider, complete = false).chunks.isEmpty())
        assertTrue(tracker.update("streaming-table", row, complete = false).chunks.isEmpty())
        val table = tracker.update("streaming-table", closedTable, complete = false)
        val tail = tracker.update("streaming-table", complete, complete = true)

        assertEquals(listOf("Atioth，安全等级 负 0.018471。"), table.chunks.map { it.text })
        assertEquals(listOf("第三句。"), tail.chunks.map { it.text })
        assertEquals(0 to closedTable.length, table.chunks.single().rawStartOffset to table.chunks.single().rawEndOffset)
        assertEquals(closedTable.length to complete.length, tail.chunks.single().rawStartOffset to tail.chunks.single().rawEndOffset)
    }

    @Test
    fun `markdown list table and mixed eve text normalize before submission`() {
        val tracker = AssistantSpeechSubmissionTracker()
        val markdown = """
            1. 第一项
            2. 第二项
            - 第三项

            | 名称 | 星系 ID | securityStatus |
            |---|---|---|
            | Atioth | 30002489 | -0.018471 |

            Route from Atioth to L-TOFR is 24 jumps.
        """.trimIndent()

        val speech = tracker.update("message-markdown", markdown, complete = true)
            .chunks.joinToString(" ") { it.text }

        assertTrue("第一项。" in speech)
        assertTrue("Atioth，星系 ID 30002489，安全等级 负 0.018471。" in speech)
        assertTrue("Route from Atioth to L-TOFR is 24 jumps." in speech)
        assertFalse(speech.contains("|---"))
    }

    @Test
    fun `slow synthesis accepts later cumulative updates without resubmission`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = ControlledSynthesizer()
        val player = RecordingAudioPlayer()
        val controller = controller(dispatcher, provider, player)
        try {
            controller.assistantMessageUpdated("slow-message", "第一句。", complete = false)
            runCurrent()
            assertEquals(listOf("第一句。"), provider.requests)

            controller.assistantMessageUpdated("slow-message", "第一句。第二句。", complete = false)
            controller.assistantMessageUpdated("slow-message", "第一句。第二句。第三句。", complete = true)
            runCurrent()
            assertEquals(listOf("第一句。"), provider.requests)

            provider.releaseNext()
            runCurrent()
            assertEquals(listOf("第一句。", "第二句。"), provider.requests)
            provider.releaseNext()
            runCurrent()
            assertEquals(listOf("第一句。", "第二句。", "第三句。"), provider.requests)
            provider.releaseNext()
            advanceUntilIdle()

            assertEquals(listOf("第一句。", "第二句。", "第三句。"), provider.requests)
            assertEquals(3, player.played.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `each synthesized chunk is enqueued and played exactly once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = RecordingSynthesizer()
        val player = RecordingAudioPlayer()
        val diagnostics = mutableListOf<String>()
        val controller = controller(dispatcher, provider, player, diagnostics::add)
        try {
            controller.assistantMessageUpdated("queue-message", "第一句。", complete = false)
            controller.assistantMessageUpdated("queue-message", "第一句。第二句。", complete = false)
            controller.assistantMessageUpdated("queue-message", "第一句。第二句。第三句。", complete = true)
            advanceUntilIdle()

            assertEquals(listOf("第一句。", "第二句。", "第三句。"), provider.requests)
            assertEquals(3, player.played.size)
            assertEquals(3, diagnostics.count { "TTS enqueue:" in it })
            assertEquals(3, diagnostics.count { "TTS playbackStart:" in it })
            assertEquals(3, diagnostics.count { "TTS playbackEnd:" in it })
            assertTrue(diagnostics.all { "textPreview=" !in it || it.substringAfter("textPreview=").length < 300 })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `long reply is synthesized in ordered bounded chunks without loss or duplication`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = RecordingSynthesizer()
        val player = RecordingAudioPlayer()
        val controller = controller(dispatcher, provider, player)
        val reply = (1..90).joinToString(" ") { index -> "第${index}段 Route to L-TOFR is ${index}." }
        try {
            controller.assistantMessageCompleted("long-message", reply)
            advanceUntilIdle()

            assertTrue(provider.requests.size > 1)
            assertTrue(provider.requests.all { it.codePointCount(0, it.length) <= 500 })
            assertEquals(
                reply.replace(Regex("\\s+"), ""),
                provider.requests.joinToString("").replace(Regex("\\s+"), ""),
            )
            assertEquals(provider.requests.size, player.played.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `retry performs two synthesis attempts but only one playback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = RetryOnceSynthesizer()
        val player = RecordingAudioPlayer()
        val diagnostics = mutableListOf<String>()
        val controller = controller(dispatcher, provider, player, diagnostics::add)
        try {
            controller.assistantMessageCompleted("retry-message", "Route ready.")
            advanceUntilIdle()

            assertEquals(2, provider.attempts)
            assertEquals(1, player.played.size)
            assertEquals(2, diagnostics.count { "TTS synthesisStart:" in it })
            assertEquals(1, diagnostics.count { "TTS playbackStart:" in it })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `diagnostic evidence records A B C provider buffers and player writes exactly once in order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = Files.createTempDirectory("tts-diagnostic-playback")
        val capture = TtsDiagnosticCapture(root, isolatePlayback = false)
        val provider = DistinctAudioSynthesizer()
        val player = RecordingAudioPlayer()
        val controller = controller(dispatcher, provider, player, ttsDiagnosticCapture = capture)
        try {
            controller.assistantMessageUpdated("diagnostic-abc", "第一句。", complete = false)
            controller.assistantMessageUpdated("diagnostic-abc", "第一句。第二句。", complete = false)
            controller.assistantMessageUpdated("diagnostic-abc", "第一句。第二句。第三句。", complete = true)
            advanceUntilIdle()

            assertEquals(listOf("A", "B", "C"), player.played.map { it.toString(Charsets.UTF_8) })
            val evidence = capture.snapshot("diagnostic-abc")
            assertEquals(3, evidence.size)
            assertTrue(evidence.all { it.synthesisQueueEnqueueCount == 1 })
            assertTrue(evidence.all { it.synthesisAttemptCount == 1 && it.successfulSynthesisCount == 1 })
            assertTrue(evidence.all { it.audioPlaybackEnqueueCount == 1 })
            assertTrue(evidence.all { it.playStartCount == 1 && it.playEndCount == 1 })
            assertTrue(evidence.all { it.violations.isEmpty() })
            assertEquals(listOf("A", "B", "C"), evidence.map { Files.readString(checkNotNull(it.audioPath)) })
            assertTrue(Files.isRegularFile(root.resolve("diagnostic-abc").resolve("manifest.json")))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `diagnostic isolation saves provider audio before player and suppresses automatic playback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = Files.createTempDirectory("tts-diagnostic-isolation")
        val capture = TtsDiagnosticCapture(root, isolatePlayback = true)
        val provider = DistinctAudioSynthesizer()
        val player = RecordingAudioPlayer()
        val controller = controller(dispatcher, provider, player, ttsDiagnosticCapture = capture)
        try {
            controller.assistantMessageCompleted("diagnostic-isolated", "隔离测试。")
            advanceUntilIdle()

            assertTrue(player.played.isEmpty())
            val evidence = capture.snapshot("diagnostic-isolated").single()
            assertEquals(1, evidence.successfulSynthesisCount)
            assertEquals(0, evidence.audioPlaybackEnqueueCount)
            assertEquals(0, evidence.playStartCount)
            assertEquals("A", Files.readString(checkNotNull(evidence.audioPath)))
            assertEquals("隔离测试。", Files.readString(evidence.textPath))
        } finally {
            controller.close()
        }
    }

    private fun controller(
        dispatcher: CoroutineDispatcher,
        provider: TextToSpeechProvider,
        player: VoiceAudioPlayer,
        diagnostics: (String) -> Unit = {},
        ttsDiagnosticCapture: TtsDiagnosticCapture? = null,
    ) = VoiceController(
        configSource = {
            VoiceConfig(
                outputProvider = VoiceOutputProvider.LOCAL,
                readAssistantRepliesAloud = true,
            )
        },
        providerFactory = MapSpeechProviderFactory(
            speechToTextProviders = emptyMap(),
            textToSpeechProviders = mapOf(VoiceOutputProvider.LOCAL to provider),
        ),
        recorder = object : VoiceRecorder {
            override val isRecording = false
            override fun start() = Files.createTempFile("unused-recorder", ".wav")
            override fun stop() = error("not recording")
            override fun cancel() = Unit
            override fun close() = Unit
        },
        audioPlayer = player,
        ioDispatcher = dispatcher,
        uiDispatcher = dispatcher,
        diagnostics = diagnostics,
        ttsDiagnosticCapture = ttsDiagnosticCapture,
    )

    private open class RecordingSynthesizer : TextToSpeechProvider {
        override val capability = SpeechProviderCapability(false, true, false, true, false)
        val requests = mutableListOf<String>()
        override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
            requests += text
            return SynthesizedAudio("audio-${requests.size}".toByteArray())
        }
    }

    private class ControlledSynthesizer : RecordingSynthesizer() {
        private val releases = ArrayDeque<CompletableDeferred<Unit>>()
        override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
            requests += text
            val release = CompletableDeferred<Unit>()
            releases += release
            release.await()
            return SynthesizedAudio("audio-${requests.size}".toByteArray())
        }
        fun releaseNext() = releases.removeFirst().complete(Unit)
    }

    private class RetryOnceSynthesizer : TextToSpeechProvider {
        override val capability = SpeechProviderCapability(false, true, false, true, false)
        var attempts = 0
        override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
            attempts++
            if (attempts == 1) throw VoiceException(VoiceErrorCode.VOICE_NETWORK_ERROR, "temporary network error")
            return SynthesizedAudio("audio-success".toByteArray())
        }
    }

    private class DistinctAudioSynthesizer : TextToSpeechProvider {
        override val capability = SpeechProviderCapability(false, true, false, true, false)
        private val audio = ArrayDeque(listOf("A", "B", "C").map(String::toByteArray))
        override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio =
            SynthesizedAudio(audio.removeFirst())
    }

    private class RecordingAudioPlayer : VoiceAudioPlayer {
        val played = mutableListOf<ByteArray>()
        override fun play(wav: ByteArray, onFinished: () -> Unit) {
            played += wav
            onFinished()
        }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
