package dev.evestaticmapplanner.embeddedai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlibabaSpeechClientTest {
    @Test
    fun `official endpoints use public domains by default and workspace domains only when required`() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            defaultAlibabaSttEndpoint(
                AlibabaSpeechRegion.CHINA_BEIJING,
                null,
                DEFAULT_ALIBABA_STT_MODEL,
            ).toString(),
        )
        assertEquals(
            "https://fixture-workspace.cn-beijing.maas.aliyuncs.com/" +
                "api/v1/services/aigc/multimodal-generation/generation",
            defaultAlibabaSttEndpoint(
                AlibabaSpeechRegion.CHINA_BEIJING,
                "fixture-workspace",
                ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL,
            ).toString(),
        )
        assertEquals(
            "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            defaultAlibabaTtsEndpoint(AlibabaSpeechRegion.SINGAPORE, null, DEFAULT_ALIBABA_TTS_MODEL).toString(),
        )
        assertEquals(
            "https://fixture-workspace.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer",
            defaultAlibabaTtsEndpoint(
                AlibabaSpeechRegion.CHINA_BEIJING,
                "fixture-workspace",
                "qwen-audio-3.0-tts-flash",
            ).toString(),
        )

        val missingWorkspace = assertFailsWith<VoiceException> {
            defaultAlibabaSttEndpoint(
                AlibabaSpeechRegion.CHINA_BEIJING,
                null,
                ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL,
            )
        }
        assertEquals(VoiceErrorCode.VOICE_PROVIDER_ERROR, missingWorkspace.code)
    }

    @Test
    fun `official http result urls are upgraded to https before download`() {
        val original = URI.create(
            "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/audio.wav?" +
                "OSSAccessKeyId=fixture&Signature=fa%2B3Hu%2B9v8%3D&token=fixture",
        )

        val upgraded = original.upgradeOfficialAlibabaAudioUrl()

        assertEquals("https", upgraded.scheme)
        assertEquals(original.host, upgraded.host)
        assertEquals(original.rawQuery, upgraded.rawQuery)
        assertContains(upgraded.toASCIIString(), "Signature=fa%2B3Hu%2B9v8%3D")
    }

    @Test
    fun `alibaba recognition config carries workspace id to provider`() {
        val config = VoiceConfig(
            inputProvider = VoiceInputProvider.ALIBABA,
            profiles = SpeechProviderProfiles(
                alibaba = AlibabaSpeechProfile(
                    sttModel = ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL,
                    workspaceId = "fixture-workspace",
                ),
            ),
        ).recognitionConfig()

        assertEquals(ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL, config.model)
        assertEquals(AlibabaSpeechRegion.CHINA_BEIJING, config.region)
        assertEquals("fixture-workspace", config.workspaceId)
    }

    @Test
    fun `stt sends authorized base64 wav to selected endpoint and parses transcript`() = withServer { server ->
        var authorization: String? = null
        var contentType: String? = null
        var requestBody = ""
        server.createContext("/stt") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            exchange.respond(200, "application/json", STT_SUCCESS.toByteArray())
        }
        val client = AlibabaSpeechClient(sttEndpoint = { _, _, _ -> server.uri("/stt") })

        val result = runBlocking {
            SecretValue.from("alibaba-secret").use {
                client.transcribe(
                    RecordedAudio(fakeWav()),
                    "qwen3-asr-flash",
                    AlibabaSpeechRegion.CHINA_BEIJING,
                    null,
                    Duration.ofSeconds(2),
                    it,
                )
            }
        }

        assertEquals("你好，这是语音识别测试。", result.text)
        assertEquals("Bearer alibaba-secret", authorization)
        assertEquals("application/json", contentType)
        assertContains(requestBody, "\"model\":\"qwen3-asr-flash\"")
        assertContains(requestBody, "data:audio/wav;base64,")
        assertContains(requestBody, "\"enable_itn\":false")
        assertFalse(requestBody.contains("hotword", ignoreCase = true))
    }

    @Test
    fun `qwen audio 3 asr uses workspace sync fixture with matching wav format and sample rate`() =
        withServer { server ->
            var endpointRegion: AlibabaSpeechRegion? = null
            var endpointWorkspace: String? = null
            var endpointModel: String? = null
            var authorization: String? = null
            var sseMode: String? = null
            var requestBody = ""
            server.createContext("/qwen-audio-stt") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                sseMode = exchange.requestHeaders.getFirst("X-DashScope-SSE")
                requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                exchange.respond(200, "application/json", QWEN_AUDIO_STT_SUCCESS.toByteArray())
            }
            val client = AlibabaSpeechClient(
                sttEndpoint = { region, workspaceId, model ->
                    endpointRegion = region
                    endpointWorkspace = workspaceId
                    endpointModel = model
                    server.uri("/qwen-audio-stt")
                },
            )

            val result = runBlocking {
                SecretValue.from("alibaba-secret").use {
                    client.transcribe(
                        audio = RecordedAudio(fakeWav(), sampleRateHz = 22_050),
                        model = ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL,
                        region = AlibabaSpeechRegion.CHINA_BEIJING,
                        workspaceId = "fixture-workspace",
                        timeout = Duration.ofSeconds(2),
                        secret = it,
                    )
                }
            }

            assertEquals("这是百炼同步接口识别结果。", result.text)
            assertEquals(AlibabaSpeechRegion.CHINA_BEIJING, endpointRegion)
            assertEquals("fixture-workspace", endpointWorkspace)
            assertEquals(ALIBABA_QWEN_AUDIO_ASR_FLASH_MODEL, endpointModel)
            assertEquals("Bearer alibaba-secret", authorization)
            assertEquals("disable", sseMode)
            assertContains(requestBody, "\"model\":\"qwen-audio-3.0-asr-flash\"")
            assertContains(requestBody, "\"input\":{\"messages\"")
            assertContains(requestBody, "\"type\":\"input_audio\"")
            assertContains(requestBody, "\"data\":\"data:audio/wav;base64,")
            assertContains(requestBody, "\"parameters\":{\"format\":\"wav\",\"sample_rate\":\"22050\"")
            assertFalse(requestBody.contains("\"choices\""))
        }

    @Test
    fun `tts sends model voice wav format and sample rate then downloads and deletes temporary audio`() =
        withServer { server ->
            val temporary = Files.createTempDirectory("alibaba-tts-test")
            try {
                var authorization: String? = null
                var requestBody = ""
                val trace = mutableListOf<TtsProviderHttpEvent>()
                server.createContext("/tts") { exchange ->
                    authorization = exchange.requestHeaders.getFirst("Authorization")
                    requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                    val response = """{"output":{"audio":{"url":"${server.uri("/audio.wav")}"}}}"""
                    exchange.respond(200, "application/json", response.toByteArray())
                }
                server.createContext("/audio.wav") { exchange ->
                    exchange.respond(200, "audio/wav", fakeWav())
                }
                val client = AlibabaSpeechClient(
                    ttsEndpoint = { _, _, _ -> server.uri("/tts") },
                    audioUrlValidator = { it.host == "127.0.0.1" },
                    temporaryDirectory = temporary,
                )

                val result = runBlocking {
                    SecretValue.from("alibaba-secret").use {
                        client.synthesize(
                            text = "你好，这是语音合成测试。",
                            model = "qwen-audio-3.0-tts-flash",
                            voice = "longanhuan_v3.6",
                            region = AlibabaSpeechRegion.CHINA_BEIJING,
                            workspaceId = "fixture-workspace",
                            timeout = Duration.ofSeconds(2),
                            secret = it,
                            requestContext = TtsRequestContext(
                                messageId = "message-1",
                                chunkId = "message-1:1",
                                chunkIndex = 1,
                                rawStartOffset = 0,
                                rawEndOffset = 12,
                                providerTraceSink = TtsProviderTraceSink(trace::add),
                            ),
                        )
                    }
                }

                assertEquals("Bearer alibaba-secret", authorization)
                assertContains(requestBody, "\"model\":\"qwen-audio-3.0-tts-flash\"")
                assertContains(requestBody, "\"voice\":\"longanhuan_v3.6\"")
                assertContains(requestBody, "\"format\":\"wav\"")
                assertContains(requestBody, "\"sample_rate\":24000")
                assertTrue(result.wav.contentEquals(fakeWav()))
                assertEquals(
                    listOf(
                        TtsProviderHttpStage.SYNTHESIS to TtsProviderHttpEventType.REQUEST_STARTED,
                        TtsProviderHttpStage.SYNTHESIS to TtsProviderHttpEventType.RESPONSE_RECEIVED,
                        TtsProviderHttpStage.AUDIO_DOWNLOAD to TtsProviderHttpEventType.REQUEST_STARTED,
                        TtsProviderHttpStage.AUDIO_DOWNLOAD to TtsProviderHttpEventType.RESPONSE_RECEIVED,
                    ),
                    trace.map { it.stage to it.type },
                )
                assertEquals(listOf(null, 200, null, 200), trace.map(TtsProviderHttpEvent::statusCode))
                assertTrue(Files.list(temporary).use { it.findAny().isEmpty })
            } finally {
                temporary.toFile().deleteRecursively()
            }
        }

    @Test
    fun `tts non success logs safe provider and chunk diagnostics`() = withServer { server ->
        val diagnostics = mutableListOf<String>()
        server.createContext("/tts-error") { exchange ->
            exchange.respond(
                400,
                "application/json",
                """{"code":"InvalidParameter","message":"voice rejected alibaba-secret"}""".toByteArray(),
            )
        }
        val client = AlibabaSpeechClient(
            ttsEndpoint = { _, _, _ -> server.uri("/tts-error") },
            diagnostics = diagnostics::add,
        )
        val input = "Route from Atioth to L-TOFR is 24 jumps."

        val failure = assertFailsWith<VoiceException> {
            runBlocking {
                SecretValue.from("alibaba-secret").use {
                    client.synthesize(
                        text = input,
                        model = "qwen-audio-3.0-tts-flash",
                        voice = "longanfengyue",
                        region = AlibabaSpeechRegion.CHINA_BEIJING,
                        workspaceId = "fixture-workspace",
                        timeout = Duration.ofSeconds(2),
                        secret = it,
                        requestContext = TtsRequestContext("message-7", "message-7:3", 3, 120, 164),
                    )
                }
            }
        }

        assertEquals(VoiceErrorCode.VOICE_MODEL_UNAVAILABLE, failure.code)
        val log = diagnostics.single()
        assertContains(log, "Alibaba TTS request failed")
        assertContains(log, "httpStatus=400")
        assertContains(log, "errorCode=InvalidParameter")
        assertContains(log, "model=qwen-audio-3.0-tts-flash")
        assertContains(log, "voice=longanfengyue")
        assertContains(log, "inputTextCharCount=${input.codePointCount(0, input.length)}")
        assertContains(log, "chunkIndex=3")
        assertFalse(log.contains("alibaba-secret"))
        assertContains(log, "<redacted>")
    }

    @Test
    fun `oss xml download diagnostics redact access key signature and signing material`() = withServer { server ->
        val diagnostics = mutableListOf<String>()
        server.createContext("/tts-signed") { exchange ->
            val response = """{"output":{"audio":{"url":"${server.uri("/signed.wav")}"}}}"""
            exchange.respond(200, "application/json", response.toByteArray())
        }
        server.createContext("/signed.wav") { exchange ->
            val response = """
                <Error>
                  <Code>SignatureDoesNotMatch</Code>
                  <Message>signature mismatch</Message>
                  <OSSAccessKeyId>temporary-access-key</OSSAccessKeyId>
                  <SignatureProvided>sensitive-signature</SignatureProvided>
                  <StringToSign>sensitive-signing-material</StringToSign>
                </Error>
            """.trimIndent()
            exchange.respond(403, "application/xml", response.toByteArray())
        }
        val client = AlibabaSpeechClient(
            diagnostics = diagnostics::add,
            ttsEndpoint = { _, _, _ -> server.uri("/tts-signed") },
            audioUrlValidator = { it.host == "127.0.0.1" },
        )

        val failure = assertFailsWith<VoiceException> {
            runBlocking {
                SecretValue.from("alibaba-secret").use {
                    client.synthesize(
                        text = "diagnostic",
                        model = "qwen-audio-3.0-tts-flash",
                        voice = "longanfengyue",
                        region = AlibabaSpeechRegion.CHINA_BEIJING,
                        workspaceId = "fixture-workspace",
                        timeout = Duration.ofSeconds(2),
                        secret = it,
                    )
                }
            }
        }

        assertEquals(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, failure.code)
        val log = diagnostics.single()
        assertContains(log, "errorCode=SignatureDoesNotMatch")
        assertContains(log, "errorMessage=signature mismatch")
        assertFalse(log.contains("temporary-access-key"))
        assertFalse(log.contains("sensitive-signature"))
        assertFalse(log.contains("sensitive-signing-material"))
        assertContains(log, "<redacted>")
    }

    @Test
    fun `tts missing audio url and invalid downloaded bytes produce stage specific diagnostics`() =
        withServer { server ->
            val diagnostics = mutableListOf<String>()
            var validUrlResponse = false
            server.createContext("/tts-stage") { exchange ->
                val body = if (validUrlResponse) {
                    """{"output":{"audio":{"url":"${server.uri("/invalid.wav")}"}}}"""
                } else {
                    """{"output":{"finish_reason":"stop"},"request_id":"fixture"}"""
                }
                exchange.respond(200, "application/json", body.toByteArray())
            }
            server.createContext("/invalid.wav") { exchange ->
                exchange.respond(200, "audio/wav", "not-a-wave".toByteArray())
            }
            val client = AlibabaSpeechClient(
                ttsEndpoint = { _, _, _ -> server.uri("/tts-stage") },
                audioUrlValidator = { it.host == "127.0.0.1" },
                diagnostics = diagnostics::add,
            )
            fun failure(): VoiceException = assertFailsWith {
                runBlocking {
                    SecretValue.from("alibaba-secret").use {
                        client.synthesize(
                            text = "Fixture chunk.",
                            model = "qwen-audio-3.0-tts-flash",
                            voice = "longanfengyue",
                            region = AlibabaSpeechRegion.CHINA_BEIJING,
                            workspaceId = "fixture-workspace",
                            timeout = Duration.ofSeconds(2),
                            secret = it,
                            requestContext = TtsRequestContext("message", "message:4", 4, 10, 24),
                        )
                    }
                }
            }

            assertEquals(VoiceErrorCode.SYNTHESIS_FAILED, failure().code)
            assertContains(diagnostics.single(), "audio URL parsing failed: httpStatus=200")
            validUrlResponse = true
            diagnostics.clear()
            assertEquals(VoiceErrorCode.AUDIO_DOWNLOAD_FAILED, failure().code)
            assertContains(diagnostics.single(), "audio download returned invalid bytes: httpStatus=200")
            assertContains(diagnostics.single(), "responseBody=<binary 10 bytes>")
            assertContains(diagnostics.single(), "chunkIndex=4")
        }

    @Test
    fun `alibaba maps authentication model rate and invalid voice errors without leaking key`() = withServer { server ->
        var status = 401
        var body = """{"code":"InvalidApiKey","message":"alibaba-secret"}"""
        server.createContext("/stt") { exchange -> exchange.respond(status, "application/json", body.toByteArray()) }
        val diagnostics = mutableListOf<String>()
        val client = AlibabaSpeechClient(
            sttEndpoint = { _, _, _ -> server.uri("/stt") },
            diagnostics = diagnostics::add,
        )
        fun failure(): VoiceException = assertFailsWith {
            runBlocking {
                SecretValue.from("alibaba-secret").use {
                    client.transcribe(
                        RecordedAudio(fakeWav()),
                        "qwen3-asr-flash",
                        AlibabaSpeechRegion.CHINA_BEIJING,
                        null,
                        Duration.ofSeconds(2),
                        it,
                    )
                }
            }
        }

        assertEquals(VoiceErrorCode.INVALID_VOICE_CREDENTIAL, failure().code)
        status = 404
        body = """{"code":"model_not_found"}"""
        assertEquals(VoiceErrorCode.VOICE_MODEL_NOT_FOUND, failure().code)
        status = 429
        body = """{"code":"Throttling.RateQuota"}"""
        assertEquals(VoiceErrorCode.VOICE_RATE_LIMITED, failure().code)
        status = 400
        body = """{"code":"InvalidParameter","message":"voice is invalid"}"""
        assertEquals(VoiceErrorCode.VOICE_MODEL_UNAVAILABLE, failure().code)
        assertFalse(failure().toString().contains("alibaba-secret"))
        assertTrue(diagnostics.any { "httpStatus=401" in it && "errorCode=InvalidApiKey" in it })
        assertTrue(diagnostics.any { "responseBody=" in it })
        assertFalse(diagnostics.any { "alibaba-secret" in it })
    }

    @Test
    fun `malformed success and audio responses use operation specific safe errors`() = withServer { server ->
        server.createContext("/stt") { exchange ->
            exchange.respond(200, "application/json", "not-json".toByteArray())
        }
        val diagnostics = mutableListOf<String>()
        val client = AlibabaSpeechClient(
            sttEndpoint = { _, _, _ -> server.uri("/stt") },
            diagnostics = diagnostics::add,
        )

        val failure = assertFailsWith<VoiceException> {
            runBlocking {
                SecretValue.from("secret").use {
                    client.transcribe(
                        RecordedAudio(fakeWav()),
                        DEFAULT_ALIBABA_STT_MODEL,
                        AlibabaSpeechRegion.CHINA_BEIJING,
                        null,
                        Duration.ofSeconds(2),
                        it,
                    )
                }
            }
        }

        assertEquals(VoiceErrorCode.TRANSCRIPTION_FAILED, failure.code)
        assertTrue(diagnostics.single().contains("response parsing failed: httpStatus=200"))
        assertTrue(diagnostics.single().contains("responseBody=not-json"))
    }

    @Test
    fun `timeout and coroutine cancellation are distinct`() = withServer { server ->
        val started = CountDownLatch(1)
        server.createContext("/slow") { exchange ->
            started.countDown()
            Thread.sleep(2_000)
            runCatching { exchange.respond(200, "application/json", STT_SUCCESS.toByteArray()) }
        }
        val client = AlibabaSpeechClient(sttEndpoint = { _, _, _ -> server.uri("/slow") })
        val timeoutFailure = assertFailsWith<VoiceException> {
            runBlocking {
                SecretValue.from("secret").use {
                    client.transcribe(
                        RecordedAudio(fakeWav()),
                        DEFAULT_ALIBABA_STT_MODEL,
                        AlibabaSpeechRegion.CHINA_BEIJING,
                        null,
                        Duration.ofMillis(30),
                        it,
                    )
                }
            }
        }
        assertEquals(VoiceErrorCode.VOICE_TIMEOUT, timeoutFailure.code)

        runBlocking {
            val job = async(Dispatchers.IO) {
                SecretValue.from("secret").use {
                    client.transcribe(
                        RecordedAudio(fakeWav()),
                        DEFAULT_ALIBABA_STT_MODEL,
                        AlibabaSpeechRegion.CHINA_BEIJING,
                        null,
                        Duration.ofSeconds(10),
                        it,
                    )
                }
            }
            withContext(Dispatchers.IO) { started.await(1, TimeUnit.SECONDS) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }

    private fun fakeWav(): ByteArray = ByteArray(48).apply {
        "RIFF".toByteArray().copyInto(this, 0)
        "WAVE".toByteArray().copyInto(this, 8)
    }

    private fun <T> withServer(block: (HttpServer) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        return try {
            block(server)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpServer.uri(path: String): URI = URI.create("http://127.0.0.1:${address.port}$path")

    private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private companion object {
        const val STT_SUCCESS =
            "{\"choices\":[{\"message\":{\"content\":\"你好，这是语音识别测试。\"}}]}"
        const val QWEN_AUDIO_STT_SUCCESS =
            "{\"output\":{\"text\":\"这是百炼同步接口识别结果。\"},\"request_id\":\"fixture-request\"}"
    }
}
