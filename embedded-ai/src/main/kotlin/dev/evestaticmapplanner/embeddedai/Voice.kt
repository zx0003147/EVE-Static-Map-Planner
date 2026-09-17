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
}

enum class VoiceOutputProvider(val displayName: String) {
    OFF("Off"),
    LOCAL("Local"),
    OPENAI("OpenAI"),
}

data class VoiceConfig(
    val inputProvider: VoiceInputProvider = VoiceInputProvider.OFF,
    val outputProvider: VoiceOutputProvider = VoiceOutputProvider.OFF,
    val autoSendAfterTranscription: Boolean = false,
    val readAssistantRepliesAloud: Boolean = false,
    val openAiSttModel: String = DEFAULT_OPENAI_STT_MODEL,
    val openAiTtsModel: String = DEFAULT_OPENAI_TTS_MODEL,
    val openAiVoice: String = DEFAULT_OPENAI_VOICE,
    val credentialRef: AiCredentialRef = OPENAI_VOICE_CREDENTIAL_REF,
    val windowsVoice: String? = null,
    val localTtsRate: Int = 0,
    val localTtsVolume: Int = 100,
) {
    init {
        require(openAiSttModel.isNotBlank() && openAiSttModel.length <= 100)
        require(openAiTtsModel.isNotBlank() && openAiTtsModel.length <= 100)
        require(openAiVoice in OPENAI_BUILT_IN_VOICES)
        require(windowsVoice == null || windowsVoice.length <= 200)
        require(localTtsRate in -10..10)
        require(localTtsVolume in 0..100)
    }

    companion object {
        val Defaults = VoiceConfig()
    }
}

enum class VoiceErrorCode {
    NO_VOICE_CREDENTIAL,
    MICROPHONE_UNAVAILABLE,
    RECORDING_FAILED,
    TRANSCRIPTION_FAILED,
    TTS_FAILED,
    VOICE_NETWORK_ERROR,
    VOICE_RATE_LIMITED,
    VOICE_MODEL_UNAVAILABLE,
    AUDIO_PLAYBACK_FAILED,
}

class VoiceException(
    val code: VoiceErrorCode,
    val safeMessage: String,
) : RuntimeException(safeMessage) {
    override fun toString(): String = "VoiceException(code=$code, message=$safeMessage)"
}

interface CloudVoiceClient {
    suspend fun transcribe(wav: ByteArray, model: String, secret: SecretValue): String
    suspend fun synthesize(text: String, model: String, voice: String, secret: SecretValue): ByteArray
}

data class VoiceHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String? = null,
)

fun interface VoiceHttpTransport {
    suspend fun post(uri: URI, headers: Map<String, String>, body: ByteArray, timeout: Duration): VoiceHttpResponse
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
}

class OpenAiVoiceClient(
    private val baseUri: URI = OPENAI_API_BASE,
    private val transport: VoiceHttpTransport = JavaVoiceHttpTransport(),
    private val requestTimeout: Duration = DEFAULT_VOICE_TIMEOUT,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CloudVoiceClient {
    override suspend fun transcribe(wav: ByteArray, model: String, secret: SecretValue): String {
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

    override suspend fun synthesize(text: String, model: String, voice: String, secret: SecretValue): ByteArray {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) > MAX_OPENAI_TTS_CHARACTERS) {
            throw voiceError(VoiceErrorCode.TTS_FAILED, "The assistant reply could not be read aloud.")
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
        )
        if (response.body.isEmpty()) throw voiceError(VoiceErrorCode.TTS_FAILED, "The speech provider returned no audio.")
        return response.body
    }

    private suspend fun request(
        endpoint: String,
        secret: SecretValue,
        contentType: String,
        body: ByteArray,
        operation: VoiceOperation,
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
                timeout = requestTimeout,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.net.http.HttpTimeoutException) {
            throw voiceError(VoiceErrorCode.VOICE_NETWORK_ERROR, "The speech provider timed out.")
        } catch (_: TimeoutException) {
            throw voiceError(VoiceErrorCode.VOICE_NETWORK_ERROR, "The speech provider timed out.")
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
            401, 403 -> throw voiceError(VoiceErrorCode.NO_VOICE_CREDENTIAL, "OpenAI Voice authentication failed.")
            404, 422 -> throw voiceError(
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
    SPEECH(VoiceErrorCode.TTS_FAILED, "The assistant reply could not be read aloud."),
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
const val OPENAI_VOICE_ENVIRONMENT_VARIABLE = "OPENAI_VOICE_API_KEY"
val OPENAI_API_BASE: URI = URI.create("https://api.openai.com/v1/")
val DEFAULT_VOICE_TIMEOUT: Duration = Duration.ofSeconds(60)
const val DEFAULT_OPENAI_STT_MODEL = "gpt-4o-mini-transcribe"
const val DEFAULT_OPENAI_TTS_MODEL = "gpt-4o-mini-tts"
const val DEFAULT_OPENAI_VOICE = "alloy"
const val MAX_OPENAI_TTS_CHARACTERS = 4_096
const val MAX_VOICE_AUDIO_BYTES = 4 * 1024 * 1024
val OPENAI_BUILT_IN_VOICES = listOf(
    "alloy", "ash", "ballad", "coral", "echo", "fable", "onyx",
    "nova", "sage", "shimmer", "verse", "marin", "cedar",
)
