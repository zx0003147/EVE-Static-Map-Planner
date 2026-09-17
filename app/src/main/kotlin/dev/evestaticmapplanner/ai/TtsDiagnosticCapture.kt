package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.ApplicationDirectories
import dev.evestaticmapplanner.embeddedai.SpeechSynthesisConfig
import dev.evestaticmapplanner.embeddedai.SynthesizedAudio
import dev.evestaticmapplanner.embeddedai.TtsProviderHttpEvent
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Opt-in runtime evidence capture for the TTS pipeline. It is intentionally not a persisted user
 * preference: enable it only for a diagnostic run with EVE_TTS_DIAGNOSTIC_MODE=true (or the
 * eve.tts.diagnostic.mode JVM property). The default diagnostic policy suppresses playback so the
 * provider result can be inspected independently from Java Sound. The explicit value "capture"
 * keeps playback enabled for a second-stage player comparison while retaining the same evidence.
 */
internal class TtsDiagnosticCapture(
    val root: Path,
    val isolatePlayback: Boolean = true,
    private val now: () -> Instant = Instant::now,
) {
    private val messages = linkedMapOf<String, MessageEvidence>()
    private val json = Json { prettyPrint = true }

    @Synchronized
    fun recordSynthesisQueueEnqueue(
        chunk: AssistantSpeechChunk,
        provider: String,
        config: SpeechSynthesisConfig,
        enqueueOrdinal: Long,
    ) {
        val evidence = evidence(chunk, provider, config)
        evidence.synthesisQueueEnqueueCount++
        evidence.synthesisQueueEnqueueOrdinal = enqueueOrdinal
        if (evidence.synthesisQueueEnqueueCount > 1) evidence.violations += "chunk enqueued for synthesis more than once"
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordSynthesisStarted(chunk: AssistantSpeechChunk, attempt: Int) {
        val evidence = requireEvidence(chunk)
        val sha = sha256(chunk.text.toByteArray(StandardCharsets.UTF_8))
        if (sha != evidence.textSha256) evidence.violations += "chunk text changed after enqueue"
        evidence.synthesisAttemptCount++
        evidence.synthesisAttempts += attempt
        evidence.synthesisStartedAt += now().toString()
        writeTextFile(evidence, chunk.text)
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordProviderHttpEvent(chunk: AssistantSpeechChunk, event: TtsProviderHttpEvent) {
        val evidence = requireEvidence(chunk)
        when (event.type) {
            dev.evestaticmapplanner.embeddedai.TtsProviderHttpEventType.REQUEST_STARTED -> evidence.httpRequestCount++
            dev.evestaticmapplanner.embeddedai.TtsProviderHttpEventType.RESPONSE_RECEIVED -> evidence.httpResponseCount++
        }
        evidence.httpEvents += ProviderHttpEvidence(
            timestamp = now().toString(),
            stage = event.stage.name,
            type = event.type.name,
            statusCode = event.statusCode,
        )
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordSynthesisSucceeded(chunk: AssistantSpeechChunk, audio: SynthesizedAudio) {
        val evidence = requireEvidence(chunk)
        evidence.successfulSynthesisCount++
        evidence.synthesisEndedAt += now().toString()
        evidence.audioByteLength = audio.wav.size
        evidence.audioSha256 = sha256(audio.wav)
        if (evidence.successfulSynthesisCount > 1) {
            evidence.violations += "chunk produced more than one successful audio result"
        }
        writeAudioFile(evidence, audio)
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordSynthesisFailed(chunk: AssistantSpeechChunk) {
        requireEvidence(chunk).synthesisEndedAt += now().toString()
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordPlaybackSuppressed(chunk: AssistantSpeechChunk) {
        requireEvidence(chunk).playbackSuppressed = true
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordPlaybackEnqueued(
        chunk: AssistantSpeechChunk,
        audio: ByteArray,
        playOrdinal: Long,
        playbackInstanceId: String,
    ) {
        val evidence = requireEvidence(chunk)
        evidence.audioPlaybackEnqueueCount++
        evidence.playOrdinals += playOrdinal
        evidence.playbackInstanceIds += playbackInstanceId
        evidence.playbackAudioSha256 = sha256(audio)
        if (evidence.audioPlaybackEnqueueCount > 1) evidence.violations += "audio enqueued for playback more than once"
        if (evidence.audioSha256 != null && evidence.playbackAudioSha256 != evidence.audioSha256) {
            evidence.violations += "player bytes differ from provider result"
        }
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordPlaybackStarted(chunk: AssistantSpeechChunk) {
        val evidence = requireEvidence(chunk)
        evidence.playStartCount++
        evidence.playStartedAt += now().toString()
        if (evidence.playStartCount > 1) evidence.violations += "player started the same chunk more than once"
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordPlaybackEnded(chunk: AssistantSpeechChunk) {
        val evidence = requireEvidence(chunk)
        evidence.playEndCount++
        evidence.playEndedAt += now().toString()
        if (evidence.playEndCount > 1) evidence.violations += "player completed the same chunk more than once"
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordAudioPlayerDebug(chunk: AssistantSpeechChunk, snapshot: AudioPlayerDebugSnapshot) {
        val evidence = requireEvidence(chunk)
        evidence.audioPlayerEvents += snapshot
        if (snapshot.playbackInstanceId !in evidence.playbackInstanceIds) {
            evidence.violations += "concrete player reported an unknown playback instance"
        }
        if (snapshot.phase == "FINISHED") {
            if (snapshot.clipPcmTransferCount != 1) {
                evidence.violations += "PCM was transferred to Clip more than once"
            }
            if (snapshot.clipPcmTransferByteCount != snapshot.pcmByteLength) {
                evidence.violations += "PCM bytes transferred to Clip differ from decoded PCM length"
            }
            if (snapshot.clipOpenCount != 1 || snapshot.clipStartCount != 1 || snapshot.clipLoopCount != 0) {
                evidence.violations += "Clip lifecycle was not exactly open=1 start=1 loop=0"
            }
            if (snapshot.activePlayerCount != 0 || snapshot.activeClipCount != 0) {
                evidence.violations += "concrete player or Clip remained active after completion"
            }
        }
        writeManifest(chunk.messageId)
    }

    @Synchronized
    fun recordVerificationTranscript(messageId: String, chunkId: String, transcript: String) {
        val message = messages[messageId] ?: error("Unknown diagnostic message: $messageId")
        val evidence = message.chunks[chunkId] ?: error("Unknown diagnostic chunk: $chunkId")
        val path = message.directory.resolve("${evidence.fileBase}-transcript.txt")
        Files.writeString(
            path,
            transcript,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        evidence.verificationTranscriptFile = path.fileName.toString()
        evidence.verificationTranscriptSha256 = sha256(transcript.toByteArray(StandardCharsets.UTF_8))
        writeManifest(messageId)
    }

    @Synchronized
    fun snapshot(messageId: String): List<TtsDiagnosticChunkSnapshot> = messages[messageId]
        ?.chunks
        ?.values
        ?.sortedBy(ChunkEvidence::sequence)
        ?.map { evidence ->
            TtsDiagnosticChunkSnapshot(
                chunkId = evidence.chunkId,
                sequence = evidence.sequence,
                rawStartOffset = evidence.rawStartOffset,
                rawEndOffset = evidence.rawEndOffset,
                normalizedLength = evidence.normalizedLength,
                textSha256 = evidence.textSha256,
                synthesisAttemptCount = evidence.synthesisAttemptCount,
                successfulSynthesisCount = evidence.successfulSynthesisCount,
                httpRequestCount = evidence.httpRequestCount,
                httpResponseCount = evidence.httpResponseCount,
                audioByteLength = evidence.audioByteLength,
                audioSha256 = evidence.audioSha256,
                synthesisQueueEnqueueCount = evidence.synthesisQueueEnqueueCount,
                audioPlaybackEnqueueCount = evidence.audioPlaybackEnqueueCount,
                playStartCount = evidence.playStartCount,
                playEndCount = evidence.playEndCount,
                textPath = messagePath(messageId).resolve("${evidence.fileBase}.txt"),
                audioPath = evidence.audioFile?.let(messagePath(messageId)::resolve),
                violations = evidence.violations.toList(),
            )
        }
        .orEmpty()

    @Synchronized
    fun messagePath(messageId: String): Path = messages[messageId]?.directory
        ?: root.resolve(safeIdentifier(messageId))

    private fun evidence(
        chunk: AssistantSpeechChunk,
        provider: String,
        config: SpeechSynthesisConfig,
    ): ChunkEvidence {
        val message = messages.getOrPut(chunk.messageId) {
            val directory = root.resolve(safeIdentifier(chunk.messageId))
            Files.createDirectories(directory)
            MessageEvidence(chunk.messageId, directory)
        }
        return message.chunks.getOrPut(chunk.chunkId) {
            ChunkEvidence(
                messageId = chunk.messageId,
                chunkId = chunk.chunkId,
                sequence = chunk.sequence,
                rawStartOffset = chunk.rawStartOffset,
                rawEndOffset = chunk.rawEndOffset,
                normalizedLength = chunk.text.codePointCount(0, chunk.text.length),
                textSha256 = sha256(chunk.text.toByteArray(StandardCharsets.UTF_8)),
                text = chunk.text,
                provider = provider,
                model = config.model,
                voice = config.voice,
                fileBase = "chunk-${chunk.sequence.toString().padStart(4, '0')}",
            )
        }.also { existing ->
            if (existing.text != chunk.text || existing.rawStartOffset != chunk.rawStartOffset ||
                existing.rawEndOffset != chunk.rawEndOffset
            ) {
                existing.violations += "chunk identity was reused with different text or raw offsets"
            }
        }
    }

    private fun requireEvidence(chunk: AssistantSpeechChunk): ChunkEvidence =
        messages[chunk.messageId]?.chunks?.get(chunk.chunkId)
            ?: error("TTS diagnostic evidence was not initialized for ${chunk.chunkId}")

    private fun writeTextFile(evidence: ChunkEvidence, text: String) {
        val path = messagePath(evidence.messageId).resolve("${evidence.fileBase}.txt")
        if (Files.exists(path)) {
            if (Files.readString(path, StandardCharsets.UTF_8) != text) {
                evidence.violations += "diagnostic text file content changed"
            }
            return
        }
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
    }

    private fun writeAudioFile(evidence: ChunkEvidence, audio: SynthesizedAudio) {
        val extension = audioExtension(audio.contentType)
        val fileName = "${evidence.fileBase}-audio.$extension"
        val path = messagePath(evidence.messageId).resolve(fileName)
        if (Files.exists(path)) {
            if (sha256(Files.readAllBytes(path)) != evidence.audioSha256) {
                evidence.violations += "diagnostic audio file content changed"
            }
        } else {
            Files.write(path, audio.wav, StandardOpenOption.CREATE_NEW)
        }
        evidence.audioFile = fileName
    }

    private fun writeManifest(messageId: String) {
        val message = messages[messageId] ?: return
        val chunks = message.chunks.values.sortedBy(ChunkEvidence::sequence)
        val manifest = buildJsonObject {
            put("messageId", message.messageId)
            put("diagnosticMode", if (isolatePlayback) "ISOLATED_CAPTURE" else "CAPTURE_WITH_PLAYBACK")
            put("updatedAt", now().toString())
            put("chunks", buildJsonArray {
                chunks.forEachIndexed { index, evidence ->
                    val previous = chunks.getOrNull(index - 1)
                    add(buildJsonObject {
                        put("messageId", evidence.messageId)
                        put("chunkId", evidence.chunkId)
                        put("sequence", evidence.sequence)
                        put("rawStartOffset", evidence.rawStartOffset)
                        put("rawEndOffset", evidence.rawEndOffset)
                        put("normalizedLength", evidence.normalizedLength)
                        put("textSha256", evidence.textSha256)
                        put("textFile", "${evidence.fileBase}.txt")
                        put("exactTextOverlapWithPreviousCodePoints", previous?.let {
                            exactSuffixPrefixOverlap(it.text, evidence.text)
                        } ?: 0)
                        put("synthesisAttemptCount", evidence.synthesisAttemptCount)
                        put("synthesisAttempts", buildJsonArray { evidence.synthesisAttempts.forEach(::add) })
                        put("successfulSynthesisCount", evidence.successfulSynthesisCount)
                        put("synthesisStartedAt", buildJsonArray { evidence.synthesisStartedAt.forEach(::add) })
                        put("synthesisEndedAt", buildJsonArray { evidence.synthesisEndedAt.forEach(::add) })
                        put("provider", evidence.provider)
                        evidence.model?.let { put("model", it) }
                        evidence.voice?.let { put("voice", it) }
                        put("httpRequestCount", evidence.httpRequestCount)
                        put("httpResponseCount", evidence.httpResponseCount)
                        put("httpEvents", buildJsonArray {
                            evidence.httpEvents.forEach { event ->
                                add(buildJsonObject {
                                    put("timestamp", event.timestamp)
                                    put("stage", event.stage)
                                    put("type", event.type)
                                    event.statusCode?.let { put("statusCode", it) }
                                })
                            }
                        })
                        evidence.audioByteLength?.let { put("audioByteLength", it) }
                        evidence.audioSha256?.let { put("audioSha256", it) }
                        evidence.audioFile?.let { put("audioFile", it) }
                        put("synthesisQueueEnqueueCount", evidence.synthesisQueueEnqueueCount)
                        evidence.synthesisQueueEnqueueOrdinal?.let { put("synthesisQueueEnqueueOrdinal", it) }
                        put("audioPlaybackEnqueueCount", evidence.audioPlaybackEnqueueCount)
                        put("playOrdinals", buildJsonArray { evidence.playOrdinals.forEach(::add) })
                        put("playbackInstanceIds", buildJsonArray { evidence.playbackInstanceIds.forEach(::add) })
                        evidence.playbackAudioSha256?.let { put("playbackAudioSha256", it) }
                        put("playStartCount", evidence.playStartCount)
                        put("playEndCount", evidence.playEndCount)
                        put("playStartedAt", buildJsonArray { evidence.playStartedAt.forEach(::add) })
                        put("playEndedAt", buildJsonArray { evidence.playEndedAt.forEach(::add) })
                        put("playbackSuppressed", evidence.playbackSuppressed)
                        put("audioPlayerEvents", buildJsonArray {
                            evidence.audioPlayerEvents.forEach { event -> add(audioPlayerEventJson(event)) }
                        })
                        evidence.audioPlayerEvents.lastOrNull()?.let { final ->
                            put("concretePlayerApi", final.api)
                            put("concretePlayerInstanceCount", evidence.audioPlayerEvents
                                .map(AudioPlayerDebugSnapshot::playerInstanceId).distinct().size)
                            put("clipInstanceCount", evidence.audioPlayerEvents
                                .map(AudioPlayerDebugSnapshot::clipInstanceId).distinct().size)
                            put("pcmByteLength", final.pcmByteLength)
                            put("pcmSha256", final.pcmSha256)
                            put("clipPcmTransferCount", final.clipPcmTransferCount)
                            put("clipPcmTransferByteCount", final.clipPcmTransferByteCount)
                            put("clipOpenCount", final.clipOpenCount)
                            put("clipStartCount", final.clipStartCount)
                            put("clipLoopCount", final.clipLoopCount)
                            put("clipStopCallCount", final.clipStopCallCount)
                            put("clipStopEventCount", final.clipStopEventCount)
                            put("clipCloseCount", final.clipCloseCount)
                            put("activePlayerCount", final.activePlayerCount)
                            put("activeClipCount", final.activeClipCount)
                        }
                        evidence.verificationTranscriptFile?.let { put("verificationTranscriptFile", it) }
                        evidence.verificationTranscriptSha256?.let { put("verificationTranscriptSha256", it) }
                        put("violations", buildJsonArray { evidence.violations.distinct().forEach(::add) })
                    })
                }
            })
        }
        val target = message.directory.resolve("manifest.json")
        val temporary = message.directory.resolve(".manifest-${UUID.randomUUID()}.tmp")
        Files.writeString(
            temporary,
            json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), manifest),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
        )
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class MessageEvidence(
        val messageId: String,
        val directory: Path,
        val chunks: LinkedHashMap<String, ChunkEvidence> = linkedMapOf(),
    )

    private data class ChunkEvidence(
        val messageId: String,
        val chunkId: String,
        val sequence: Int,
        val rawStartOffset: Int,
        val rawEndOffset: Int,
        val normalizedLength: Int,
        val textSha256: String,
        val text: String,
        val provider: String,
        val model: String?,
        val voice: String?,
        val fileBase: String,
        var synthesisQueueEnqueueCount: Int = 0,
        var synthesisQueueEnqueueOrdinal: Long? = null,
        var synthesisAttemptCount: Int = 0,
        val synthesisAttempts: MutableList<Int> = mutableListOf(),
        val synthesisStartedAt: MutableList<String> = mutableListOf(),
        val synthesisEndedAt: MutableList<String> = mutableListOf(),
        var successfulSynthesisCount: Int = 0,
        var httpRequestCount: Int = 0,
        var httpResponseCount: Int = 0,
        val httpEvents: MutableList<ProviderHttpEvidence> = mutableListOf(),
        var audioByteLength: Int? = null,
        var audioSha256: String? = null,
        var audioFile: String? = null,
        var audioPlaybackEnqueueCount: Int = 0,
        val playOrdinals: MutableList<Long> = mutableListOf(),
        val playbackInstanceIds: MutableList<String> = mutableListOf(),
        var playbackAudioSha256: String? = null,
        var playStartCount: Int = 0,
        var playEndCount: Int = 0,
        val playStartedAt: MutableList<String> = mutableListOf(),
        val playEndedAt: MutableList<String> = mutableListOf(),
        var playbackSuppressed: Boolean = false,
        val audioPlayerEvents: MutableList<AudioPlayerDebugSnapshot> = mutableListOf(),
        var verificationTranscriptFile: String? = null,
        var verificationTranscriptSha256: String? = null,
        val violations: MutableList<String> = mutableListOf(),
    )

    private data class ProviderHttpEvidence(
        val timestamp: String,
        val stage: String,
        val type: String,
        val statusCode: Int?,
    )

    private companion object {
        fun audioPlayerEventJson(event: AudioPlayerDebugSnapshot) = buildJsonObject {
            put("phase", event.phase)
            put("api", event.api)
            put("playerInstanceId", event.playerInstanceId)
            put("playbackInstanceId", event.playbackInstanceId)
            put("clipInstanceId", event.clipInstanceId)
            put("wavByteLength", event.wavByteLength)
            put("wavSha256", event.wavSha256)
            event.riffDeclaredSize?.let { put("riffDeclaredSize", it) }
            event.dataDeclaredSize?.let { put("dataDeclaredSize", it) }
            put("sourceFrameLength", event.sourceFrameLength)
            put("pcmFormat", event.pcmFormat)
            put("pcmByteLength", event.pcmByteLength)
            put("pcmSha256", event.pcmSha256)
            put("sourceStreamOpenCount", event.sourceStreamOpenCount)
            put("sourceStreamResetCount", event.sourceStreamResetCount)
            put("pcmReadCallCount", event.pcmReadCallCount)
            put("pcmReadTotalBytes", event.pcmReadTotalBytes)
            put("clipPcmTransferCount", event.clipPcmTransferCount)
            put("clipPcmTransferByteCount", event.clipPcmTransferByteCount)
            put("clipOpenCount", event.clipOpenCount)
            put("clipStartCount", event.clipStartCount)
            put("clipLoopCount", event.clipLoopCount)
            put("clipStopCallCount", event.clipStopCallCount)
            put("clipStopEventCount", event.clipStopEventCount)
            put("clipCloseCount", event.clipCloseCount)
            put("clipFrameLength", event.clipFrameLength)
            put("clipFramePosition", event.clipFramePosition)
            put("activePlayerCount", event.activePlayerCount)
            put("activeClipCount", event.activeClipCount)
            event.failure?.let { put("failure", it) }
        }

        fun safeIdentifier(value: String): String = value
            .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
            .take(120)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        fun audioExtension(contentType: String): String = when (contentType.substringBefore(';').lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/flac" -> "flac"
            else -> "wav"
        }

        fun exactSuffixPrefixOverlap(previous: String, current: String): Int {
            val maximum = minOf(previous.length, current.length)
            for (length in maximum downTo 1) {
                if (previous.regionMatches(previous.length - length, current, 0, length)) {
                    return current.substring(0, length).codePointCount(0, length)
                }
            }
            return 0
        }
    }
}

internal data class TtsDiagnosticChunkSnapshot(
    val chunkId: String,
    val sequence: Int,
    val rawStartOffset: Int,
    val rawEndOffset: Int,
    val normalizedLength: Int,
    val textSha256: String,
    val synthesisAttemptCount: Int,
    val successfulSynthesisCount: Int,
    val httpRequestCount: Int,
    val httpResponseCount: Int,
    val audioByteLength: Int?,
    val audioSha256: String?,
    val synthesisQueueEnqueueCount: Int,
    val audioPlaybackEnqueueCount: Int,
    val playStartCount: Int,
    val playEndCount: Int,
    val textPath: Path,
    val audioPath: Path?,
    val violations: List<String>,
)

internal fun defaultTtsDiagnosticCapture(
    root: Path = ApplicationDirectories.root().resolve("logs").resolve("tts-debug"),
    environment: (String) -> String? = System::getenv,
    systemProperty: (String) -> String? = System::getProperty,
): TtsDiagnosticCapture? {
    val requested = systemProperty(TTS_DIAGNOSTIC_PROPERTY)
        ?: environment(TTS_DIAGNOSTIC_ENVIRONMENT_VARIABLE)
        ?: return null
    val isolatePlayback = when (requested.trim().lowercase()) {
        "1", "true", "on", "isolate" -> true
        "capture", "playback" -> false
        else -> return null
    }
    return TtsDiagnosticCapture(root = root, isolatePlayback = isolatePlayback)
}

internal const val TTS_DIAGNOSTIC_ENVIRONMENT_VARIABLE = "EVE_TTS_DIAGNOSTIC_MODE"
internal const val TTS_DIAGNOSTIC_PROPERTY = "eve.tts.diagnostic.mode"
