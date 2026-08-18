package dev.evestaticmapplanner.sde.update

import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SdeUpdateClientTest {
    private val now = Instant.parse("2026-08-18T01:02:03Z")
    private val body = """{"_key":"sde","buildNumber":3470007,"releaseDate":"2026-08-17T11:26:56Z"}"""

    @Test
    fun `latest 200 parses and caches ETag and Last-Modified`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("latest"))
        val transport = QueueTransport(
            ResponseSpec(200, body, mapOf("ETag" to listOf("etag-1"), "Last-Modified" to listOf("date-1"))),
        )
        val result = client(paths, transport).checkLatest()

        assertEquals(3_470_007, result.buildInfo.buildNumber)
        assertFalse(result.notModified)
        val cached = LatestBuildCacheStore(paths).read()!!
        assertEquals("etag-1", cached.etag)
        assertEquals("date-1", cached.lastModified)
    }

    @Test
    fun `latest 304 reuses matching cached body and sends validators`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("latest"))
        val seed = QueueTransport(ResponseSpec(200, body, mapOf("ETag" to listOf("etag-1"))))
        client(paths, seed).checkLatest()
        val transport = QueueTransport(ResponseSpec(304, ""))

        val result = client(paths, transport).checkLatest()

        assertTrue(result.notModified)
        assertEquals("etag-1", transport.requests.single().headers["If-None-Match"])
    }

    @Test
    fun `304 without usable cache retries unconditional 200`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("latest"))
        val transport = QueueTransport(ResponseSpec(304, ""), ResponseSpec(200, body))

        val result = client(paths, transport).checkLatest()

        assertEquals(3_470_007, result.buildInfo.buildNumber)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[1].headers.isEmpty())
    }

    @Test
    fun `malformed latest and HTTP errors are explicit`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("latest"))
        assertFailsWith<SdeLatestParseException> {
            client(paths, QueueTransport(ResponseSpec(200, "not-json"))).checkLatest()
        }
        assertFailsWith<java.io.IOException> {
            client(paths, QueueTransport(ResponseSpec(500, "error"))).checkLatest()
        }
    }

    @Test
    fun `timeout and DNS transport failures are surfaced without cache fabrication`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("latest"))
        assertFailsWith<java.net.http.HttpTimeoutException> {
            client(paths, SdeHttpTransport { throw java.net.http.HttpTimeoutException("timeout") }).checkLatest()
        }
        assertFailsWith<java.io.IOException> {
            client(paths, SdeHttpTransport { throw java.io.IOException("DNS failure") }).checkLatest()
        }
        assertEquals(null, LatestBuildCacheStore(paths).read())
    }

    @Test
    fun `fixed build URI is auditable and does not use latest shortcut`() {
        val paths = ManagedStaticDataPaths(createTempDirectory("latest"))
        val uri = client(paths, QueueTransport()).fixedBuildUri(123)
        assertEquals(
            "https://developers.eveonline.com/static-data/tranquility/eve-online-static-data-123-jsonl.zip",
            uri.toString(),
        )
    }

    private fun client(paths: ManagedStaticDataPaths, transport: SdeHttpTransport) = SdeUpdateClient(
        transport,
        LatestBuildCacheStore(paths),
        Clock.fixed(now, ZoneOffset.UTC),
        URI.create("https://example.test/latest.jsonl"),
    )
}

internal data class ResponseSpec(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
)

internal class QueueTransport(vararg specs: ResponseSpec) : SdeHttpTransport {
    private val responses = ArrayDeque(specs.toList())
    val requests = mutableListOf<SdeHttpRequest>()

    override fun execute(request: SdeHttpRequest): SdeHttpResponse {
        requests += request
        val spec = responses.removeFirstOrNull() ?: error("No fake response queued")
        return SdeHttpResponse(spec.status, spec.headers, ByteArrayInputStream(spec.body.toByteArray()))
    }
}
