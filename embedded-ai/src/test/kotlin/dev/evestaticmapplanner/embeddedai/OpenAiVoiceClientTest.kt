package dev.evestaticmapplanner.embeddedai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.evestaticmapplanner.shared.auth.SecretValue
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiVoiceClientTest {
    @Test
    fun `transcription uses independent bearer credential and multipart wav`() = withServer { server ->
        var authorization: String? = null
        var contentType: String? = null
        var requestBody = ""
        server.createContext("/v1/audio/transcriptions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.ISO_8859_1)
            exchange.respond(200, "application/json", "{\"text\":\"Jita to Amarr\"}".toByteArray())
        }
        val client = OpenAiVoiceClient(baseUri = server.baseUri())

        val result = runBlocking {
            SecretValue.from("voice-secret").use { client.transcribe(fakeWav(), "gpt-4o-mini-transcribe", it) }
        }

        assertEquals("Jita to Amarr", result)
        assertEquals("Bearer voice-secret", authorization)
        assertTrue(contentType.orEmpty().startsWith("multipart/form-data; boundary="))
        assertContains(requestBody, "name=\"model\"")
        assertContains(requestBody, "gpt-4o-mini-transcribe")
        assertContains(requestBody, "filename=\"recording.wav\"")
    }

    @Test
    fun `speech requests wav with selected current built in voice`() = withServer { server ->
        var accept: String? = null
        var requestBody = ""
        server.createContext("/v1/audio/speech") { exchange ->
            accept = exchange.requestHeaders.getFirst("Accept")
            requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            exchange.respond(200, "audio/wav", fakeWav())
        }
        val client = OpenAiVoiceClient(baseUri = server.baseUri())

        val result = runBlocking {
            SecretValue.from("voice-secret").use {
                client.synthesize("Route complete", "gpt-4o-mini-tts", "coral", it)
            }
        }

        assertTrue(result.contentEquals(fakeWav()))
        assertEquals("audio/wav", accept)
        assertContains(requestBody, "\"response_format\":\"wav\"")
        assertContains(requestBody, "\"voice\":\"coral\"")
    }

    @Test
    fun `cloud voice maps safe error categories without leaking credential`() = withServer { server ->
        var status = 401
        server.createContext("/v1/audio/transcriptions") { exchange ->
            exchange.respond(status, "application/json", "{\"error\":\"voice-secret\"}".toByteArray())
        }
        val client = OpenAiVoiceClient(baseUri = server.baseUri())
        fun failure(): VoiceException = assertFailsWith {
            runBlocking {
                SecretValue.from("voice-secret").use { client.transcribe(fakeWav(), DEFAULT_OPENAI_STT_MODEL, it) }
            }
        }

        assertEquals(VoiceErrorCode.INVALID_VOICE_CREDENTIAL, failure().code)
        status = 429
        assertEquals(VoiceErrorCode.VOICE_RATE_LIMITED, failure().code)
        status = 404
        assertEquals(VoiceErrorCode.VOICE_MODEL_NOT_FOUND, failure().code)
        assertFalse(failure().toString().contains("voice-secret"))
    }

    @Test
    fun `voice credential resolution is session then dpapi then environment`() {
        InMemoryAiCredentialStore().use { secure ->
            InMemoryAiCredentialStore().use { session ->
                SecretValue.from("secure").use { secure.save(OPENAI_VOICE_CREDENTIAL_REF, it) }
                val resolver = AiCredentialResolver(secure, session) { "environment" }
                assertEquals(AiCredentialSource.SECURE_STORAGE, resolver.source(OPENAI_VOICE_CREDENTIAL_REF, OPENAI_VOICE_ENVIRONMENT_VARIABLE))
                SecretValue.from("session").use { session.save(OPENAI_VOICE_CREDENTIAL_REF, it) }
                assertEquals(AiCredentialSource.SESSION_ONLY, resolver.source(OPENAI_VOICE_CREDENTIAL_REF, OPENAI_VOICE_ENVIRONMENT_VARIABLE))
                session.delete(OPENAI_VOICE_CREDENTIAL_REF)
                secure.delete(OPENAI_VOICE_CREDENTIAL_REF)
                assertEquals(AiCredentialSource.ENVIRONMENT, resolver.source(OPENAI_VOICE_CREDENTIAL_REF, OPENAI_VOICE_ENVIRONMENT_VARIABLE))
            }
        }
    }

    private fun fakeWav(): ByteArray = "RIFFfake-wave".toByteArray()

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

    private fun HttpServer.baseUri(): URI = URI.create("http://127.0.0.1:${address.port}/v1/")

    private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }
}
