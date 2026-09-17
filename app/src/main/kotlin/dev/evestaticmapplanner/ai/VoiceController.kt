package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceErrorCode
import dev.evestaticmapplanner.embeddedai.VoiceException
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.embeddedai.recognitionConfig
import dev.evestaticmapplanner.embeddedai.synthesisConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class VoiceActivity { IDLE, RECORDING, TRANSCRIBING, SYNTHESIZING, PLAYING }

internal data class VoiceUiState(
    val activity: VoiceActivity = VoiceActivity.IDLE,
    val playingMessageId: String? = null,
    val errorCode: VoiceErrorCode? = null,
    val message: String? = null,
) {
    val recording: Boolean get() = activity == VoiceActivity.RECORDING
    val transcribing: Boolean get() = activity == VoiceActivity.TRANSCRIBING
    val playing: Boolean get() = activity == VoiceActivity.PLAYING
}

internal class VoiceController(
    private val configSource: () -> VoiceConfig,
    private val providerFactory: SpeechProviderFactory,
    private val recorder: VoiceRecorder,
    private val audioPlayer: VoiceAudioPlayer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutableState = MutableStateFlow(VoiceUiState())
    private var voiceJob: Job? = null
    private var activeRecording: Path? = null
    private var lastAutoReadMessageId: String? = null
    val state: StateFlow<VoiceUiState> = mutableState.asStateFlow()

    fun microphonePressed(onTranscript: (String, Boolean) -> Unit) {
        if (closed.get()) return
        when (mutableState.value.activity) {
            VoiceActivity.IDLE -> startRecording(onTranscript)
            VoiceActivity.RECORDING -> finishRecording(onTranscript)
            else -> Unit
        }
    }

    fun cancelVoiceActivity() {
        voiceJob?.cancel()
        voiceJob = null
        if (recorder.isRecording) recorder.cancel()
        activeRecording?.let { runCatching { Files.deleteIfExists(it) } }
        activeRecording = null
        audioPlayer.stop()
        mutableState.value = VoiceUiState(message = "Voice activity cancelled.")
    }

    fun speak(messageId: String, markdown: String) {
        if (closed.get()) return
        synthesizeAndPlay(messageId, markdown)
    }

    fun stopPlayback() {
        audioPlayer.stop()
        if (mutableState.value.activity in setOf(VoiceActivity.SYNTHESIZING, VoiceActivity.PLAYING)) {
            voiceJob?.cancel()
            voiceJob = null
            mutableState.value = VoiceUiState()
        }
    }

    fun assistantMessageCompleted(messageId: String, markdown: String) {
        val config = configSource()
        if (!config.readAssistantRepliesAloud || config.outputProvider == VoiceOutputProvider.OFF) return
        if (lastAutoReadMessageId == messageId) return
        lastAutoReadMessageId = messageId
        synthesizeAndPlay(messageId, markdown)
    }

    fun onNewChat() {
        lastAutoReadMessageId = null
        cancelVoiceActivity()
    }

    fun onAssistantWindowClosed() = cancelVoiceActivity()

    fun providerConfigurationChanged() = cancelVoiceActivity()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        voiceJob?.cancel()
        recorder.close()
        audioPlayer.close()
        activeRecording?.let { runCatching { Files.deleteIfExists(it) } }
        scope.cancel()
    }

    private fun startRecording(onTranscript: (String, Boolean) -> Unit) {
        val config = configSource()
        if (config.inputProvider == VoiceInputProvider.OFF) {
            mutableState.value = VoiceUiState(
                errorCode = VoiceErrorCode.MICROPHONE_UNAVAILABLE,
                message = "Voice Input is off. Enable it in AI Features settings.",
            )
            return
        }
        try {
            activeRecording = recorder.start()
            mutableState.value = VoiceUiState(VoiceActivity.RECORDING, message = "Recording… click again to transcribe.")
            voiceJob = scope.launch {
                delay(MAX_RECORDING_SECONDS * 1_000)
                withContext(uiDispatcher) { finishRecording(onTranscript) }
            }
        } catch (failure: Throwable) {
            publishFailure(failure, VoiceErrorCode.MICROPHONE_UNAVAILABLE, "The microphone could not be opened.")
        }
    }

    private fun finishRecording(onTranscript: (String, Boolean) -> Unit) {
        voiceJob?.cancel()
        voiceJob = null
        val wav = try {
            recorder.stop()
        } catch (failure: Throwable) {
            activeRecording = null
            publishFailure(failure, VoiceErrorCode.RECORDING_FAILED, "The recording could not be completed.")
            return
        }
        activeRecording = wav
        mutableState.value = VoiceUiState(VoiceActivity.TRANSCRIBING, message = "Transcribing…")
        voiceJob = scope.launch {
            try {
                val config = configSource()
                val provider = providerFactory.speechToText(config.inputProvider)
                    ?: throw VoiceException(VoiceErrorCode.TRANSCRIPTION_FAILED, "Voice Input is unavailable.")
                val transcript = provider.transcribe(
                    RecordedAudio(Files.readAllBytes(wav), sourcePath = wav),
                    config.recognitionConfig(),
                ).text.trim()
                withContext(uiDispatcher) {
                    mutableState.value = VoiceUiState(message = "Transcription complete.")
                    onTranscript(transcript, config.autoSendAfterTranscription)
                }
            } catch (_: CancellationException) {
                mutableState.value = VoiceUiState(message = "Transcription cancelled.")
            } catch (failure: Throwable) {
                publishFailure(failure, VoiceErrorCode.TRANSCRIPTION_FAILED, "The recording could not be transcribed.")
            } finally {
                runCatching { Files.deleteIfExists(wav) }
                activeRecording = null
                voiceJob = null
            }
        }
    }

    private fun synthesizeAndPlay(messageId: String, markdown: String) {
        val config = configSource()
        if (config.outputProvider == VoiceOutputProvider.OFF) {
            mutableState.value = VoiceUiState(
                errorCode = VoiceErrorCode.SYNTHESIS_FAILED,
                message = "Voice Output is off. Enable it in AI Features settings.",
            )
            return
        }
        val text = assistantMarkdownToSpeech(markdown)
        if (text.isBlank()) return
        stopPlayback()
        mutableState.value = VoiceUiState(VoiceActivity.SYNTHESIZING, playingMessageId = messageId, message = "Preparing speech…")
        voiceJob = scope.launch {
            try {
                val provider = providerFactory.textToSpeech(config.outputProvider)
                    ?: throw VoiceException(VoiceErrorCode.SYNTHESIS_FAILED, "Voice Output is unavailable.")
                val wav = provider.synthesize(text, config.synthesisConfig()).wav
                withContext(uiDispatcher) {
                    mutableState.value = VoiceUiState(
                        VoiceActivity.PLAYING,
                        playingMessageId = messageId,
                        message = "Playing…",
                    )
                    audioPlayer.play(wav) {
                        mutableState.value = VoiceUiState()
                    }
                }
            } catch (_: CancellationException) {
                mutableState.value = VoiceUiState()
            } catch (failure: Throwable) {
                publishFailure(failure, VoiceErrorCode.SYNTHESIS_FAILED, "The assistant reply could not be read aloud.")
            } finally {
                voiceJob = null
            }
        }
    }

    private fun publishFailure(failure: Throwable, fallbackCode: VoiceErrorCode, fallbackMessage: String) {
        val voiceFailure = failure as? VoiceException
        mutableState.value = VoiceUiState(
            errorCode = voiceFailure?.code ?: fallbackCode,
            message = voiceFailure?.safeMessage ?: fallbackMessage,
        )
    }
}
