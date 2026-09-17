package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.shared.auth.SecretValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class VoiceInputProvider(val displayName: String) {
    OFF("Off"),
    LOCAL("Local"),
    OPENAI("OpenAI"),
    ALIBABA("Alibaba Cloud"),
}

enum class VoiceOutputProvider(val displayName: String) {
    OFF("Off"),
    LOCAL("Local"),
    OPENAI("OpenAI"),
    ALIBABA("Alibaba Cloud"),
}

enum class AlibabaSpeechRegion(val displayName: String, val publicHost: String, val dedicatedRegion: String) {
    CHINA_BEIJING("China (Beijing)", "dashscope.aliyuncs.com", "cn-beijing"),
    SINGAPORE("Singapore", "dashscope-intl.aliyuncs.com", "ap-southeast-1"),
}

data class LocalSpeechProfile(
    val windowsVoice: String? = null,
    val ttsRate: Int = 0,
    val ttsVolume: Int = 100,
) {
    init {
        require(windowsVoice == null || windowsVoice.length <= 200)
        require(ttsRate in -10..10)
        require(ttsVolume in 0..100)
    }
}

data class OpenAiSpeechProfile(
    val sttModel: String = DEFAULT_OPENAI_STT_MODEL,
    val ttsModel: String = DEFAULT_OPENAI_TTS_MODEL,
    val voice: String = DEFAULT_OPENAI_VOICE,
    val timeoutSeconds: Long = DEFAULT_VOICE_TIMEOUT.seconds,
    val credentialRef: AiCredentialRef = OPENAI_VOICE_CREDENTIAL_REF,
) {
    init {
        require(sttModel.isNotBlank() && sttModel.length <= 100)
        require(ttsModel.isNotBlank() && ttsModel.length <= 100)
        require(voice in OPENAI_BUILT_IN_VOICES)
        require(timeoutSeconds in MIN_VOICE_TIMEOUT_SECONDS..MAX_VOICE_TIMEOUT_SECONDS)
        require(credentialRef == OPENAI_VOICE_CREDENTIAL_REF)
    }
}

data class AlibabaSpeechProfile(
    val sttModel: String = DEFAULT_ALIBABA_STT_MODEL,
    val ttsModel: String = DEFAULT_ALIBABA_TTS_MODEL,
    val voice: String = DEFAULT_ALIBABA_VOICE,
    val sttRegion: AlibabaSpeechRegion = AlibabaSpeechRegion.CHINA_BEIJING,
    val ttsRegion: AlibabaSpeechRegion = AlibabaSpeechRegion.CHINA_BEIJING,
    val workspaceId: String? = null,
    val sttTimeoutSeconds: Long = DEFAULT_VOICE_TIMEOUT.seconds,
    val ttsTimeoutSeconds: Long = DEFAULT_VOICE_TIMEOUT.seconds,
    val credentialRef: AiCredentialRef = ALIBABA_SPEECH_CREDENTIAL_REF,
) {
    init {
        require(sttModel.isNotBlank() && sttModel.length <= 120)
        require(ttsModel.isNotBlank() && ttsModel.length <= 120)
        require(voice.isNotBlank() && voice.length <= 160)
        require(workspaceId == null || WORKSPACE_ID_PATTERN.matches(workspaceId))
        require(sttTimeoutSeconds in MIN_VOICE_TIMEOUT_SECONDS..MAX_VOICE_TIMEOUT_SECONDS)
        require(ttsTimeoutSeconds in MIN_VOICE_TIMEOUT_SECONDS..MAX_VOICE_TIMEOUT_SECONDS)
        require(credentialRef == ALIBABA_SPEECH_CREDENTIAL_REF)
    }
}

data class SpeechProviderProfiles(
    val local: LocalSpeechProfile = LocalSpeechProfile(),
    val openAi: OpenAiSpeechProfile = OpenAiSpeechProfile(),
    val alibaba: AlibabaSpeechProfile = AlibabaSpeechProfile(),
)

data class VoiceConfig(
    val inputProvider: VoiceInputProvider = VoiceInputProvider.OFF,
    val outputProvider: VoiceOutputProvider = VoiceOutputProvider.OFF,
    val autoSendAfterTranscription: Boolean = false,
    val readAssistantRepliesAloud: Boolean = false,
    val profiles: SpeechProviderProfiles = SpeechProviderProfiles(),
) {

    companion object {
        val Defaults = VoiceConfig()
    }
}

enum class VoiceErrorCode {
    NO_VOICE_CREDENTIAL,
    INVALID_VOICE_CREDENTIAL,
    MICROPHONE_UNAVAILABLE,
    RECORDING_FAILED,
    TRANSCRIPTION_FAILED,
    SYNTHESIS_FAILED,
    VOICE_NETWORK_ERROR,
    VOICE_RATE_LIMITED,
    VOICE_TIMEOUT,
    VOICE_MODEL_NOT_FOUND,
    VOICE_MODEL_UNAVAILABLE,
    VOICE_PROVIDER_ERROR,
    AUDIO_DOWNLOAD_FAILED,
    AUDIO_PLAYBACK_FAILED,
}

class VoiceException(
    val code: VoiceErrorCode,
    val safeMessage: String,
) : RuntimeException(safeMessage) {
    override fun toString(): String = "VoiceException(code=$code, message=$safeMessage)"
}

interface CloudVoiceClient {
    suspend fun transcribe(
        wav: ByteArray,
        model: String,
        secret: SecretValue,
        timeout: Duration = DEFAULT_VOICE_TIMEOUT,
    ): String
    suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        secret: SecretValue,
        timeout: Duration = DEFAULT_VOICE_TIMEOUT,
    ): ByteArray
}

data class VoiceHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String? = null,
)

interface VoiceHttpTransport {
    suspend fun post(uri: URI, headers: Map<String, String>, body: ByteArray, timeout: Duration): VoiceHttpResponse
    suspend fun get(uri: URI, headers: Map<String, String>, timeout: Duration): VoiceHttpResponse
}

class JavaVoiceHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(DEFAULT_VOICE_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : VoiceHttpTransport {
    override suspend fun post(
        uri: URI,
        headers: Map<String, String>,
        body: ByteArray,
        timeout: Duration,
    ): VoiceHttpResponse {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        headers.forEach(builder::header)
        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).awaitCancellable()
        return VoiceHttpResponse(
            statusCode = response.statusCode(),
            body = response.body(),
            contentType = response.headers().firstValue("Content-Type").orElse(null),
        )
    }

    override suspend fun get(uri: URI, headers: Map<String, String>, timeout: Duration): VoiceHttpResponse {
        val builder = HttpRequest.newBuilder(uri).timeout(timeout).GET()
        headers.forEach(builder::header)
        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).awaitCancellable()
        return VoiceHttpResponse(
            statusCode = response.statusCode(),
            body = response.body(),
            contentType = response.headers().firstValue("Content-Type").orElse(null),
        )
    }
}

class OpenAiVoiceClient(
    private val baseUri: URI = OPENAI_API_BASE,
    private val transport: VoiceHttpTransport = JavaVoiceHttpTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CloudVoiceClient {
    override suspend fun transcribe(wav: ByteArray, model: String, secret: SecretValue, timeout: Duration): String {
        if (wav.isEmpty() || wav.size > MAX_VOICE_AUDIO_BYTES) {
            throw voiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, "The recording could not be transcribed.")
        }
        val boundary = "eve-planner-${UUID.randomUUID()}"
        val body = multipartWav(boundary, wav, model)
        val response = request(
            endpoint = "audio/transcriptions",
            secret = secret,
            contentType = "multipart/form-data; boundary=$boundary",
            body = body,
            operation = VoiceOperation.TRANSCRIPTION,
            timeout = timeout,
        )
        val transcript = try {
            json.parseToJsonElement(response.body.toString(StandardCharsets.UTF_8))
                .jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim()
        } catch (_: Exception) {
            null
        }
        return transcript?.takeIf(String::isNotBlank)
            ?: throw voiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, "The speech provider returned no transcript.")
    }

    override suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        secret: SecretValue,
        timeout: Duration,
    ): ByteArray {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) > MAX_OPENAI_TTS_CHARACTERS) {
            throw voiceError(VoiceErrorCode.SYNTHESIS_FAILED, "The assistant reply could not be read aloud.")
        }
        if (voice !in OPENAI_BUILT_IN_VOICES) {
            throw voiceError(VoiceErrorCode.VOICE_MODEL_UNAVAILABLE, "The selected OpenAI voice is unavailable.")
        }
        val body = buildJsonObject {
            put("model", model)
            put("voice", voice)
            put("input", normalized)
            put("response_format", "wav")
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val response = request(
            endpoint = "audio/speech",
            secret = secret,
            contentType = "application/json",
            body = body,
            operation = VoiceOperation.SPEECH,
            timeout = timeout,
        )
        if (response.body.isEmpty()) throw voiceError(VoiceErrorCode.SYNTHESIS_FAILED, "The speech provider returned no audio.")
        return response.body
    }

    private suspend fun request(
        endpoint: String,
        secret: SecretValue,
        contentType: String,
        body: ByteArray,
        operation: VoiceOperation,
        timeout: Duration,
    ): VoiceHttpResponse {
        val token = secret.useString { it }
        val response = try {
            transport.post(
                uri = baseUri.resolve(endpoint),
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to if (operation == VoiceOperation.SPEECH) "audio/wav" else "application/json",
                    "Content-Type" to contentType,
                ),
                body = body,
                timeout = timeout,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.net.http.HttpTimeoutException) {
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "The speech provider timed out.")
        } catch (_: TimeoutException) {
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "The speech provider timed out.")
        } catch (_: ConnectException) {
            throw voiceError(VoiceErrorCode.VOICE_NETWORK_ERROR, "The speech provider could not be reached.")
        } catch (_: IOException) {
            throw voiceError(VoiceErrorCode.VOICE_NETWORK_ERROR, "The speech provider could not be reached.")
        } catch (failure: VoiceException) {
            throw failure
        } catch (_: Exception) {
            throw voiceError(operation.failureCode, operation.failureMessage)
        }
        return when (response.statusCode) {
            in 200..299 -> response
            401, 403 -> throw voiceError(VoiceErrorCode.INVALID_VOICE_CREDENTIAL, "OpenAI Voice authentication failed.")
            404 -> throw voiceError(VoiceErrorCode.VOICE_MODEL_NOT_FOUND, "The selected speech model was not found.")
            422 -> throw voiceError(
                VoiceErrorCode.VOICE_MODEL_UNAVAILABLE,
                "The selected speech model or voice is unavailable.",
            )
            429 -> throw voiceError(VoiceErrorCode.VOICE_RATE_LIMITED, "The speech provider rate limit was reached.")
            in 500..599 -> throw voiceError(VoiceErrorCode.VOICE_MODEL_UNAVAILABLE, "The speech model is temporarily unavailable.")
            else -> throw voiceError(operation.failureCode, operation.failureMessage)
        }
    }
}

private enum class VoiceOperation(val failureCode: VoiceErrorCode, val failureMessage: String) {
    TRANSCRIPTION(VoiceErrorCode.TRANSCRIPTION_FAILED, "The recording could not be transcribed."),
    SPEECH(VoiceErrorCode.SYNTHESIS_FAILED, "The assistant reply could not be read aloud."),
}

private fun multipartWav(boundary: String, wav: ByteArray, model: String): ByteArray = ByteArrayOutputStream().use { output ->
    fun write(value: String) = output.write(value.toByteArray(StandardCharsets.UTF_8))
    write("--$boundary\r\n")
    write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
    write(model)
    write("\r\n--$boundary\r\n")
    write("Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n")
    write("Content-Type: audio/wav\r\n\r\n")
    output.write(wav)
    write("\r\n--$boundary--\r\n")
    output.toByteArray()
}

private suspend fun <T> java.util.concurrent.CompletableFuture<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel(true) }
        whenComplete { value, failure ->
            when {
                failure == null -> continuation.resume(value)
                failure is CompletionException && failure.cause != null -> continuation.resumeWithException(failure.cause!!)
                else -> continuation.resumeWithException(failure)
            }
        }
    }

private fun voiceError(code: VoiceErrorCode, message: String) = VoiceException(code, message)

val OPENAI_VOICE_CREDENTIAL_REF = AiCredentialRef("openai-voice")
val ALIBABA_SPEECH_CREDENTIAL_REF = AiCredentialRef("alibaba-speech")
const val OPENAI_VOICE_ENVIRONMENT_VARIABLE = "OPENAI_VOICE_API_KEY"
const val ALIBABA_SPEECH_ENVIRONMENT_VARIABLE = "DASHSCOPE_API_KEY"
val OPENAI_API_BASE: URI = URI.create("https://api.openai.com/v1/")
val DEFAULT_VOICE_TIMEOUT: Duration = Duration.ofSeconds(60)
const val MIN_VOICE_TIMEOUT_SECONDS = 5L
const val MAX_VOICE_TIMEOUT_SECONDS = 300L
const val DEFAULT_OPENAI_STT_MODEL = "gpt-4o-mini-transcribe"
const val DEFAULT_OPENAI_TTS_MODEL = "gpt-4o-mini-tts"
const val DEFAULT_OPENAI_VOICE = "alloy"
const val DEFAULT_ALIBABA_STT_MODEL = "qwen3-asr-flash"
const val ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL = "qwen-audio-3.0-asr-flash"
const val DEFAULT_ALIBABA_TTS_MODEL = "qwen3-tts-flash"
const val DEFAULT_ALIBABA_VOICE = "Cherry"
const val DEFAULT_ALIBABA_TTS_SAMPLE_RATE = 24_000
const val MAX_OPENAI_TTS_CHARACTERS = 4_096
const val MAX_VOICE_AUDIO_BYTES = 4 * 1024 * 1024
const val MAX_SYNTHESIZED_AUDIO_BYTES = 16 * 1024 * 1024
private val WORKSPACE_ID_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{1,127}")
val OPENAI_BUILT_IN_VOICES = listOf(
    "alloy", "ash", "ballad", "coral", "echo", "fable", "onyx",
    "nova", "sage", "shimmer", "verse", "marin", "cedar",
)
