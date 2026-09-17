package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.shared.auth.SecretValue
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AlibabaSpeechClient(
    transport: VoiceHttpTransport? = null,
    private val sttEndpoint: (AlibabaSpeechRegion) -> URI = ::defaultAlibabaSttEndpoint,
    private val ttsEndpoint: (AlibabaSpeechRegion, String?, String) -> URI = ::defaultAlibabaTtsEndpoint,
    private val audioUrlValidator: (URI) -> Boolean = ::isOfficialAlibabaAudioUrl,
    private val temporaryDirectory: Path? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val transport: VoiceHttpTransport by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        transport ?: JavaVoiceHttpTransport()
    }

    suspend fun transcribe(
        audio: RecordedAudio,
        model: String,
        region: AlibabaSpeechRegion,
        timeout: Duration,
        secret: SecretValue,
    ): SpeechTranscript {
        val dataUri = "data:audio/wav;base64,${Base64.getEncoder().encodeToString(audio.wav)}"
        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_audio")
                            put("input_audio", buildJsonObject { put("data", dataUri) })
                        })
                    })
                })
            })
            put("stream", false)
            put("asr_options", buildJsonObject { put("enable_itn", false) })
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val response = request(
            uri = sttEndpoint(region),
            secret = secret,
            body = requestBody,
            timeout = timeout,
            operation = AlibabaOperation.TRANSCRIPTION,
        )
        val transcript = runCatching {
            json.parseToJsonElement(response.body.toString(StandardCharsets.UTF_8))
                .jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
        }.getOrNull()
        return transcript?.takeIf(String::isNotBlank)?.let(::SpeechTranscript)
            ?: throw voiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, "Alibaba Cloud returned no transcript.")
    }

    suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        region: AlibabaSpeechRegion,
        workspaceId: String?,
        timeout: Duration,
        secret: SecretValue,
    ): SynthesizedAudio {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) > MAX_ALIBABA_TTS_CHARACTERS) {
            throw voiceError(VoiceErrorCode.SYNTHESIS_FAILED, "The assistant reply could not be synthesized.")
        }
        val qwenAudio = model.startsWith("qwen-audio-", ignoreCase = true) ||
            model.startsWith("cosyvoice-", ignoreCase = true)
        if (qwenAudio && region != AlibabaSpeechRegion.CHINA_BEIJING) {
            throw voiceError(
                VoiceErrorCode.VOICE_MODEL_UNAVAILABLE,
                "The selected Alibaba speech model is not available in this region.",
            )
        }
        val requestBody = buildJsonObject {
            put("model", model)
            put("input", buildJsonObject {
                put("text", normalized)
                put("voice", voice)
                if (qwenAudio) {
                    put("format", "wav")
                    put("sample_rate", DEFAULT_ALIBABA_TTS_SAMPLE_RATE)
                } else {
                    put("language_type", "Auto")
                }
            })
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val response = request(
            uri = ttsEndpoint(region, workspaceId, model),
            secret = secret,
            body = requestBody,
            timeout = timeout,
            operation = AlibabaOperation.SYNTHESIS,
        )
        val audioUri = runCatching {
            json.parseToJsonElement(response.body.toString(StandardCharsets.UTF_8))
                .jsonObject["output"]?.jsonObject?.get("audio")?.jsonObject
                ?.get("url")?.jsonPrimitive?.contentOrNull?.trim()?.let(URI::create)
        }.getOrNull()?.upgradeOfficialAlibabaAudioUrl()?.takeIf(audioUrlValidator)
            ?: throw voiceError(VoiceErrorCode.SYNTHESIS_FAILED, "Alibaba Cloud returned no valid audio URL.")
        return downloadWav(audioUri, timeout)
    }

    private suspend fun request(
        uri: URI,
        secret: SecretValue,
        body: ByteArray,
        timeout: Duration,
        operation: AlibabaOperation,
    ): VoiceHttpResponse {
        val token = secret.useString { it }
        val response = try {
            transport.post(
                uri = uri,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                ),
                body = body,
                timeout = timeout,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpTimeoutException) {
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "Alibaba Cloud speech timed out.")
        } catch (_: TimeoutException) {
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "Alibaba Cloud speech timed out.")
        } catch (_: ConnectException) {
            throw voiceError(VoiceErrorCode.VOICE_NETWORK_ERROR, "Alibaba Cloud speech could not be reached.")
        } catch (_: IOException) {
            throw voiceError(VoiceErrorCode.VOICE_NETWORK_ERROR, "Alibaba Cloud speech could not be reached.")
        } catch (failure: VoiceException) {
            throw failure
        } catch (_: Exception) {
            throw voiceError(operation.failureCode, operation.failureMessage)
        }
        if (response.statusCode in 200..299) return response
        throw mapAlibabaFailure(response, operation)
    }

    private suspend fun downloadWav(uri: URI, timeout: Duration): SynthesizedAudio {
        val response = try {
            transport.get(uri, mapOf("Accept" to "audio/wav"), timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpTimeoutException) {
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "Alibaba Cloud audio download timed out.")
        } catch (_: TimeoutException) {
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "Alibaba Cloud audio download timed out.")
        } catch (_: IOException) {
            throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud audio could not be downloaded.")
        } catch (failure: VoiceException) {
            throw failure
        } catch (_: Exception) {
            throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud audio could not be downloaded.")
        }
        if (response.statusCode !in 200..299) {
            throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud audio could not be downloaded.")
        }
        val contentType = response.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (contentType !in ALLOWED_WAV_CONTENT_TYPES || response.body.size !in MIN_WAV_BYTES..MAX_SYNTHESIZED_AUDIO_BYTES) {
            throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud returned invalid audio.")
        }
        val temporary = if (temporaryDirectory == null) {
            Files.createTempFile("eve-planner-alibaba-tts-", ".wav")
        } else {
            Files.createDirectories(temporaryDirectory)
            Files.createTempFile(temporaryDirectory, "eve-planner-alibaba-tts-", ".wav")
        }
        return try {
            Files.write(temporary, response.body)
            val wav = Files.readAllBytes(temporary)
            if (!wav.isRiffWave()) {
                throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud returned invalid WAV audio.")
            }
            SynthesizedAudio(wav, "audio/wav")
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun mapAlibabaFailure(response: VoiceHttpResponse, operation: AlibabaOperation): VoiceException {
        val error = runCatching {
            val parsed = json.parseToJsonElement(response.body.toString(StandardCharsets.UTF_8)).jsonObject
            listOfNotNull(
                parsed["code"]?.jsonPrimitive?.contentOrNull,
                parsed["message"]?.jsonPrimitive?.contentOrNull,
                parsed["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull,
                parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull,
            ).joinToString(" ").lowercase()
        }.getOrDefault("")
        val code = when {
            response.statusCode == 401 || "invalidapikey" in error || "invalid_api_key" in error ->
                VoiceErrorCode.INVALID_VOICE_CREDENTIAL
            response.statusCode == 429 || "throttl" in error || "rate" in error || "quota" in error ->
                VoiceErrorCode.VOICE_RATE_LIMITED
            response.statusCode == 404 || "modelnotfound" in error || "model_not_found" in error ||
                "model not" in error -> VoiceErrorCode.VOICE_MODEL_NOT_FOUND
            response.statusCode in 500..599 -> VoiceErrorCode.VOICE_MODEL_UNAVAILABLE
            response.statusCode == 400 && ("voice" in error || "model" in error || "invalidparameter" in error) ->
                VoiceErrorCode.VOICE_MODEL_UNAVAILABLE
            response.statusCode == 403 -> VoiceErrorCode.VOICE_PROVIDER_ERROR
            else -> operation.failureCode
        }
        val message = when (code) {
            VoiceErrorCode.INVALID_VOICE_CREDENTIAL -> "Alibaba Cloud speech authentication failed."
            VoiceErrorCode.VOICE_RATE_LIMITED -> "Alibaba Cloud speech rate limit was reached."
            VoiceErrorCode.VOICE_MODEL_NOT_FOUND -> "The selected Alibaba speech model was not found."
            VoiceErrorCode.VOICE_MODEL_UNAVAILABLE -> "The selected Alibaba speech model or voice is unavailable."
            VoiceErrorCode.VOICE_PROVIDER_ERROR -> "Alibaba Cloud rejected the speech request."
            else -> operation.failureMessage
        }
        return voiceError(code, message)
    }
}

class AlibabaSpeechToTextProvider(
    private val credentialResolver: AiCredentialResolver,
    private val client: AlibabaSpeechClient,
) : SpeechToTextProvider {
    override val capability = SpeechProviderCapability(
        supportsStt = true,
        supportsTts = false,
        requiresApiKey = true,
        supportsVoiceSelection = false,
        supportsModelSelection = true,
    )

    override suspend fun transcribe(audio: RecordedAudio, config: SpeechRecognitionConfig): SpeechTranscript {
        val model = config.model?.takeIf(String::isNotBlank)
            ?: throw voiceError(VoiceErrorCode.VOICE_MODEL_NOT_FOUND, "No Alibaba transcription model is configured.")
        val region = config.region ?: AlibabaSpeechRegion.CHINA_BEIJING
        val credential = credentialResolver.resolve(ALIBABA_SPEECH_CREDENTIAL_REF, ALIBABA_SPEECH_ENVIRONMENT_VARIABLE)
            ?: throw voiceError(VoiceErrorCode.NO_VOICE_CREDENTIAL, "Alibaba Speech is not configured.")
        return credential.use { resolved ->
            resolved.useSecret(SecretValue::copy).use { secret ->
                client.transcribe(audio, model, region, config.timeout, secret)
            }
        }
    }
}

class AlibabaTextToSpeechProvider(
    private val credentialResolver: AiCredentialResolver,
    private val client: AlibabaSpeechClient,
) : TextToSpeechProvider {
    override val capability = SpeechProviderCapability(
        supportsStt = false,
        supportsTts = true,
        requiresApiKey = true,
        supportsVoiceSelection = true,
        supportsModelSelection = true,
    )

    override suspend fun synthesize(text: String, config: SpeechSynthesisConfig): SynthesizedAudio {
        val model = config.model?.takeIf(String::isNotBlank)
            ?: throw voiceError(VoiceErrorCode.VOICE_MODEL_NOT_FOUND, "No Alibaba speech model is configured.")
        val voice = config.voice?.takeIf(String::isNotBlank)
            ?: throw voiceError(VoiceErrorCode.VOICE_MODEL_UNAVAILABLE, "No Alibaba voice is configured.")
        val region = config.region ?: AlibabaSpeechRegion.CHINA_BEIJING
        val credential = credentialResolver.resolve(ALIBABA_SPEECH_CREDENTIAL_REF, ALIBABA_SPEECH_ENVIRONMENT_VARIABLE)
            ?: throw voiceError(VoiceErrorCode.NO_VOICE_CREDENTIAL, "Alibaba Speech is not configured.")
        return credential.use { resolved ->
            resolved.useSecret(SecretValue::copy).use { secret ->
                client.synthesize(text, model, voice, region, config.workspaceId, config.timeout, secret)
            }
        }
    }
}

internal fun defaultAlibabaSttEndpoint(region: AlibabaSpeechRegion): URI =
    URI.create("https://${region.publicHost}/compatible-mode/v1/chat/completions")

internal fun defaultAlibabaTtsEndpoint(
    region: AlibabaSpeechRegion,
    workspaceId: String?,
    model: String,
): URI {
    val workspaceModel = model.startsWith("qwen-audio-", ignoreCase = true) ||
        model.startsWith("cosyvoice-", ignoreCase = true)
    return if (workspaceModel) {
        val workspace = workspaceId?.takeIf(String::isNotBlank)
            ?: throw voiceError(
                VoiceErrorCode.VOICE_PROVIDER_ERROR,
                "The selected Alibaba TTS model requires a Workspace ID.",
            )
        URI.create(
            "https://$workspace.${region.dedicatedRegion}.maas.aliyuncs.com/" +
                "api/v1/services/audio/tts/SpeechSynthesizer",
        )
    } else {
        URI.create("https://${region.publicHost}/api/v1/services/aigc/multimodal-generation/generation")
    }
}

private enum class AlibabaOperation(val failureCode: VoiceErrorCode, val failureMessage: String) {
    TRANSCRIPTION(VoiceErrorCode.TRANSCRIPTION_FAILED, "The recording could not be transcribed by Alibaba Cloud."),
    SYNTHESIS(VoiceErrorCode.SYNTHESIS_FAILED, "The reply could not be synthesized by Alibaba Cloud."),
}

private fun isOfficialAlibabaAudioUrl(uri: URI): Boolean =
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host?.lowercase()?.let { it == "aliyuncs.com" || it.endsWith(".aliyuncs.com") }.orFalse()

internal fun URI.upgradeOfficialAlibabaAudioUrl(): URI {
    val officialHost = host?.lowercase()?.let { it == "aliyuncs.com" || it.endsWith(".aliyuncs.com") }.orFalse()
    return if (scheme.equals("http", ignoreCase = true) && officialHost) {
        URI("https", userInfo, host, port, path, query, fragment)
    } else {
        this
    }
}

private fun Boolean?.orFalse(): Boolean = this == true

private fun ByteArray.isRiffWave(): Boolean = size >= MIN_WAV_BYTES &&
    copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
    copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WAVE"

private fun voiceError(code: VoiceErrorCode, message: String) = VoiceException(code, message)

private val ALLOWED_WAV_CONTENT_TYPES = setOf("audio/wav", "audio/x-wav", "application/octet-stream")
private const val MIN_WAV_BYTES = 44
private const val MAX_ALIBABA_TTS_CHARACTERS = 600
