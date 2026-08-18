package dev.evestaticmapplanner.sde.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Serializable
data class SdeBuildInfo(
    val buildNumber: Long,
    val releaseDate: String? = null,
)

@Serializable
data class LatestBuildCache(
    val rawBody: String,
    val buildInfo: SdeBuildInfo,
    val etag: String? = null,
    val lastModified: String? = null,
    val checkedAt: String,
)

data class LatestBuildResult(
    val buildInfo: SdeBuildInfo,
    val checkedAt: Instant,
    val notModified: Boolean,
)

class LatestBuildCacheStore(
    private val paths: ManagedStaticDataPaths,
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false },
) {
    private val cachePath get() = paths.cacheDirectory.resolve("latest.json")

    fun read(): LatestBuildCache? = if (Files.isRegularFile(cachePath)) {
        runCatching { json.decodeFromString<LatestBuildCache>(Files.readString(cachePath)) }.getOrNull()
    } else null

    fun write(cache: LatestBuildCache) {
        Files.createDirectories(paths.cacheDirectory)
        val part = cachePath.resolveSibling("latest.json.part")
        Files.writeString(part, json.encodeToString(cache), StandardCharsets.UTF_8)
        movePublished(part, cachePath)
    }
}

class SdeLatestParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object SdeLatestParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): SdeBuildInfo {
        body.lineSequence().filter(String::isNotBlank).forEach { line ->
            val record = try {
                json.parseToJsonElement(line) as? JsonObject
            } catch (error: Throwable) {
                throw SdeLatestParseException("Malformed latest.jsonl", error)
            } ?: throw SdeLatestParseException("latest.jsonl record is not an object")
            if (record["_key"]?.jsonPrimitive?.content == "sde") {
                val buildText = record["buildNumber"]?.jsonPrimitive?.content
                    ?: record["_value"]?.jsonPrimitive?.content
                    ?: throw SdeLatestParseException("SDE latest record has no build number")
                val build = buildText.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw SdeLatestParseException("SDE build number is invalid: $buildText")
                return SdeBuildInfo(build, record["releaseDate"]?.jsonPrimitive?.content)
            }
        }
        throw SdeLatestParseException("latest.jsonl has no _key=sde record")
    }
}

class SdeUpdateClient(
    private val transport: SdeHttpTransport,
    private val cacheStore: LatestBuildCacheStore,
    private val clock: Clock = Clock.systemUTC(),
    private val latestUri: URI = URI.create(LATEST_URL),
) {
    fun checkLatest(): LatestBuildResult {
        val cache = cacheStore.read()
        val conditionalHeaders = buildMap {
            cache?.etag?.let { put("If-None-Match", it) }
            cache?.lastModified?.let { put("If-Modified-Since", it) }
        }
        return requestLatest(conditionalHeaders, cache, allowUnconditionalRetry = true)
    }

    fun fixedBuildUri(build: Long): URI {
        require(build > 0)
        return URI.create("https://developers.eveonline.com/static-data/tranquility/eve-online-static-data-$build-jsonl.zip")
    }

    private fun requestLatest(
        headers: Map<String, String>,
        cache: LatestBuildCache?,
        allowUnconditionalRetry: Boolean,
    ): LatestBuildResult {
        transport.execute(SdeHttpRequest(latestUri, headers, Duration.ofSeconds(30))).use { response ->
            val checkedAt = Instant.now(clock)
            if (response.statusCode == 304) {
                if (cache != null && runCatching { SdeLatestParser.parse(cache.rawBody) }.getOrNull() == cache.buildInfo) {
                    cacheStore.write(cache.copy(checkedAt = checkedAt.toString()))
                    return LatestBuildResult(cache.buildInfo, checkedAt, true)
                }
                if (allowUnconditionalRetry) return requestLatest(emptyMap(), null, false)
                throw IOException("CCP returned 304 without usable cached latest data")
            }
            if (response.statusCode !in 200..299) throw IOException("Latest build request failed with HTTP ${response.statusCode}")
            val bodyBytes = response.body.readNBytes(MAX_LATEST_BYTES + 1)
            if (bodyBytes.size > MAX_LATEST_BYTES) throw IOException("latest.jsonl exceeds safety limit")
            val body = bodyBytes.toString(StandardCharsets.UTF_8)
            val info = SdeLatestParser.parse(body)
            cacheStore.write(
                LatestBuildCache(
                    rawBody = body,
                    buildInfo = info,
                    etag = response.firstHeader("ETag"),
                    lastModified = response.firstHeader("Last-Modified"),
                    checkedAt = checkedAt.toString(),
                ),
            )
            return LatestBuildResult(info, checkedAt, false)
        }
    }

    companion object {
        const val LATEST_URL = "https://developers.eveonline.com/static-data/tranquility/latest.jsonl"
        private const val MAX_LATEST_BYTES = 64 * 1024
    }
}

private typealias IOException = java.io.IOException
