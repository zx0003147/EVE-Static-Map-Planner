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
            defaultAlibabaSttEndpoint(AlibabaSpeechRegion.CHINA_BEIJING).toString(),
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
    }

    @Test
    fun `official http result urls are upgraded to https before download`() {
        val original = URI.create("http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/audio.wav?token=fixture")

        val upgraded = original.upgradeOfficialAlibabaAudioUrl()

        assertEquals("https", upgraded.scheme)
        assertEquals(original.host, upgraded.host)
        assertEquals(original.query, upgraded.query)
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
        val client = AlibabaSpeechClient(sttEndpoint = { server.uri("/stt") })

        val result = runBlocking {
            SecretValue.from("alibaba-secret").use {
                client.transcribe(
                    RecordedAudio(fakeWav()),
                    "qwen3-asr-flash",
                    AlibabaSpeechRegion.CHINA_BEIJING,
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
    fun `tts sends model voice wav format and sample rate then downloads and deletes temporary audio`() =
        withServer { server ->
            val temporary = Files.createTempDirectory("alibaba-tts-test")
            try {
                var authorization: String? = null
                var requestBody = ""
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
                        )
                    }
                }

                assertEquals("Bearer alibaba-secret", authorization)
                assertContains(requestBody, "\"model\":\"qwen-audio-3.0-tts-flash\"")
                assertContains(requestBody, "\"voice\":\"longanhuan_v3.6\"")
                assertContains(requestBody, "\"format\":\"wav\"")
                assertContains(requestBody, "\"sample_rate\":24000")
                assertTrue(result.wav.contentEquals(fakeWav()))
                assertTrue(Files.list(temporary).use { it.findAny().isEmpty })
            } finally {
                temporary.toFile().deleteRecursively()
            }
        }

    @Test
    fun `alibaba maps authentication model rate and invalid voice errors without leaking key`() = withServer { server ->
        var status = 401
        var body = """{"code":"InvalidApiKey","message":"alibaba-secret"}"""
        server.createContext("/stt") { exchange -> exchange.respond(status, "application/json", body.toByteArray()) }
        val client = AlibabaSpeechClient(sttEndpoint = { server.uri("/stt") })
        fun failure(): VoiceException = assertFailsWith {
            runBlocking {
                SecretValue.from("alibaba-secret").use {
                    client.transcribe(
                        RecordedAudio(fakeWav()),
                        "qwen3-asr-flash",
                        AlibabaSpeechRegion.CHINA_BEIJING,
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
    }

    @Test
    fun `malformed success and audio responses use operation specific safe errors`() = withServer { server ->
        server.createContext("/stt") { exchange ->
            exchange.respond(200, "application/json", "not-json".toByteArray())
        }
        val client = AlibabaSpeechClient(sttEndpoint = { server.uri("/stt") })

        val failure = assertFailsWith<VoiceException> {
            runBlocking {
                SecretValue.from("secret").use {
                    client.transcribe(
                        RecordedAudio(fakeWav()),
                        DEFAULT_ALIBABA_STT_MODEL,
                        AlibabaSpeechRegion.CHINA_BEIJING,
                        Duration.ofSeconds(2),
                        it,
                    )
                }
            }
        }

        assertEquals(VoiceErrorCode.TRANSCRIPTION_FAILED, failure.code)
    }

    @Test
    fun `timeout and coroutine cancellation are distinct`() = withServer { server ->
        val started = CountDownLatch(1)
        server.createContext("/slow") { exchange ->
            started.countDown()
            Thread.sleep(2_000)
            runCatching { exchange.respond(200, "application/json", STT_SUCCESS.toByteArray()) }
        }
        val client = AlibabaSpeechClient(sttEndpoint = { server.uri("/slow") })
        val timeoutFailure = assertFailsWith<VoiceException> {
            runBlocking {
                SecretValue.from("secret").use {
                    client.transcribe(
                        RecordedAudio(fakeWav()),
                        DEFAULT_ALIBABA_STT_MODEL,
                        AlibabaSpeechRegion.CHINA_BEIJING,
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
    }
}
