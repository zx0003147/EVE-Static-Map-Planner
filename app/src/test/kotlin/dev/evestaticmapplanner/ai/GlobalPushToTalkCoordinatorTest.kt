package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.MapSpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderCapability
import dev.evestaticmapplanner.embeddedai.SpeechRecognitionConfig
import dev.evestaticmapplanner.embeddedai.SpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.SpeechTranscript
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.shortcut.GlobalPushToTalkService
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.shortcut.PushToTalkActivationResult
import dev.evestaticmapplanner.shortcut.PushToTalkListener
import dev.evestaticmapplanner.shortcut.ShortcutKey
import java.nio.file.Files
import java.nio.file.Path
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalPushToTalkCoordinatorTest {
    @Test
    fun `open capture close and reopen own exactly one listener and cancel held audio`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = CoordinatorRecorder()
        val service = FakeGlobalPushToTalkService()
        val controller = voiceController(dispatcher, recorder, ImmediateTranscriber())
        val coordinator = GlobalPushToTalkCoordinator(service, controller)
        try {
            coordinator.bind("generation-1", KeyboardShortcut(ShortcutKey.F9)) { _, _ -> }
            assertEquals(1, service.activateCount)
            assertEquals(GlobalPushToTalkStatus.ACTIVE, coordinator.state.value.status)

            val firstListener = service.currentListener
            firstListener.onPressed()
            assertTrue(recorder.isRecording)
            coordinator.suspendForShortcutCapture()
            assertFalse(recorder.isRecording)
            assertEquals(1, recorder.cancelCount)

            coordinator.resumeAfterShortcutCapture()
            assertEquals(2, service.activateCount)
            coordinator.unbind("generation-1")
            assertEquals(GlobalPushToTalkStatus.INACTIVE, coordinator.state.value.status)

            coordinator.bind("generation-2", KeyboardShortcut(ShortcutKey.F9)) { _, _ -> }
            assertEquals(3, service.activateCount)
            assertEquals(1, service.activeListenerCount)
            firstListener.onPressed()
            assertFalse(recorder.isRecording)
        } finally {
            coordinator.close()
            controller.close()
        }
        assertTrue(service.closed)
    }

    @Test
    fun `STT busy ignores one complete physical cycle then accepts the next`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = CoordinatorRecorder()
        val transcriber = ControlledTranscriber()
        val service = FakeGlobalPushToTalkService()
        val controller = voiceController(dispatcher, recorder, transcriber)
        val coordinator = GlobalPushToTalkCoordinator(service, controller)
        try {
            coordinator.bind("generation", KeyboardShortcut(ShortcutKey.F9)) { _, _ -> }
            val listener = service.currentListener
            listener.onPressed()
            listener.onReleased()
            runCurrent()
            assertEquals(VoiceActivity.TRANSCRIBING, controller.state.value.activity)

            listener.onPressed()
            listener.onReleased()
            assertEquals(1, recorder.startCount)

            transcriber.release.complete(Unit)
            advanceUntilIdle()
            listener.onPressed()
            assertEquals(2, recorder.startCount)
            listener.onCancelled()
        } finally {
            coordinator.close()
            controller.close()
        }
    }

    @Test
    fun `runtime shortcut update replaces the listener and clear leaves the hook inactive`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = CoordinatorRecorder()
        val service = FakeGlobalPushToTalkService()
        val controller = voiceController(dispatcher, recorder, ImmediateTranscriber())
        val coordinator = GlobalPushToTalkCoordinator(service, controller)
        try {
            coordinator.bind("generation", KeyboardShortcut(ShortcutKey.F9)) { _, _ -> }
            val oldListener = service.currentListener

            coordinator.bind("generation", KeyboardShortcut(ShortcutKey.SPACE)) { _, _ -> }
            val newListener = service.currentListener
            oldListener.onPressed()
            assertFalse(recorder.isRecording)
            newListener.onPressed()
            assertTrue(recorder.isRecording)
            newListener.onCancelled()

            coordinator.bind("generation", null) { _, _ -> }
            assertEquals(GlobalPushToTalkStatus.INACTIVE, coordinator.state.value.status)
            assertEquals(2, service.activateCount)
            assertEquals(0, service.activeListenerCount)
        } finally {
            coordinator.close()
            controller.close()
        }
    }

    private fun voiceController(
        dispatcher: CoroutineDispatcher,
        recorder: CoordinatorRecorder,
        transcriber: SpeechToTextProvider,
    ) = VoiceController(
        configSource = { VoiceConfig(inputProvider = VoiceInputProvider.LOCAL) },
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

    private class FakeGlobalPushToTalkService : GlobalPushToTalkService {
        private val listeners = mutableListOf<PushToTalkListener>()
        var activateCount = 0
        var deactivateCount = 0
        var closed = false
        val currentListener: PushToTalkListener get() = listeners.last()
        val activeListenerCount: Int get() = if (listeners.isEmpty() || deactivateCount >= activateCount) 0 else 1

        override fun activate(
            shortcut: KeyboardShortcut,
            listener: PushToTalkListener,
        ): PushToTalkActivationResult {
            activateCount++
            listeners += listener
            return PushToTalkActivationResult.Active
        }

        override fun deactivate() {
            if (deactivateCount < activateCount) deactivateCount++
        }

        override fun close() {
            deactivate()
            closed = true
        }
    }

    private open class ImmediateTranscriber : SpeechToTextProvider {
        override val capability = SpeechProviderCapability(true, false, false, false, false)
        override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig) = SpeechTranscript("ok")
    }

    private class ControlledTranscriber : ImmediateTranscriber() {
        val release = CompletableDeferred<Unit>()
        override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig): SpeechTranscript {
            release.await()
            return SpeechTranscript("ok")
        }
    }

    private class CoordinatorRecorder : VoiceRecorder {
        override var isRecording = false
            private set
        var startCount = 0
        var cancelCount = 0
        private var path: Path? = null

        override fun start(): Path {
            isRecording = true
            startCount++
            return Files.createTempFile("coordinator-ptt", ".wav").also {
                Files.write(it, byteArrayOf(1))
                path = it
            }
        }

        override fun stop(): Path {
            check(isRecording)
            isRecording = false
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
