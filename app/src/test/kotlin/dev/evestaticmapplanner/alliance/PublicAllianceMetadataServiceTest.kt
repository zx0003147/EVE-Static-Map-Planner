package dev.evestaticmapplanner.alliance

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicAllianceMetadataServiceTest {
    @Test
    fun `verified detail is cached and revalidated with ETag`() {
        val requests = mutableListOf<AllianceDetailRequest>()
        val now = Instant.parse("2026-09-23T00:00:00Z")
        val cache = createTempDirectory("alliance-cache")
        val transport = AllianceDetailTransport { request ->
            requests += request
            if (requests.size == 1) {
                AllianceDetailResponse(
                    200,
                    """{"name":"Verified Alliance","ticker":"VRFY"}""",
                    mapOf("ETag" to "v1", "Cache-Control" to "max-age=60"),
                )
            } else {
                AllianceDetailResponse(304, "", mapOf("Cache-Control" to "max-age=60"))
            }
        }
        val service = PublicAllianceMetadataService(
            cache,
            transport,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        assertEquals("VRFY", service.resolve(99)?.ticker)
        assertEquals("VRFY", service.resolve(99)?.ticker)
        assertEquals(1, requests.size)

        val revalidating = PublicAllianceMetadataService(
            cache,
            transport,
            Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC),
        )
        assertEquals("VRFY", revalidating.resolve(99)?.ticker)
        assertEquals("v1", requests.last().etag)
    }

    @Test
    fun `temporary failure returns last good metadata after cache expiry`() {
        val cache = createTempDirectory("alliance-last-good")
        val first = PublicAllianceMetadataService(
            cache,
            AllianceDetailTransport {
                AllianceDetailResponse(200, """{"name":"Last Good","ticker":"GOOD"}""", mapOf("Cache-Control" to "max-age=0"))
            },
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )
        assertEquals("GOOD", first.resolve(55)?.ticker)

        val offline = PublicAllianceMetadataService(
            cache,
            AllianceDetailTransport { error("offline") },
            Clock.fixed(Instant.EPOCH.plusSeconds(1), ZoneOffset.UTC),
        )
        assertEquals("GOOD", offline.resolve(55)?.ticker)
    }
}
