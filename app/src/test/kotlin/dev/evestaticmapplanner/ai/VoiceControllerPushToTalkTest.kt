package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.MapSpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderCapability
import dev.evestaticmapplanner.embeddedai.SpeechRecognitionConfig
import dev.evestaticmapplanner.embeddedai.SpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.SpeechTranscript
import dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig
import dev.evestaticmapplanner.embeddedai.SynthesizedAudio
import dev.evestaticmapplanner.embeddedai.TextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerPushToTalkTest {
    @Test
    fun `PTT begin and end transcribes once with existing Auto Send value`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = RecordingVoiceRecorder()
        val transcriber = RecordingTranscriber("Set destination to Jita")
        val controller = controller(dispatcher, recorder, transcriber, autoSend = true)
        val transcripts = mutableListOf<Pair<String, Boolean>>()
        try {
            val session = controller.beginPushToTalk { text, autoSend -> transcripts += text to autoSend }

            assertNotNull(session)
            assertTrue(recorder.isRecording)
            assertTrue(controller.state.value.pushToTalkRecording)

            controller.endPushToTalk(session)
            advanceUntilIdle()

            assertEquals(1, recorder.stopCount)
            assertEquals(1, transcriber.calls)
            assertEquals(listOf("Set destination to Jita" to true), transcripts)
            assertEquals(VoiceActivity.IDLE, controller.state.value.activity)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `PTT cancellation deletes recording and never starts STT`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = RecordingVoiceRecorder()
        val transcriber = RecordingTranscriber("must not appear")
        val controller = controller(dispatcher, recorder, transcriber)
        try {
            val session = assertNotNull(controller.beginPushToTalk { _, _ -> error("unexpected transcript") })
            val path = assertNotNull(recorder.path)

            controller.cancelPushToTalk(session)
            advanceUntilIdle()

            assertEquals(1, recorder.cancelCount)
            assertEquals(0, recorder.stopCount)
            assertEquals(0, transcriber.calls)
            assertFalse(Files.exists(path))
            assertEquals(VoiceActivity.IDLE, controller.state.value.activity)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `manual microphone and PTT cannot stop each other's recording`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = RecordingVoiceRecorder()
        val controller = controller(dispatcher, recorder, RecordingTranscriber("ok"))
        try {
            controller.microphonePressed { _, _ -> }
            assertNull(controller.beginPushToTalk { _, _ -> })
            controller.endPushToTalk(PushToTalkSession(999))
            assertTrue(recorder.isRecording)
            assertEquals(0, recorder.stopCount)

            controller.cancelVoiceActivity()
            val session = assertNotNull(controller.beginPushToTalk { _, _ -> })
            controller.microphonePressed { _, _ -> }
            assertTrue(recorder.isRecording)
            assertEquals(0, recorder.stopCount)
            controller.cancelPushToTalk(session)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `PTT interrupts playback and suppresses later chunks from the same streaming reply`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = RecordingVoiceRecorder()
        val transcriber = RecordingTranscriber("ok")
        val synthesizer = RecordingSynthesizer()
        val player = HoldingAudioPlayer()
        val controller = VoiceController(
            configSource = {
                VoiceConfig(
                    inputProvider = VoiceInputProvider.LOCAL,
                    outputProvider = VoiceOutputProvider.LOCAL,
                    readAssistantRepliesAloud = true,
                )
            },
            providerFactory = MapSpeechProviderFactory(
                speechToTextProviders = mapOf(VoiceInputProvider.LOCAL to transcriber),
                textToSpeechProviders = mapOf(VoiceOutputProvider.LOCAL to synthesizer),
            ),
            recorder = recorder,
            audioPlayer = player,
            ioDispatcher = dispatcher,
            uiDispatcher = dispatcher,
        )
        try {
            controller.assistantMessageUpdated("streaming", "First sentence.", complete = false)
            runCurrent()
            assertEquals(listOf("First sentence."), synthesizer.requests)
            assertEquals(VoiceActivity.PLAYING, controller.state.value.activity)

            val session = assertNotNull(controller.beginPushToTalk { _, _ -> })
            runCurrent()
            assertTrue(player.stopCount > 0)
            controller.assistantMessageUpdated(
                "streaming",
                "First sentence. Second sentence.",
                complete = false,
            )
            controller.cancelPushToTalk(session)
            controller.assistantMessageCompleted(
                "streaming",
                "First sentence. Second sentence. Final sentence.",
            )
            controller.assistantMessageCompleted("next", "Next reply.")
            runCurrent()

            assertEquals(listOf("First sentence.", "Next reply."), synthesizer.requests)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `PTT timeout transcribes once and stays latched until physical release`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = RecordingVoiceRecorder()
        val transcriber = RecordingTranscriber("timed out recording")
        val controller = controller(dispatcher, recorder, transcriber)
        val transcripts = mutableListOf<String>()
        try {
            val session = assertNotNull(controller.beginPushToTalk { text, _ -> transcripts += text })

            advanceTimeBy(MAX_RECORDING_SECONDS * 1_000)
            advanceUntilIdle()
            assertEquals(1, recorder.stopCount)
            assertEquals(1, transcriber.calls)
            assertEquals(listOf("timed out recording"), transcripts)

            controller.endPushToTalk(session)
            advanceUntilIdle()
            assertEquals(1, recorder.stopCount)
            assertEquals(1, transcriber.calls)
        } finally {
            controller.close()
        }
    }

    private fun controller(
        dispatcher: CoroutineDispatcher,
        recorder: RecordingVoiceRecorder,
        transcriber: RecordingTranscriber,
        autoSend: Boolean = false,
    ) = VoiceController(
        configSource = {
            VoiceConfig(
                inputProvider = VoiceInputProvider.LOCAL,
                autoSendAfterTranscription = autoSend,
            )
        },
        providerFactory = MapSpeechProviderFactory(
            speechToTextProviders = mapOf(VoiceInputProvider.LOCAL to transcriber),
            textToSpeechProviders = emptyMap(),
        ),
        recorder = recorder,
        audioPlayer = object : VoiceAudioPlayer {
            override fun play(wav: ByteArray, onFinished: () -> Unit) = onFinished()
            override fun stop() = Unit
            override fun close() = Unit
        },
        ioDispatcher = dispatcher,
        uiDispatcher = dispatcher,
    )

    private class RecordingTranscriber(private val transcript: String) : SpeechToTextProvider {
        override val capability = SpeechProviderCapability(true, false, false, false, false)
        var calls = 0
        override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig): SpeechTranscript {
            calls++
            return SpeechTranscript(transcript)
        }
    }

    private class RecordingSynthesizer : TextToSpeechProvider {
        override val capability = SpeechProviderCapability(false, true, false, false, false)
        val requests = mutableListOf<String>()
        override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
            requests += text
            return SynthesizedAudio(byteArrayOf(1, 2, 3))
        }
    }

    private class HoldingAudioPlayer : VoiceAudioPlayer {
        var stopCount = 0
        override fun play(wav: ByteArray, onFinished: () -> Unit) = Unit
        override fun stop() { stopCount++ }
        override fun close() = Unit
    }

    private class RecordingVoiceRecorder : VoiceRecorder {
        override var isRecording = false
            private set
        var path: Path? = null
            private set
        var stopCount = 0
        var cancelCount = 0

        override fun start(): Path {
            check(!isRecording)
            isRecording = true
            return Files.createTempFile("ptt-test", ".wav").also {
                Files.write(it, byteArrayOf(1, 2, 3))
                path = it
            }
        }

        override fun stop(): Path {
            check(isRecording)
            isRecording = false
            stopCount++
            return checkNotNull(path)
        }

        override fun cancel() {
            if (!isRecording) return
            isRecording = false
            cancelCount++
            path?.let(Files::deleteIfExists)
        }

        override fun close() {
            if (isRecording) cancel()
            path?.let(Files::deleteIfExists)
        }
    }
}
