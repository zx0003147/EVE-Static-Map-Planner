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
    private val sttEndpoint: (AlibabaSpeechRegion, String?, String) -> URI = ::defaultAlibabaSttEndpoint,
    private val ttsEndpoint: (AlibabaSpeechRegion, String?, String) -> URI = ::defaultAlibabaTtsEndpoint,
    private val audioUrlValidator: (URI) -> Boolean = ::isOfficialAlibabaAudioUrl,
    private val temporaryDirectory: Path? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val diagnostics: (String) -> Unit = {},
) {
    private val transport: VoiceHttpTransport by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        transport ?: JavaVoiceHttpTransport()
    }

    suspend fun transcribe(
        audio: RecordedAudio,
        model: String,
        region: AlibabaSpeechRegion,
        workspaceId: String?,
        timeout: Duration,
        secret: SecretValue,
    ): SpeechTranscript {
        val dataUri = "data:audio/wav;base64,${Base64.getEncoder().encodeToString(audio.wav)}"
        val workspaceAsr = isAlibabaWorkspaceAsrModel(model)
        val requestBody = if (workspaceAsr) {
            buildJsonObject {
                put("model", model)
                put("input", buildJsonObject {
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
                })
                put("parameters", buildJsonObject {
                    put("format", "wav")
                    put("sample_rate", audio.sampleRateHz.toString())
                })
            }
        } else {
            buildJsonObject {
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
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val response = request(
            uri = sttEndpoint(region, workspaceId, model),
            secret = secret,
            body = requestBody,
            timeout = timeout,
            operation = AlibabaOperation.TRANSCRIPTION,
            additionalHeaders = if (workspaceAsr) mapOf("X-DashScope-SSE" to "disable") else emptyMap(),
        )
        val transcript = runCatching {
            val parsed = json.parseToJsonElement(response.body.toString(StandardCharsets.UTF_8)).jsonObject
            if (workspaceAsr) {
                val output = parsed["output"]?.jsonObject
                output?.get("text")?.jsonPrimitive?.contentOrNull?.trim()
                    ?: output?.get("sentence")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.trim()
            } else {
                parsed["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
            }
        }.getOrNull()
        transcript?.takeIf(String::isNotBlank)?.let { return SpeechTranscript(it) }
        secret.useString { token ->
            reportTranscriptionFailure("response parsing failed", response, token)
        }
        throw voiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, "Alibaba Cloud returned no transcript.")
    }

    suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        region: AlibabaSpeechRegion,
        workspaceId: String?,
        timeout: Duration,
        secret: SecretValue,
        requestContext: TtsRequestContext? = null,
    ): SynthesizedAudio {
        val normalized = text.trim()
        val ttsContext = AlibabaTtsDiagnosticContext(
            model = model,
            voice = voice,
            inputTextCharCount = normalized.codePointCount(0, normalized.length),
            chunkIndex = requestContext?.chunkIndex ?: 1,
            chunkCharCount = normalized.codePointCount(0, normalized.length),
        )
        if (normalized.isEmpty()) {
            secret.useString { token -> reportTtsFailure("empty input rejected", null, token, ttsContext) }
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
            ttsContext = ttsContext,
            requestContext = requestContext,
        )
        val audioUri = runCatching {
            json.parseToJsonElement(response.body.toString(StandardCharsets.UTF_8))
                .jsonObject["output"]?.jsonObject?.get("audio")?.jsonObject
                ?.get("url")?.jsonPrimitive?.contentOrNull?.trim()?.let(URI::create)
        }.getOrNull()?.upgradeOfficialAlibabaAudioUrl()?.takeIf(audioUrlValidator)
        if (audioUri == null) {
            secret.useString { token -> reportTtsFailure("audio URL parsing failed", response, token, ttsContext) }
            throw voiceError(VoiceErrorCode.SYNTHESIS_FAILED, "Alibaba Cloud returned no valid audio URL.")
        }
        return downloadWav(audioUri, timeout, ttsContext, requestContext)
    }

    private suspend fun request(
        uri: URI,
        secret: SecretValue,
        body: ByteArray,
        timeout: Duration,
        operation: AlibabaOperation,
        additionalHeaders: Map<String, String> = emptyMap(),
        ttsContext: AlibabaTtsDiagnosticContext? = null,
        requestContext: TtsRequestContext? = null,
    ): VoiceHttpResponse {
        val token = secret.useString { it }
        if (operation == AlibabaOperation.SYNTHESIS) {
            requestContext?.providerTraceSink?.record(
                TtsProviderHttpEvent(
                    stage = TtsProviderHttpStage.SYNTHESIS,
                    type = TtsProviderHttpEventType.REQUEST_STARTED,
                ),
            )
        }
        val response = try {
            transport.post(
                uri = uri,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                ) + additionalHeaders,
                body = body,
                timeout = timeout,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpTimeoutException) {
            ttsContext?.let { reportTtsFailure("request timed out", null, token, it) }
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "Alibaba Cloud speech timed out.")
        } catch (_: TimeoutException) {
            ttsContext?.let { reportTtsFailure("request timed out", null, token, it) }
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "Alibaba Cloud speech timed out.")
        } catch (_: ConnectException) {
            ttsContext?.let { reportTtsFailure("request connection failed", null, token, it) }
            throw voiceError(VoiceErrorCode.VOICE_NETWORK_ERROR, "Alibaba Cloud speech could not be reached.")
        } catch (_: IOException) {
            ttsContext?.let { reportTtsFailure("request I/O failed", null, token, it) }
            throw voiceError(VoiceErrorCode.VOICE_NETWORK_ERROR, "Alibaba Cloud speech could not be reached.")
        } catch (failure: VoiceException) {
            ttsContext?.let { reportTtsFailure("request failed locally", null, token, it, "error=${failure.code}") }
            throw failure
        } catch (_: Exception) {
            ttsContext?.let { reportTtsFailure("request failed unexpectedly", null, token, it) }
            throw voiceError(operation.failureCode, operation.failureMessage)
        }
        if (operation == AlibabaOperation.SYNTHESIS) {
            requestContext?.providerTraceSink?.record(
                TtsProviderHttpEvent(
                    stage = TtsProviderHttpStage.SYNTHESIS,
                    type = TtsProviderHttpEventType.RESPONSE_RECEIVED,
                    statusCode = response.statusCode,
                ),
            )
        }
        if (response.statusCode in 200..299) return response
        if (operation == AlibabaOperation.TRANSCRIPTION) {
            reportTranscriptionFailure("request failed", response, token)
        } else if (ttsContext != null) {
            reportTtsFailure("request failed", response, token, ttsContext)
        }
        throw mapAlibabaFailure(response, operation)
    }

    private suspend fun downloadWav(
        uri: URI,
        timeout: Duration,
        ttsContext: AlibabaTtsDiagnosticContext,
        requestContext: TtsRequestContext?,
    ): SynthesizedAudio {
        requestContext?.providerTraceSink?.record(
            TtsProviderHttpEvent(
                stage = TtsProviderHttpStage.AUDIO_DOWNLOAD,
                type = TtsProviderHttpEventType.REQUEST_STARTED,
            ),
        )
        val response = try {
            transport.get(uri, mapOf("Accept" to "audio/wav"), timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpTimeoutException) {
            reportTtsFailure("audio download timed out", null, "", ttsContext)
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "Alibaba Cloud audio download timed out.")
        } catch (_: TimeoutException) {
            reportTtsFailure("audio download timed out", null, "", ttsContext)
            throw voiceError(VoiceErrorCode.VOICE_TIMEOUT, "Alibaba Cloud audio download timed out.")
        } catch (_: IOException) {
            reportTtsFailure("audio download I/O failed", null, "", ttsContext)
            throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud audio could not be downloaded.")
        } catch (failure: VoiceException) {
            reportTtsFailure("audio download failed locally", null, "", ttsContext, "error=${failure.code}")
            throw failure
        } catch (_: Exception) {
            reportTtsFailure("audio download failed unexpectedly", null, "", ttsContext)
            throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud audio could not be downloaded.")
        }
        requestContext?.providerTraceSink?.record(
            TtsProviderHttpEvent(
                stage = TtsProviderHttpStage.AUDIO_DOWNLOAD,
                type = TtsProviderHttpEventType.RESPONSE_RECEIVED,
                statusCode = response.statusCode,
            ),
        )
        if (response.statusCode !in 200..299) {
            reportTtsFailure("audio download failed", response, "", ttsContext)
            throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud audio could not be downloaded.")
        }
        val contentType = response.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (contentType !in ALLOWED_WAV_CONTENT_TYPES || response.body.size !in MIN_WAV_BYTES..MAX_SYNTHESIZED_AUDIO_BYTES) {
            reportTtsFailure(
                "audio download returned invalid bytes",
                response,
                "",
                ttsContext,
                "contentType=${response.contentType ?: "<none>"}, audioByteCount=${response.body.size}",
            )
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
                reportTtsFailure(
                    "downloaded audio was not RIFF/WAVE",
                    response,
                    "",
                    ttsContext,
                    "audioByteCount=${wav.size}",
                )
                throw voiceError(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, "Alibaba Cloud returned invalid WAV audio.")
            }
            SynthesizedAudio(wav, "audio/wav")
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun mapAlibabaFailure(response: VoiceHttpResponse, operation: AlibabaOperation): VoiceException {
        val error = parseAlibabaErrorDetails(response.body).searchable
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

    private fun reportTranscriptionFailure(reason: String, response: VoiceHttpResponse, token: String) {
        val details = parseAlibabaErrorDetails(response.body)
        val message = buildString {
            append("Alibaba STT ").append(reason)
            append(": httpStatus=").append(response.statusCode)
            append(", errorCode=").append(sanitizeDiagnosticValue(details.code, token))
            append(", errorMessage=").append(sanitizeDiagnosticValue(details.message, token))
            append(", responseBody=").append(safeResponseSummary(response.body, token))
        }
        runCatching { diagnostics(message) }
    }

    private fun reportTtsFailure(
        reason: String,
        response: VoiceHttpResponse?,
        token: String,
        context: AlibabaTtsDiagnosticContext,
        extra: String? = null,
    ) {
        val details = response?.let { parseAlibabaErrorDetails(it.body) } ?: AlibabaErrorDetails(null, null)
        val responseSummary = when {
            response == null -> "<unavailable>"
            response.contentType?.substringBefore(';')?.trim()?.lowercase() in ALLOWED_WAV_CONTENT_TYPES ->
                "<binary ${response.body.size} bytes>"
            else -> safeResponseSummary(response.body, token)
        }
        val message = buildString {
            append("Alibaba TTS ").append(reason)
            append(": httpStatus=").append(response?.statusCode ?: "<none>")
            append(", errorCode=").append(sanitizeDiagnosticValue(details.code, token))
            append(", errorMessage=").append(sanitizeDiagnosticValue(details.message, token))
            append(", responseBody=").append(responseSummary)
            append(", model=").append(sanitizeDiagnosticValue(context.model, token))
            append(", voice=").append(sanitizeDiagnosticValue(context.voice, token))
            append(", inputTextCharCount=").append(context.inputTextCharCount)
            append(", chunkIndex=").append(context.chunkIndex)
            append(", chunkCharCount=").append(context.chunkCharCount)
            extra?.let { append(", ").append(it) }
        }
        runCatching { diagnostics(message) }
    }

    private fun parseAlibabaErrorDetails(body: ByteArray): AlibabaErrorDetails {
        val bodyText = body.toString(StandardCharsets.UTF_8)
        val parsed = runCatching {
            json.parseToJsonElement(bodyText).jsonObject
        }.getOrNull()
        val nested = parsed?.get("error")?.let { runCatching { it.jsonObject }.getOrNull() }
        val code = parsed?.get("code")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: nested?.get("code")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: bodyText.xmlElement("Code")
        val message = parsed?.get("message")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: nested?.get("message")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: bodyText.xmlElement("Message")
        return AlibabaErrorDetails(code, message)
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
                client.transcribe(audio, model, region, config.workspaceId, config.timeout, secret)
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
                client.synthesize(
                    text = text,
                    model = model,
                    voice = voice,
                    region = region,
                    workspaceId = config.workspaceId,
                    timeout = config.timeout,
                    secret = secret,
                    requestContext = config.requestContext,
                )
            }
        }
    }
}

internal fun defaultAlibabaSttEndpoint(
    region: AlibabaSpeechRegion,
    workspaceId: String?,
    model: String,
): URI = if (isAlibabaWorkspaceAsrModel(model)) {
    val workspace = workspaceId?.takeIf(String::isNotBlank)
        ?: throw voiceError(
            VoiceErrorCode.VOICE_PROVIDER_ERROR,
            "The selected Alibaba STT model requires a Workspace ID.",
        )
    URI.create(
        "https://$workspace.${region.dedicatedRegion}.maas.aliyuncs.com/" +
            "api/v1/services/aigc/multimodal-generation/generation",
    )
} else {
    URI.create("https://${region.publicHost}/compatible-mode/v1/chat/completions")
}

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

private data class AlibabaErrorDetails(
    val code: String?,
    val message: String?,
) {
    val searchable: String = listOfNotNull(code, message).joinToString(" ").lowercase()
}

private data class AlibabaTtsDiagnosticContext(
    val model: String,
    val voice: String,
    val inputTextCharCount: Int,
    val chunkIndex: Int,
    val chunkCharCount: Int,
)

private fun isAlibabaWorkspaceAsrModel(model: String): Boolean =
    model.equals(ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL, ignoreCase = true)

private fun sanitizeDiagnosticValue(value: String?, token: String): String =
    value?.let { redactSensitiveDiagnosticText(it, token).take(MAX_DIAGNOSTIC_FIELD_CHARACTERS) } ?: "<none>"

private fun safeResponseSummary(body: ByteArray, token: String): String {
    val preview = body.copyOfRange(0, minOf(body.size, MAX_DIAGNOSTIC_SOURCE_BYTES))
    val raw = preview.toString(StandardCharsets.UTF_8)
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
    return redactSensitiveDiagnosticText(raw, token).take(MAX_DIAGNOSTIC_BODY_CHARACTERS).ifEmpty { "<empty>" }
}

private fun redactSensitiveDiagnosticText(value: String, token: String): String {
    var redacted = if (token.isBlank()) value else value.replace(token, "<redacted>")
    redacted = redacted.replace(Regex("(?i)Bearer\\s+[^\\s\\\"]+"), "Bearer <redacted>")
    redacted = redacted.replace(Regex("(?i)sk-[A-Za-z0-9_-]{8,}"), "<redacted>")
    redacted = redacted.replace(
        Regex("(?i)(\\\"(?:api[_-]?key|authorization|token|secret)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"),
        "$1<redacted>$2",
    )
    redacted = redacted.replace(
        Regex("(?i)data:audio/[a-z0-9.+-]+;base64,[a-z0-9+/=]+"),
        "<audio-data-redacted>",
    )
    redacted = redacted.replace(
        Regex("(?i)([?&](?:token|signature|x-oss-signature)=)[^&\\\"\\s]+"),
        "$1<redacted>",
    )
    return redacted.replace(
        Regex(
            "(?is)<(OSSAccessKeyId|AccessKeyId|SignatureProvided|Signature|SecurityToken|" +
                "StringToSign|StringToSignBytes)>.*?</\\1>",
        ),
        "<$1><redacted></$1>",
    )
}

private fun String.xmlElement(name: String): String? = Regex(
    "(?is)<${Regex.escape(name)}>(.*?)</${Regex.escape(name)}>",
).find(this)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)

private fun isOfficialAlibabaAudioUrl(uri: URI): Boolean =
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host?.lowercase()?.let { it == "aliyuncs.com" || it.endsWith(".aliyuncs.com") }.orFalse()

internal fun URI.upgradeOfficialAlibabaAudioUrl(): URI {
    val officialHost = host?.lowercase()?.let { it == "aliyuncs.com" || it.endsWith(".aliyuncs.com") }.orFalse()
    return if (scheme.equals("http", ignoreCase = true) && officialHost) {
        // OSS signatures cover the raw query bytes. Rebuilding from decoded URI components can
        // turn an escaped '+' into a literal '+', which form-style query parsing reads as a space.
        URI.create("https" + toASCIIString().substring("http".length))
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
private const val MAX_DIAGNOSTIC_FIELD_CHARACTERS = 256
private const val MAX_DIAGNOSTIC_BODY_CHARACTERS = 2_048
private const val MAX_DIAGNOSTIC_SOURCE_BYTES = 4_096
