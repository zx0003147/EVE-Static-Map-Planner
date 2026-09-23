package dev.evestaticmapplanner.alliance

import dev.evestaticmapplanner.core.alliance.AllianceReference
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AllianceDetailRequest(
    val allianceId: Long,
    val etag: String? = null,
    val lastModified: String? = null,
)

data class AllianceDetailResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface AllianceDetailTransport {
    fun execute(request: AllianceDetailRequest): AllianceDetailResponse
}

class JdkAllianceDetailTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : AllianceDetailTransport {
    override fun execute(request: AllianceDetailRequest): AllianceDetailResponse {
        val builder = HttpRequest.newBuilder(
            URI.create("https://esi.evetech.net/alliances/${request.allianceId}"),
        )
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", "EVE-Static-Map-Planner/AllianceDirectory")
            .header("X-Compatibility-Date", ESI_COMPATIBILITY_DATE)
            .GET()
        request.etag?.let { builder.header("If-None-Match", it) }
        request.lastModified?.let { builder.header("If-Modified-Since", it) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return AllianceDetailResponse(
            response.statusCode(),
            response.body(),
            response.headers().map().mapValues { it.value.firstOrNull().orEmpty() },
        )
    }

    private companion object {
        const val ESI_COMPATIBILITY_DATE = "2026-08-28"
    }
}

/** Public, credential-free Alliance Detail lookup with conditional last-good disk caching. */
class PublicAllianceMetadataService(
    private val cacheDirectory: Path,
    private val transport: AllianceDetailTransport = JdkAllianceDetailTransport(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun resolve(allianceId: Long): AllianceReference? {
        require(allianceId > 0) { "Alliance ID must be positive" }
        val cached = load(allianceId)
        if (cached != null && clock.instant().isBefore(cached.expiresAt)) return cached.alliance
        return runCatching {
            val response = transport.execute(AllianceDetailRequest(allianceId, cached?.etag, cached?.lastModified))
            when (response.statusCode) {
                200 -> parse(allianceId, response.body).also { alliance ->
                    save(CacheRecord(alliance, response.header("ETag"), response.header("Last-Modified"), expiry(response)))
                }
                304 -> cached?.also { record -> save(record.copy(expiresAt = expiry(response))) }?.alliance
                404 -> null
                else -> cached?.alliance
            }
        }.getOrElse { cached?.alliance }
    }

    private fun parse(allianceId: Long, body: String): AllianceReference {
        val objectValue = JSON.parseToJsonElement(body).jsonObject
        val name = objectValue["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val ticker = objectValue["ticker"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(name.isNotEmpty() && ticker.isNotEmpty()) { "Alliance Detail omitted name or ticker" }
        return AllianceReference(allianceId, name, ticker)
    }

    private fun expiry(response: AllianceDetailResponse): Instant {
        val maxAge = response.header("Cache-Control")
            ?.split(',')
            ?.map(String::trim)
            ?.firstNotNullOfOrNull { directive ->
                directive.substringAfter("max-age=", "").toLongOrNull()?.takeIf { directive.startsWith("max-age=") }
            }
        if (maxAge != null) return clock.instant().plusSeconds(maxAge.coerceAtLeast(0))
        val expires = response.header("Expires")?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        }
        return expires ?: clock.instant().plus(DEFAULT_CACHE_DURATION)
    }

    private fun load(allianceId: Long): CacheRecord? = runCatching {
        val path = cachePath(allianceId)
        if (!Files.isRegularFile(path)) return@runCatching null
        val properties = Properties().apply { Files.newInputStream(path).use(::load) }
        CacheRecord(
            alliance = AllianceReference(
                allianceId,
                properties.getProperty("name"),
                properties.getProperty("ticker"),
            ),
            etag = properties.getProperty("etag"),
            lastModified = properties.getProperty("lastModified"),
            expiresAt = Instant.parse(properties.getProperty("expiresAt")),
        )
    }.getOrNull()

    private fun save(record: CacheRecord) {
        Files.createDirectories(cacheDirectory)
        val target = cachePath(record.alliance.allianceId)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        val properties = Properties().apply {
            setProperty("name", record.alliance.name.orEmpty())
            setProperty("ticker", record.alliance.ticker.orEmpty())
            record.etag?.let { setProperty("etag", it) }
            record.lastModified?.let { setProperty("lastModified", it) }
            setProperty("expiresAt", record.expiresAt.toString())
        }
        Files.newOutputStream(temporary).use { properties.store(it, "Public ESI Alliance Detail cache") }
        runCatching {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cachePath(allianceId: Long) = cacheDirectory.resolve("$allianceId.properties")

    private data class CacheRecord(
        val alliance: AllianceReference,
        val etag: String?,
        val lastModified: String?,
        val expiresAt: Instant,
    )

    private companion object {
        val DEFAULT_CACHE_DURATION: Duration = Duration.ofHours(1)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
