package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.VoiceErrorCode
import dev.evestaticmapplanner.embeddedai.VoiceException
import dev.evestaticmapplanner.embeddedai.RecordedAudio
import dev.evestaticmapplanner.embeddedai.SpeechProviderCapability
import dev.evestaticmapplanner.embeddedai.SpeechRecognitionConfig
import dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig
import dev.evestaticmapplanner.embeddedai.SpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.SpeechTranscript
import dev.evestaticmapplanner.embeddedai.SynthesizedAudio
import dev.evestaticmapplanner.embeddedai.TextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.TtsTextNormalizer
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Comparator
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface VoiceRecorder : AutoCloseable {
    fun start(): Path
    fun stop(): Path
    fun cancel()
    val isRecording: Boolean
}

internal class MicrophoneWavRecorder(
    private val temporaryDirectory: Path? = null,
) : VoiceRecorder {
    private var line: TargetDataLine? = null
    private var recordingThread: Thread? = null
    private var output: Path? = null
    private var writeFailure: Throwable? = null

    override val isRecording: Boolean
        @Synchronized get() = line != null

    @Synchronized
    override fun start(): Path {
        check(line == null) { "A recording is already active" }
        val info = DataLine.Info(TargetDataLine::class.java, RECORDING_FORMAT)
        if (!AudioSystem.isLineSupported(info)) {
            throw voiceFailure(VoiceErrorCode.MICROPHONE_UNAVAILABLE, "No compatible microphone is available.")
        }
        val target = try {
            (AudioSystem.getLine(info) as TargetDataLine).also {
                it.open(RECORDING_FORMAT)
                it.start()
            }
        } catch (_: Exception) {
            throw voiceFailure(VoiceErrorCode.MICROPHONE_UNAVAILABLE, "The default microphone could not be opened.")
        }
        val path = if (temporaryDirectory == null) {
            Files.createTempFile("eve-planner-voice-", ".wav")
        } else {
            Files.createDirectories(temporaryDirectory)
            Files.createTempFile(temporaryDirectory, "eve-planner-voice-", ".wav")
        }
        writeFailure = null
        output = path
        line = target
        recordingThread = Thread({
            try {
                AudioInputStream(target).use { stream ->
                    AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile())
                }
            } catch (failure: Throwable) {
                if (line != null) writeFailure = failure
            }
        }, "voice-recorder").apply {
            isDaemon = true
            start()
        }
        return path
    }

    @Synchronized
    override fun stop(): Path {
        val path = output ?: throw voiceFailure(VoiceErrorCode.RECORDING_FAILED, "No recording is active.")
        val target = line ?: throw voiceFailure(VoiceErrorCode.RECORDING_FAILED, "No recording is active.")
        line = null
        target.stop()
        target.close()
        recordingThread?.join(RECORDING_JOIN_TIMEOUT_MILLIS)
        recordingThread = null
        output = null
        writeFailure?.let {
            Files.deleteIfExists(path)
            writeFailure = null
            throw voiceFailure(VoiceErrorCode.RECORDING_FAILED, "The recording could not be saved.")
        }
        if (!Files.isRegularFile(path) || Files.size(path) <= WAV_HEADER_BYTES) {
            Files.deleteIfExists(path)
            throw voiceFailure(VoiceErrorCode.RECORDING_FAILED, "The recording contains no audio.")
        }
        return path
    }

    @Synchronized
    override fun cancel() {
        val path = output
        val target = line
        line = null
        output = null
        runCatching { target?.stop() }
        runCatching { target?.close() }
        recordingThread?.join(RECORDING_JOIN_TIMEOUT_MILLIS)
        recordingThread = null
        path?.let { runCatching { Files.deleteIfExists(it) } }
    }

    override fun close() = cancel()

    companion object {
        private val RECORDING_FORMAT = AudioFormat(16_000f, 16, 1, true, false)
        private const val RECORDING_JOIN_TIMEOUT_MILLIS = 3_000L
        private const val WAV_HEADER_BYTES = 44L
    }
}

internal data class SpeechPackState(
    val installed: Boolean,
    val modelName: String = SPEECH_PACK_MODEL_NAME,
    val modelBytes: Long? = null,
    val helperBytes: Long? = null,
)

internal data class SpeechPackDescriptor(
    val modelName: String = SPEECH_PACK_MODEL_NAME,
    val modelFile: String = SPEECH_PACK_MODEL_FILE,
    val modelUri: URI = SPEECH_PACK_MODEL_URI,
    val modelSha256: String = SPEECH_PACK_MODEL_SHA256,
    val runtimeRelease: String = SPEECH_PACK_RUNTIME_RELEASE,
    val runtimeUri: URI = SPEECH_PACK_RUNTIME_URI,
    val runtimeSha256: String = SPEECH_PACK_RUNTIME_SHA256,
)

internal fun interface SpeechPackTransport {
    fun download(uri: URI, destination: Path)
}

internal class JavaSpeechPackTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : SpeechPackTransport {
    override fun download(uri: URI, destination: Path) {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(destination))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Speech Pack download failed with HTTP ${response.statusCode()}")
        }
    }
}

internal class SpeechPackManager(
    private val speechRoot: Path = defaultSpeechRoot(),
    private val transport: SpeechPackTransport = JavaSpeechPackTransport(),
    private val descriptor: SpeechPackDescriptor = SpeechPackDescriptor(),
) {
    val packDirectory: Path get() = speechRoot.resolve("pack")
    val modelPath: Path get() = packDirectory.resolve(descriptor.modelFile)
    val helperPath: Path get() = packDirectory.resolve("runtime").resolve("whisper-cli.exe")

    fun state(): SpeechPackState {
        val installed = Files.isRegularFile(modelPath) && Files.isRegularFile(helperPath) &&
            Files.isRegularFile(packDirectory.resolve(SPEECH_PACK_MANIFEST))
        return SpeechPackState(
            installed = installed,
            modelName = descriptor.modelName,
            modelBytes = modelPath.takeIf(Files::isRegularFile)?.let(Files::size),
            helperBytes = packDirectory.resolve("runtime").takeIf(Files::isDirectory)?.let(::directoryBytes),
        )
    }

    fun install() {
        Files.createDirectories(speechRoot)
        val work = speechRoot.resolve("install-${UUID.randomUUID()}")
        val runtimeArchive = work.resolve("runtime.zip")
        val modelDownload = work.resolve("${descriptor.modelFile}.download")
        val stagedPack = work.resolve("pack")
        try {
            Files.createDirectories(work)
            transport.download(descriptor.runtimeUri, runtimeArchive)
            verifySha256(runtimeArchive, descriptor.runtimeSha256)
            transport.download(descriptor.modelUri, modelDownload)
            verifySha256(modelDownload, descriptor.modelSha256)
            Files.createDirectories(stagedPack.resolve("runtime"))
            extractRuntime(runtimeArchive, stagedPack.resolve("runtime"))
            moveAtomically(modelDownload, stagedPack.resolve(descriptor.modelFile))
            writeManifest(stagedPack)
            verifyInstalledPack(stagedPack)
            replacePack(stagedPack)
        } finally {
            deleteTree(work)
        }
    }

    fun remove() = deleteTree(packDirectory)

    fun requireInstalled() {
        if (!state().installed) throw voiceFailure(
            VoiceErrorCode.VOICE_MODEL_UNAVAILABLE,
            "Local speech model is not installed.",
        )
    }

    private fun extractRuntime(archive: Path, destination: Path) {
        val requiredFiles = mutableSetOf(
            "whisper-cli.exe",
            "whisper.dll",
            "ggml.dll",
            "ggml-base.dll",
            "ggml-cpu.dll",
        )
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val name = Path.of(entry.name.replace('\\', '/')).fileName?.toString().orEmpty()
                val keep = !entry.isDirectory && name in requiredFiles
                if (keep) {
                    val target = destination.resolve(name).normalize()
                    require(target.parent == destination.normalize()) { "Invalid Speech Pack archive entry" }
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                    requiredFiles.remove(name)
                }
                zip.closeEntry()
            }
        }
        check(requiredFiles.isEmpty()) { "Speech Pack runtime is incomplete: ${requiredFiles.sorted()}" }
    }

    private fun writeManifest(stagedPack: Path) {
        val properties = Properties().apply {
            setProperty("formatVersion", "1")
            setProperty("runtime", descriptor.runtimeRelease)
            setProperty("runtimeSha256", descriptor.runtimeSha256)
            setProperty("model", descriptor.modelName)
            setProperty("modelSha256", descriptor.modelSha256)
        }
        Files.newOutputStream(stagedPack.resolve(SPEECH_PACK_MANIFEST)).use { properties.store(it, null) }
    }

    private fun verifyInstalledPack(stagedPack: Path) {
        check(Files.isRegularFile(stagedPack.resolve("runtime/whisper-cli.exe")))
        verifySha256(stagedPack.resolve(descriptor.modelFile), descriptor.modelSha256)
    }

    private fun replacePack(stagedPack: Path) {
        val backup = speechRoot.resolve("pack.backup-${UUID.randomUUID()}")
        if (Files.exists(packDirectory)) moveAtomically(packDirectory, backup)
        try {
            moveAtomically(stagedPack, packDirectory)
        } catch (failure: Throwable) {
            if (!Files.exists(packDirectory) && Files.exists(backup)) moveAtomically(backup, packDirectory)
            throw failure
        }
        runCatching { deleteTree(backup) }
    }
}

internal fun interface LocalTranscriber {
    suspend fun transcribe(wav: Path): String
}

internal class WhisperCppTranscriber(
    private val speechPack: SpeechPackManager,
    private val timeout: Duration = Duration.ofSeconds(90),
) : LocalTranscriber, SpeechToTextProvider {
    override val capability = SpeechProviderCapability(
        supportsStt = true,
        supportsTts = false,
        requiresApiKey = false,
        supportsVoiceSelection = false,
        supportsModelSelection = false,
    )

    override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig): SpeechTranscript {
        val temporary = audio.sourcePath == null
        val path = audio.sourcePath ?: Files.createTempFile("eve-planner-local-stt-", ".wav").also {
            Files.write(it, audio.wav)
        }
        return try {
            SpeechTranscript(transcribe(path))
        } finally {
            if (temporary) runCatching { Files.deleteIfExists(path) }
        }
    }

    override suspend fun transcribe(wav: Path): String = withContext(Dispatchers.IO) {
        speechPack.requireInstalled()
        val process = ProcessBuilder(
            speechPack.helperPath.toString(),
            "-m", speechPack.modelPath.toString(),
            "-f", wav.toString(),
            "-l", "auto",
            "-nt",
            "-np",
            "-ng",
        )
            .directory(speechPack.helperPath.parent.toFile())
            .redirectErrorStream(true)
            .start()
        try {
            val output = coroutineScope {
                val reader = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
                }
                try {
                    withTimeout(timeout.toMillis()) { process.onExit().awaitCancellable() }
                    reader.await()
                } catch (_: TimeoutCancellationException) {
                    process.destroyForcibly()
                    reader.cancel()
                    throw voiceFailure(VoiceErrorCode.TRANSCRIPTION_FAILED, "Local transcription timed out.")
                } catch (cancelled: CancellationException) {
                    process.destroyForcibly()
                    reader.cancel()
                    throw cancelled
                }
            }
            if (process.exitValue() != 0 || output.isBlank()) {
                throw voiceFailure(VoiceErrorCode.TRANSCRIPTION_FAILED, "Local transcription failed.")
            }
            output.lineSequence()
                .filterNot { it.startsWith("whisper_") || it.startsWith("ggml_") || it.startsWith("system_info:") }
                .joinToString(" ")
                .trim()
                .takeIf(String::isNotBlank)
                ?: throw voiceFailure(VoiceErrorCode.TRANSCRIPTION_FAILED, "Local transcription returned no text.")
        } catch (cancelled: CancellationException) {
            process.destroyForcibly()
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal interface SpeechSynthesizer {
    suspend fun synthesize(text: String, voice: String?, rate: Int, volume: Int): ByteArray
    suspend fun voices(): List<String>
}

internal class WindowsSpeechSynthesizer : SpeechSynthesizer, TextToSpeechProvider {
    override val capability = SpeechProviderCapability(
        supportsStt = false,
        supportsTts = true,
        requiresApiKey = false,
        supportsVoiceSelection = true,
        supportsModelSelection = false,
    )

    override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio = SynthesizedAudio(
        synthesize(text, config.voice, config.rate, config.volume),
    )

    override suspend fun synthesize(text: String, voice: String?, rate: Int, volume: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val textFile = Files.createTempFile("eve-planner-tts-", ".txt")
            val outputFile = Files.createTempFile("eve-planner-tts-", ".wav")
            try {
                Files.writeString(textFile, text, StandardCharsets.UTF_8)
                val environment = mapOf(
                    "EVE_TTS_TEXT" to textFile.toString(),
                    "EVE_TTS_OUTPUT" to outputFile.toString(),
                    "EVE_TTS_VOICE" to voice.orEmpty(),
                    "EVE_TTS_RATE" to rate.coerceIn(-10, 10).toString(),
                    "EVE_TTS_VOLUME" to volume.coerceIn(0, 100).toString(),
                )
                runPowerShell(SYNTHESIZE_SCRIPT, environment)
                Files.readAllBytes(outputFile).takeIf { it.size > 44 }
                    ?: throw voiceFailure(VoiceErrorCode.SYNTHESIS_FAILED, "Windows speech synthesis returned no audio.")
            } finally {
                Files.deleteIfExists(textFile)
                Files.deleteIfExists(outputFile)
            }
        }

    override suspend fun voices(): List<String> = withContext(Dispatchers.IO) {
        val output = Files.createTempFile("eve-planner-voices-", ".txt")
        try {
            runPowerShell(LIST_VOICES_SCRIPT, mapOf("EVE_TTS_OUTPUT" to output.toString()))
            Files.readAllLines(output, StandardCharsets.UTF_8).map(String::trim).filter(String::isNotBlank)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private suspend fun runPowerShell(script: String, environment: Map<String, String>) {
        val process = ProcessBuilder(
            "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-Command", script,
        ).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
        try {
            withTimeout(30_000) { process.onExit().awaitCancellable() }
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (_: TimeoutCancellationException) {
            process.destroyForcibly()
            throw voiceFailure(VoiceErrorCode.VOICE_TIMEOUT, "Windows speech synthesis timed out.")
        } catch (cancelled: CancellationException) {
            process.destroyForcibly()
            throw cancelled
        }
        if (process.exitValue() != 0) {
            throw voiceFailure(VoiceErrorCode.SYNTHESIS_FAILED, "Windows speech synthesis is unavailable.")
        }
    }

    companion object {
        private const val SYNTHESIZE_SCRIPT = """
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            try {
              ${'$'}voice = ${'$'}env:EVE_TTS_VOICE
              if (${'$'}voice) { ${'$'}s.SelectVoice(${'$'}voice) }
              ${'$'}s.Rate = [int]${'$'}env:EVE_TTS_RATE
              ${'$'}s.Volume = [int]${'$'}env:EVE_TTS_VOLUME
              ${'$'}s.SetOutputToWaveFile(${'$'}env:EVE_TTS_OUTPUT)
              ${'$'}text = [IO.File]::ReadAllText(${'$'}env:EVE_TTS_TEXT, [Text.Encoding]::UTF8)
              ${'$'}s.Speak(${'$'}text)
            } finally { ${'$'}s.Dispose() }
        """
        private const val LIST_VOICES_SCRIPT = """
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            try {
              ${'$'}names = @(${'$'}s.GetInstalledVoices() | ForEach-Object { ${'$'}_.VoiceInfo.Name })
              [IO.File]::WriteAllLines(${'$'}env:EVE_TTS_OUTPUT, ${'$'}names, [Text.UTF8Encoding]::new(${'$'}false))
            } finally { ${'$'}s.Dispose() }
        """
    }
}

internal interface VoiceAudioPlayer : AutoCloseable {
    fun play(wav: ByteArray, onFinished: () -> Unit = {})
    fun play(wav: ByteArray, context: VoiceAudioPlaybackContext, onFinished: () -> Unit = {}) =
        play(wav, onFinished)
    fun stop()
}

internal data class VoiceAudioPlaybackContext(
    val playbackInstanceId: String,
    val diagnosticSink: (AudioPlayerDebugSnapshot) -> Unit = {},
)

internal data class AudioPlayerDebugSnapshot(
    val phase: String,
    val api: String,
    val playerInstanceId: String,
    val playbackInstanceId: String,
    val clipInstanceId: String,
    val wavByteLength: Int,
    val wavSha256: String,
    val riffDeclaredSize: Long?,
    val dataDeclaredSize: Long?,
    val sourceFrameLength: Long,
    val pcmFormat: String,
    val pcmByteLength: Int,
    val pcmSha256: String,
    val sourceStreamOpenCount: Int,
    val sourceStreamResetCount: Int,
    val pcmReadCallCount: Int,
    val pcmReadTotalBytes: Int,
    val clipPcmTransferCount: Int,
    val clipPcmTransferByteCount: Int,
    val clipOpenCount: Int,
    val clipStartCount: Int,
    val clipLoopCount: Int,
    val clipStopCallCount: Int,
    val clipStopEventCount: Int,
    val clipCloseCount: Int,
    val clipFrameLength: Int,
    val clipFramePosition: Int,
    val activePlayerCount: Int,
    val activeClipCount: Int,
    val failure: String? = null,
) {
    fun toSafeLogMessage(): String = buildString {
        append("AudioPlayer DEBUG: phase=").append(phase)
        append(", api=").append(api)
        append(", playerInstanceId=").append(playerInstanceId)
        append(", playbackInstanceId=").append(playbackInstanceId)
        append(", clipInstanceId=").append(clipInstanceId)
        append(", wavBytes=").append(wavByteLength)
        append(", wavSha256=").append(wavSha256)
        append(", riffDeclaredSize=").append(riffDeclaredSize)
        append(", dataDeclaredSize=").append(dataDeclaredSize)
        append(", sourceFrameLength=").append(sourceFrameLength)
        append(", pcmBytes=").append(pcmByteLength)
        append(", pcmSha256=").append(pcmSha256)
        append(", pcmReads=").append(pcmReadCallCount)
        append(", pcmReadBytes=").append(pcmReadTotalBytes)
        append(", clipTransferCount=").append(clipPcmTransferCount)
        append(", clipTransferBytes=").append(clipPcmTransferByteCount)
        append(", open/start/loop/stopCall/stopEvent/close=")
            .append(clipOpenCount).append('/')
            .append(clipStartCount).append('/')
            .append(clipLoopCount).append('/')
            .append(clipStopCallCount).append('/')
            .append(clipStopEventCount).append('/')
            .append(clipCloseCount)
        append(", framePosition/frameLength=").append(clipFramePosition).append('/').append(clipFrameLength)
        append(", activePlayers/activeClips=").append(activePlayerCount).append('/').append(activeClipCount)
        failure?.let { append(", failure=").append(it) }
    }
}

internal data class DecodedPcmAudio(
    val format: AudioFormat,
    val bytes: ByteArray,
    val sourceFrameLength: Long,
    val sourceStreamOpenCount: Int,
    val sourceStreamResetCount: Int,
    val readCallCount: Int,
    val riffDeclaredSize: Long?,
    val dataDeclaredSize: Long?,
)

internal interface JavaSoundClipHandle {
    val frameLength: Int
    val framePosition: Int
    fun onStop(listener: () -> Unit)
    fun open(format: AudioFormat, pcm: ByteArray, offset: Int, length: Int)
    fun start()
    fun stop()
    fun close()
}

internal fun interface JavaSoundClipFactory {
    fun create(): JavaSoundClipHandle
}

private class SystemJavaSoundClipHandle(
    private val delegate: Clip = AudioSystem.getClip(),
) : JavaSoundClipHandle {
    override val frameLength: Int get() = delegate.frameLength
    override val framePosition: Int get() = delegate.framePosition

    override fun onStop(listener: () -> Unit) {
        delegate.addLineListener { event -> if (event.type == LineEvent.Type.STOP) listener() }
    }

    override fun open(format: AudioFormat, pcm: ByteArray, offset: Int, length: Int) =
        delegate.open(format, pcm, offset, length)

    override fun start() = delegate.start()
    override fun stop() = delegate.stop()
    override fun close() = delegate.close()
}

internal class JavaSoundVoiceAudioPlayer(
    private val clipFactory: JavaSoundClipFactory = JavaSoundClipFactory { SystemJavaSoundClipHandle() },
    private val diagnosticSink: (AudioPlayerDebugSnapshot) -> Unit = {},
) : VoiceAudioPlayer {
    private val playerInstanceId = "java-sound-player-${UUID.randomUUID()}"
    private var active: ActivePlayback? = null

    @Synchronized
    override fun play(wav: ByteArray, onFinished: () -> Unit) {
        play(
            wav = wav,
            context = VoiceAudioPlaybackContext("standalone-${UUID.randomUUID()}"),
            onFinished = onFinished,
        )
    }

    @Synchronized
    override fun play(wav: ByteArray, context: VoiceAudioPlaybackContext, onFinished: () -> Unit) {
        stopActive()
        val decoded = try {
            decodeWavToPcm(wav)
        } catch (failure: Exception) {
            throw playbackFailure(failure)
        }
        val clipHandle = try {
            clipFactory.create()
        } catch (failure: Exception) {
            throw playbackFailure(failure)
        }
        val state = ActivePlayback(
            context = context,
            clipInstanceId = "java-sound-clip-${UUID.randomUUID()}",
            wav = wav,
            decoded = decoded,
            clip = clipHandle,
            onFinished = onFinished,
        )
        active = state
        try {
            state.clip.onStop { clipStopped(state) }
            state.clipOpenCount++
            state.clipPcmTransferCount++
            state.clipPcmTransferByteCount += decoded.bytes.size
            state.clip.open(decoded.format, decoded.bytes, 0, decoded.bytes.size)
            state.registerActive()
            emit(state, "OPENED")
            state.clipStartCount++
            state.clip.start()
            emit(state, "STARTED")
        } catch (failure: Exception) {
            state.failure = "${failure::class.simpleName}: ${failure.message.orEmpty()}".trim()
            finish(state, invokeFinished = false, phase = "FAILED")
            throw playbackFailure(failure)
        }
    }

    @Synchronized
    override fun stop() {
        stopActive()
    }

    override fun close() = stop()

    fun playLocalDiagnosticWav(path: Path, onFinished: () -> Unit = {}) {
        play(Files.readAllBytes(path), onFinished)
    }

    private fun clipStopped(state: ActivePlayback) {
        var callback: (() -> Unit)? = null
        synchronized(this) {
            state.clipStopEventCount++
            emit(state, "STOP_EVENT")
            if (active === state && state.clip.framePosition >= state.clip.frameLength) {
                callback = finish(state, invokeFinished = true, phase = "FINISHED")
            }
        }
        callback?.invoke()
    }

    private fun stopActive() {
        val state = active ?: return
        if (!state.finished.compareAndSet(false, true)) return
        state.clipStopCallCount++
        runCatching { state.clip.stop() }
        closeClip(state)
        if (active === state) active = null
        state.unregisterActive()
        emit(state, "STOPPED")
    }

    private fun finish(state: ActivePlayback, invokeFinished: Boolean, phase: String): (() -> Unit)? {
        if (!state.finished.compareAndSet(false, true)) return null
        closeClip(state)
        if (active === state) active = null
        state.unregisterActive()
        emit(state, phase)
        return state.onFinished.takeIf { invokeFinished }
    }

    private fun closeClip(state: ActivePlayback) {
        state.captureFramePosition()
        state.clipCloseCount++
        runCatching { state.clip.close() }
    }

    private fun emit(state: ActivePlayback, phase: String) {
        state.captureFramePosition()
        val snapshot = AudioPlayerDebugSnapshot(
            phase = phase,
            api = "javax.sound.sampled.Clip",
            playerInstanceId = playerInstanceId,
            playbackInstanceId = state.context.playbackInstanceId,
            clipInstanceId = state.clipInstanceId,
            wavByteLength = state.wav.size,
            wavSha256 = sha256Hex(state.wav),
            riffDeclaredSize = state.decoded.riffDeclaredSize,
            dataDeclaredSize = state.decoded.dataDeclaredSize,
            sourceFrameLength = state.decoded.sourceFrameLength,
            pcmFormat = state.decoded.format.toString(),
            pcmByteLength = state.decoded.bytes.size,
            pcmSha256 = sha256Hex(state.decoded.bytes),
            sourceStreamOpenCount = state.decoded.sourceStreamOpenCount,
            sourceStreamResetCount = state.decoded.sourceStreamResetCount,
            pcmReadCallCount = state.decoded.readCallCount,
            pcmReadTotalBytes = state.decoded.bytes.size,
            clipPcmTransferCount = state.clipPcmTransferCount,
            clipPcmTransferByteCount = state.clipPcmTransferByteCount,
            clipOpenCount = state.clipOpenCount,
            clipStartCount = state.clipStartCount,
            clipLoopCount = state.clipLoopCount,
            clipStopCallCount = state.clipStopCallCount,
            clipStopEventCount = state.clipStopEventCount,
            clipCloseCount = state.clipCloseCount,
            clipFrameLength = state.lastFrameLength,
            clipFramePosition = state.lastFramePosition,
            activePlayerCount = ACTIVE_PLAYBACKS.get(),
            activeClipCount = ACTIVE_CLIPS.get(),
            failure = state.failure,
        )
        runCatching { diagnosticSink(snapshot) }
        runCatching { state.context.diagnosticSink(snapshot) }
    }

    private fun playbackFailure(failure: Exception): VoiceException =
        voiceFailure(VoiceErrorCode.AUDIO_PLAYBACK_FAILED, "Audio playback failed.").also {
            it.initCause(failure)
        }

    private data class ActivePlayback(
        val context: VoiceAudioPlaybackContext,
        val clipInstanceId: String,
        val wav: ByteArray,
        val decoded: DecodedPcmAudio,
        val clip: JavaSoundClipHandle,
        val onFinished: () -> Unit,
        val finished: AtomicBoolean = AtomicBoolean(),
        var registeredActive: Boolean = false,
        var clipPcmTransferCount: Int = 0,
        var clipPcmTransferByteCount: Int = 0,
        var clipOpenCount: Int = 0,
        var clipStartCount: Int = 0,
        var clipLoopCount: Int = 0,
        var clipStopCallCount: Int = 0,
        var clipStopEventCount: Int = 0,
        var clipCloseCount: Int = 0,
        var failure: String? = null,
        var lastFrameLength: Int = 0,
        var lastFramePosition: Int = 0,
    ) {
        fun captureFramePosition() {
            runCatching { clip.frameLength }.getOrNull()?.takeIf { it > 0 }?.let { lastFrameLength = it }
            runCatching { clip.framePosition }.getOrNull()?.let { position ->
                if (position > lastFramePosition || lastFramePosition == 0) lastFramePosition = position
            }
        }

        fun registerActive() {
            if (registeredActive) return
            registeredActive = true
            ACTIVE_PLAYBACKS.incrementAndGet()
            ACTIVE_CLIPS.incrementAndGet()
        }

        fun unregisterActive() {
            if (!registeredActive) return
            registeredActive = false
            ACTIVE_PLAYBACKS.decrementAndGet()
            ACTIVE_CLIPS.decrementAndGet()
        }
    }

    private companion object {
        val ACTIVE_PLAYBACKS = AtomicInteger()
        val ACTIVE_CLIPS = AtomicInteger()
    }
}

internal fun decodeWavToPcm(wav: ByteArray): DecodedPcmAudio {
    val source = AudioSystem.getAudioInputStream(BufferedInputStream(wav.inputStream()))
    val playbackFormat = source.format.toClipPcmFormat()
    val pcmStream = if (playbackFormat.matches(source.format)) {
        source
    } else {
        AudioSystem.getAudioInputStream(playbackFormat, source)
    }
    return try {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var reads = 0
        while (true) {
            val count = pcmStream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            reads++
        }
        val pcm = output.toByteArray()
        require(pcm.isNotEmpty()) { "WAV contains no PCM audio" }
        require(playbackFormat.frameSize > 0 && pcm.size % playbackFormat.frameSize == 0) {
            "PCM byte length is not aligned to the audio frame size"
        }
        DecodedPcmAudio(
            format = playbackFormat,
            bytes = pcm,
            sourceFrameLength = source.frameLength,
            sourceStreamOpenCount = 1,
            sourceStreamResetCount = 0,
            readCallCount = reads,
            riffDeclaredSize = wav.riffUInt32(4),
            dataDeclaredSize = wav.takeIf { it.ascii(36, 4) == "data" }?.riffUInt32(40),
        )
    } finally {
        runCatching { if (pcmStream !== source) pcmStream.close() }
        runCatching { source.close() }
    }
}

private fun AudioFormat.toClipPcmFormat(): AudioFormat {
    if (encoding == AudioFormat.Encoding.PCM_SIGNED || encoding == AudioFormat.Encoding.PCM_UNSIGNED) return this
    val channels = channels.coerceAtLeast(1)
    return AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        sampleRate,
        16,
        channels,
        channels * 2,
        sampleRate,
        false,
    )
}

private fun ByteArray.riffUInt32(offset: Int): Long? {
    if (size < offset + 4 || ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE") return null
    return (this[offset].toLong() and 0xff) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)
}

private fun ByteArray.ascii(offset: Int, length: Int): String =
    if (size >= offset + length) copyOfRange(offset, offset + length).toString(Charsets.US_ASCII) else ""

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun assistantMarkdownToSpeech(markdown: String): String {
    return TtsTextNormalizer.normalize(markdown)
}

internal fun defaultSpeechRoot(environment: (String) -> String? = System::getenv): Path {
    val localAppData = environment("LOCALAPPDATA")?.trim()?.takeIf(String::isNotBlank)
    return if (localAppData != null) {
        Path.of(localAppData, "EVE Static Map Planner", "speech")
    } else {
        Path.of(System.getProperty("user.home"), "AppData", "Local", "EVE Static Map Planner", "speech")
    }
}

private fun verifySha256(path: Path, expected: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach { count ->
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(actual.equals(expected, ignoreCase = true)) { "Speech Pack checksum verification failed" }
}

private fun moveAtomically(source: Path, destination: Path) {
    Files.createDirectories(destination.parent)
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun directoryBytes(path: Path): Long = Files.walk(path).use { paths ->
    paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
}

private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private fun voiceFailure(code: VoiceErrorCode, message: String) = VoiceException(code, message)

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    whenComplete { value, failure ->
        when {
            failure == null -> continuation.resume(value)
            failure is CompletionException && failure.cause != null -> continuation.resumeWithException(failure.cause!!)
            else -> continuation.resumeWithException(failure)
        }
    }
}

internal const val SPEECH_PACK_MODEL_NAME = "whisper.cpp multilingual base"
internal const val SPEECH_PACK_MODEL_FILE = "ggml-base.bin"
internal const val SPEECH_PACK_RUNTIME_RELEASE = "v1.7.6"
internal const val SPEECH_PACK_RUNTIME_SHA256 = "0d2eca299c248f965bd0341bcb219db4b433c7f0c0ce2200d4df85765e8156a9"
internal const val SPEECH_PACK_MODEL_SHA256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
internal val SPEECH_PACK_RUNTIME_URI: URI = URI.create(
    "https://github.com/ggml-org/whisper.cpp/releases/download/$SPEECH_PACK_RUNTIME_RELEASE/whisper-bin-x64.zip",
)
internal val SPEECH_PACK_MODEL_URI: URI = URI.create(
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$SPEECH_PACK_MODEL_FILE",
)
private const val SPEECH_PACK_MANIFEST = "speech-pack.properties"
internal const val MAX_RECORDING_SECONDS = 60L
