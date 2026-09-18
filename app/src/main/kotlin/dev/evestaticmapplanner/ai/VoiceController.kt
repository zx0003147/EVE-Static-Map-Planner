package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.TtsProviderTraceSink
import dev.evestaticmapplanner.embeddedai.TtsRequestContext
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class VoiceActivity { IDLE, RECORDING, TRANSCRIBING, SYNTHESIZING, PLAYING }

internal data class VoiceUiState(
    val activity: VoiceActivity = VoiceActivity.IDLE,
    val playingMessageId: String? = null,
    val errorCode: VoiceErrorCode? = null,
    val message: String? = null,
    val pushToTalkRecording: Boolean = false,
) {
    val recording: Boolean get() = activity == VoiceActivity.RECORDING
    val transcribing: Boolean get() = activity == VoiceActivity.TRANSCRIBING
    val playing: Boolean get() = activity == VoiceActivity.PLAYING
}

internal class PushToTalkSession internal constructor(val id: Long)

private sealed interface RecordingOwner {
    data object Manual : RecordingOwner
    data class PushToTalk(val session: PushToTalkSession) : RecordingOwner
}

internal class VoiceController(
    private val configSource: () -> VoiceConfig,
    private val providerFactory: SpeechProviderFactory,
    private val recorder: VoiceRecorder,
    private val audioPlayer: VoiceAudioPlayer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val diagnostics: (String) -> Unit = {},
    private val ttsDiagnosticCapture: TtsDiagnosticCapture? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutableState = MutableStateFlow(VoiceUiState())
    private var voiceJob: Job? = null
    private var activeRecording: Path? = null
    private var recordingOwner: RecordingOwner? = null
    private val speechTracker = AssistantSpeechSubmissionTracker()
    private val speechLock = Any()
    private var speechRun = SpeechRun()
    private val manualInvocation = AtomicLong()
    private val synthesisRequestNumber = AtomicLong()
    private val enqueueNumber = AtomicLong()
    private val playOrdinal = AtomicLong()
    private val pushToTalkSessionNumber = AtomicLong()
    val state: StateFlow<VoiceUiState> = mutableState.asStateFlow()

    fun microphonePressed(onTranscript: (String, Boolean) -> Unit) {
        if (closed.get()) return
        when (mutableState.value.activity) {
            VoiceActivity.IDLE -> startRecording(RecordingOwner.Manual, onTranscript)
            VoiceActivity.RECORDING -> if (recordingOwner == RecordingOwner.Manual) {
                finishRecording(RecordingOwner.Manual, onTranscript)
            }
            else -> Unit
        }
    }

    fun beginPushToTalk(onTranscript: (String, Boolean) -> Unit): PushToTalkSession? {
        if (closed.get()) return null
        if (mutableState.value.activity !in setOf(
                VoiceActivity.IDLE,
                VoiceActivity.SYNTHESIZING,
                VoiceActivity.PLAYING,
            )
        ) {
            return null
        }
        val config = configSource()
        if (!validateInput(config)) return null
        interruptAssistantSpeech()
        val session = PushToTalkSession(pushToTalkSessionNumber.incrementAndGet())
        return session.takeIf { startRecording(RecordingOwner.PushToTalk(session), onTranscript, config) }
    }

    fun endPushToTalk(session: PushToTalkSession) {
        val owner = RecordingOwner.PushToTalk(session)
        if (recordingOwner == owner) finishRecording(owner, null)
    }

    fun cancelPushToTalk(session: PushToTalkSession) {
        if (recordingOwner == RecordingOwner.PushToTalk(session)) {
            cancelRecording()
            mutableState.value = VoiceUiState(message = "Voice activity cancelled.")
        }
    }

    fun cancelVoiceActivity() {
        cancelRecording()
        resetSpeechRun(clearTracker = false)
        mutableState.value = VoiceUiState(message = "Voice activity cancelled.")
    }

    fun speak(messageId: String, markdown: String) {
        if (closed.get()) return
        val config = configSource()
        if (!validateOutput(config)) return
        resetSpeechRun(clearTracker = false)
        queueSpeechChunks(
            oneShotSpeechChunks(messageId, markdown, manualInvocation.incrementAndGet()),
            config,
        )
    }

    fun stopPlayback() {
        resetSpeechRun(clearTracker = false)
        if (mutableState.value.activity in setOf(VoiceActivity.SYNTHESIZING, VoiceActivity.PLAYING)) {
            mutableState.value = VoiceUiState()
        }
    }

    fun interruptAssistantSpeech() {
        speechTracker.suppressIncomplete()
        resetSpeechRun(clearTracker = false)
        if (mutableState.value.activity in setOf(VoiceActivity.SYNTHESIZING, VoiceActivity.PLAYING)) {
            mutableState.value = VoiceUiState()
        }
    }

    fun assistantMessageCompleted(messageId: String, markdown: String) {
        assistantMessageUpdated(messageId, markdown, complete = true)
    }

    fun assistantMessageUpdated(messageId: String, accumulatedMarkdown: String, complete: Boolean) {
        val config = configSource()
        if (!config.readAssistantRepliesAloud || config.outputProvider == VoiceOutputProvider.OFF) return
        val update = speechTracker.update(messageId, accumulatedMarkdown, complete)
        update.ignoredReason?.let { reason ->
            log("TTS assistant update ignored: messageId=${safeIdentifier(messageId)}, reason=$reason")
        }
        queueSpeechChunks(update.chunks, config)
    }

    fun onNewChat() {
        speechTracker.clear()
        cancelVoiceActivity()
    }

    fun onAssistantWindowClosed() = cancelVoiceActivity()

    fun providerConfigurationChanged() {
        speechTracker.clear()
        cancelVoiceActivity()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        voiceJob?.cancel()
        resetSpeechRun(clearTracker = true)
        recorder.close()
        audioPlayer.close()
        activeRecording?.let { runCatching { Files.deleteIfExists(it) } }
        scope.cancel()
    }

    private fun startRecording(
        owner: RecordingOwner,
        onTranscript: (String, Boolean) -> Unit,
        config: VoiceConfig = configSource(),
    ): Boolean {
        if (!validateInput(config)) return false
        try {
            activeRecording = recorder.start()
            recordingOwner = owner
            currentTranscriptConsumer = onTranscript
            mutableState.value = VoiceUiState(
                activity = VoiceActivity.RECORDING,
                message = "Recording… click again to transcribe.",
                pushToTalkRecording = owner is RecordingOwner.PushToTalk,
            )
            voiceJob = scope.launch {
                delay(MAX_RECORDING_SECONDS * 1_000)
                withContext(uiDispatcher) { finishRecording(owner, onTranscript) }
            }
            return true
        } catch (failure: Throwable) {
            recordingOwner = null
            publishFailure(failure, VoiceErrorCode.MICROPHONE_UNAVAILABLE, "The microphone could not be opened.")
            return false
        }
    }

    private fun finishRecording(
        expectedOwner: RecordingOwner,
        onTranscriptOverride: ((String, Boolean) -> Unit)?,
    ) {
        if (recordingOwner != expectedOwner) return
        val onTranscript = onTranscriptOverride ?: currentTranscriptConsumer ?: return
        currentTranscriptConsumer = null
        voiceJob?.cancel()
        voiceJob = null
        recordingOwner = null
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
                if (activeRecording == wav) {
                    activeRecording = null
                    voiceJob = null
                }
            }
        }
    }

    private var currentTranscriptConsumer: ((String, Boolean) -> Unit)? = null

    private fun validateInput(config: VoiceConfig): Boolean {
        if (config.inputProvider != VoiceInputProvider.OFF) return true
        mutableState.value = VoiceUiState(
            errorCode = VoiceErrorCode.MICROPHONE_UNAVAILABLE,
            message = "Voice Input is off. Enable it in AI Features settings.",
        )
        return false
    }

    private fun cancelRecording() {
        voiceJob?.cancel()
        voiceJob = null
        recordingOwner = null
        currentTranscriptConsumer = null
        if (recorder.isRecording) recorder.cancel()
        activeRecording?.let { runCatching { Files.deleteIfExists(it) } }
        activeRecording = null
    }

    private fun validateOutput(config: VoiceConfig): Boolean {
        if (config.outputProvider == VoiceOutputProvider.OFF) {
            mutableState.value = VoiceUiState(
                errorCode = VoiceErrorCode.SYNTHESIS_FAILED,
                message = "Voice Output is off. Enable it in AI Features settings.",
            )
            return false
        }
        return true
    }

    private fun queueSpeechChunks(chunks: List<AssistantSpeechChunk>, config: VoiceConfig) {
        if (chunks.isEmpty() || !validateOutput(config)) return
        val provider = providerFactory.textToSpeech(config.outputProvider)
        if (provider == null) {
            publishFailure(
                VoiceException(VoiceErrorCode.SYNTHESIS_FAILED, "Voice Output is unavailable."),
                VoiceErrorCode.SYNTHESIS_FAILED,
                "Voice Output is unavailable.",
            )
            return
        }
        val synthesisConfig = config.synthesisConfig()
        val run = synchronized(speechLock) {
            speechRun.also { current ->
                if (current.worker?.isActive != true) {
                    current.worker = scope.launch { processSpeechRun(current) }
                }
            }
        }
        chunks.forEach { chunk ->
            val queued = QueuedSpeechChunk(
                chunk = chunk,
                providerName = config.outputProvider.name,
                provider = provider,
                config = synthesisConfig.copy(
                    requestContext = TtsRequestContext(
                        messageId = chunk.messageId,
                        chunkId = chunk.chunkId,
                        chunkIndex = chunk.sequence,
                        rawStartOffset = chunk.rawStartOffset,
                        rawEndOffset = chunk.rawEndOffset,
                        providerTraceSink = ttsDiagnosticCapture?.let {
                            TtsProviderTraceSink { event ->
                                recordDiagnostic("provider-http", chunk) { capture ->
                                    capture.recordProviderHttpEvent(chunk, event)
                                }
                            }
                        },
                    ),
                ),
                enqueueNumber = enqueueNumber.incrementAndGet(),
            )
            run.pending.incrementAndGet()
            if (run.channel.trySend(queued).isFailure) {
                run.pending.decrementAndGet()
                return@forEach
            }
            recordDiagnostic("synthesis-queue-enqueue", chunk) { capture ->
                capture.recordSynthesisQueueEnqueue(
                    chunk = chunk,
                    provider = queued.providerName,
                    config = queued.config,
                    enqueueOrdinal = queued.enqueueNumber,
                )
            }
            logChunk("enqueue", queued)
        }
        if (run.pending.get() > 0 && mutableState.value.activity == VoiceActivity.IDLE) {
            mutableState.value = VoiceUiState(
                VoiceActivity.SYNTHESIZING,
                playingMessageId = chunks.first().messageId,
                message = "Preparing speech…",
            )
        }
    }

    private suspend fun processSpeechRun(run: SpeechRun) {
        for (queued in run.channel) {
            if (!isCurrent(run) || !scope.isActive) break
            try {
                val audio = synthesizeWithRetry(queued)
                if (!isCurrent(run)) return
                if (ttsDiagnosticCapture?.isolatePlayback == true) {
                    recordDiagnostic("playback-suppressed", queued.chunk) { capture ->
                        capture.recordPlaybackSuppressed(queued.chunk)
                    }
                    logChunk("playbackSuppressed", queued, "reason=tts-diagnostic-isolation")
                } else {
                    playOnce(queued, audio.wav)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                logChunk("failed", queued, "error=${safeFailure(failure)}")
                abortSpeechRun(run)
                publishFailure(failure, VoiceErrorCode.SYNTHESIS_FAILED, "The assistant reply could not be read aloud.")
                return
            } finally {
                val remaining = run.pending.decrementAndGet()
                if (remaining == 0 && isCurrent(run)) mutableState.value = VoiceUiState()
            }
        }
    }

    private suspend fun synthesizeWithRetry(queued: QueuedSpeechChunk): dev.evestaticmapplanner.embeddedai.SynthesizedAudio {
        var attempt = 1
        while (true) {
            val requestNumber = synthesisRequestNumber.incrementAndGet()
            recordDiagnostic("synthesis-start", queued.chunk) { capture ->
                capture.recordSynthesisStarted(queued.chunk, attempt)
            }
            logChunk("synthesisStart", queued, "synthesisRequestNumber=$requestNumber, attempt=$attempt")
            try {
                val audio = queued.provider.synthesize(queued.chunk.text, queued.config)
                recordDiagnostic("synthesis-success", queued.chunk) { capture ->
                    capture.recordSynthesisSucceeded(queued.chunk, audio)
                }
                logChunk(
                    "synthesisSuccess",
                    queued,
                    "synthesisRequestNumber=$requestNumber, attempt=$attempt, audioByteCount=${audio.wav.size}",
                )
                return audio
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                recordDiagnostic("synthesis-failure", queued.chunk) { capture ->
                    capture.recordSynthesisFailed(queued.chunk)
                }
                logChunk(
                    "synthesisFailure",
                    queued,
                    "synthesisRequestNumber=$requestNumber, attempt=$attempt, error=${safeFailure(failure)}",
                )
                if (attempt >= MAX_SYNTHESIS_ATTEMPTS || !failure.isRetryableSynthesisFailure()) throw failure
                attempt++
                delay(SYNTHESIS_RETRY_DELAY_MILLIS)
            }
        }
    }

    private suspend fun playOnce(queued: QueuedSpeechChunk, wav: ByteArray) {
        val playbackFinished = CompletableDeferred<Unit>()
        val ordinal = playOrdinal.incrementAndGet()
        val playbackInstanceId = "${queued.chunk.chunkId}:play-$ordinal-${UUID.randomUUID()}"
        recordDiagnostic("playback-enqueue", queued.chunk) { capture ->
            capture.recordPlaybackEnqueued(queued.chunk, wav, ordinal, playbackInstanceId)
        }
        withContext(uiDispatcher) {
            mutableState.value = VoiceUiState(
                VoiceActivity.PLAYING,
                playingMessageId = queued.chunk.messageId,
                message = "Playing…",
            )
            recordDiagnostic("playback-start", queued.chunk) { capture ->
                capture.recordPlaybackStarted(queued.chunk)
            }
            logChunk("playbackStart", queued, "audioByteCount=${wav.size}")
            audioPlayer.play(
                wav = wav,
                context = VoiceAudioPlaybackContext(
                    playbackInstanceId = playbackInstanceId,
                    diagnosticSink = { snapshot ->
                        recordDiagnostic("audio-player-${snapshot.phase.lowercase()}", queued.chunk) { capture ->
                            capture.recordAudioPlayerDebug(queued.chunk, snapshot)
                        }
                    },
                ),
            ) {
                recordDiagnostic("playback-end", queued.chunk) { capture ->
                    capture.recordPlaybackEnded(queued.chunk)
                }
                playbackFinished.complete(Unit)
            }
        }
        try {
            playbackFinished.await()
            logChunk("playbackEnd", queued, "audioByteCount=${wav.size}")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + uiDispatcher) { audioPlayer.stop() }
            throw cancelled
        }
    }

    private fun resetSpeechRun(clearTracker: Boolean) {
        val old = synchronized(speechLock) {
            val previous = speechRun
            speechRun = SpeechRun()
            previous
        }
        old.channel.close()
        old.worker?.cancel()
        audioPlayer.stop()
        if (clearTracker) speechTracker.clear()
    }

    private fun abortSpeechRun(run: SpeechRun) {
        synchronized(speechLock) {
            if (speechRun === run) speechRun = SpeechRun()
        }
        run.channel.close()
        audioPlayer.stop()
    }

    private fun isCurrent(run: SpeechRun): Boolean = synchronized(speechLock) { speechRun === run }

    private fun logChunk(event: String, queued: QueuedSpeechChunk, extra: String? = null) {
        val chunk = queued.chunk
        val base = buildString {
            append("TTS ").append(event)
            append(": messageId=").append(safeIdentifier(chunk.messageId))
            append(", ttsChunkId=").append(safeIdentifier(chunk.chunkId))
            append(", rawStartOffset=").append(chunk.rawStartOffset)
            append(", rawEndOffset=").append(chunk.rawEndOffset)
            append(", normalizedCharCount=").append(chunk.text.codePointCount(0, chunk.text.length))
            append(", textPreview=").append(safeTextPreview(chunk.text))
            append(", provider=").append(queued.providerName)
            append(", model=").append(queued.config.model ?: "<none>")
            append(", voice=").append(queued.config.voice ?: "<none>")
            append(", enqueueNumber=").append(queued.enqueueNumber)
            extra?.let { append(", ").append(it) }
        }
        log(base)
    }

    private fun log(message: String) = runCatching { diagnostics(message) }

    private fun recordDiagnostic(
        event: String,
        chunk: AssistantSpeechChunk,
        record: (TtsDiagnosticCapture) -> Unit,
    ) {
        val capture = ttsDiagnosticCapture ?: return
        runCatching { record(capture) }.onFailure { failure ->
            log(
                "TTS diagnostic capture failed: event=$event, " +
                    "messageId=${safeIdentifier(chunk.messageId)}, " +
                    "ttsChunkId=${safeIdentifier(chunk.chunkId)}, " +
                    "error=${failure::class.simpleName.orEmpty()}",
            )
        }
    }

    private fun safeTextPreview(text: String): String = text
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_TTS_TEXT_PREVIEW_CHARACTERS)

    private fun safeIdentifier(value: String): String = value.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(120)

    private fun safeFailure(failure: Throwable): String = (failure as? VoiceException)?.let {
        "${it.code}:${it.safeMessage}"
    } ?: failure::class.simpleName.orEmpty()

    private fun Throwable.isRetryableSynthesisFailure(): Boolean = (this as? VoiceException)?.code in setOf(
        VoiceErrorCode.VOICE_NETWORK_ERROR,
        VoiceErrorCode.VOICE_TIMEOUT,
    )

    private class SpeechRun {
        val channel = Channel<QueuedSpeechChunk>(Channel.UNLIMITED)
        val pending = AtomicInteger()
        var worker: Job? = null
    }

    private data class QueuedSpeechChunk(
        val chunk: AssistantSpeechChunk,
        val providerName: String,
        val provider: dev.evestaticmapplanner.embeddedai.TextToSpeechProvider,
        val config: dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig,
        val enqueueNumber: Long,
    )

    private fun publishFailure(failure: Throwable, fallbackCode: VoiceErrorCode, fallbackMessage: String) {
        val voiceFailure = failure as? VoiceException
        mutableState.value = VoiceUiState(
            errorCode = voiceFailure?.code ?: fallbackCode,
            message = voiceFailure?.safeMessage ?: fallbackMessage,
        )
    }

    private companion object {
        const val MAX_SYNTHESIS_ATTEMPTS = 2
        const val SYNTHESIS_RETRY_DELAY_MILLIS = 150L
        const val MAX_TTS_TEXT_PREVIEW_CHARACTERS = 80
    }
}
