package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.evestaticmapplanner.AppDiagnostics
import dev.evestaticmapplanner.ApplicationDirectories
import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapTransform
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Image

data class PresentationEmblemReference(
    val key: String,
    val url: String,
)

data class PresentedFeatureEmblemCandidate(
    val componentKey: String,
    val reference: PresentationEmblemReference,
    val anchor: MapPoint,
    val bounds: MapBounds,
    val mapArea: Double,
    val systemCount: Int,
    val boundaryClearance: Double,
    val clipTerritory: PresentedFeatureTerritory,
)

internal data class FeatureOverlayEmblemPlacement(
    val candidate: PresentedFeatureEmblemCandidate,
    val anchor: MapPoint,
    val sizePx: Float,
    val alpha: Float,
)

data class PresentedFeatureEmblem(
    val anchor: MapPoint,
    val image: ImageBitmap,
    val sizePx: Float,
    val alpha: Float,
    val clipTerritory: PresentedFeatureTerritory,
)

internal data class FeatureOverlayEmblemZoomPolicy(
    val emphasisZoom: Double,
) {
    init {
        require(emphasisZoom.isFinite() && emphasisZoom > 0.0)
    }

    companion object {
        val Default = FeatureOverlayEmblemZoomPolicy(EMBLEM_DEFAULT_EMPHASIS_ZOOM)
    }
}

internal object FeatureOverlayEmblemLod {
    fun alpha(
        zoom: Double,
        policy: FeatureOverlayEmblemZoomPolicy = FeatureOverlayEmblemZoomPolicy.Default,
    ): Float {
        val backgroundFullAlphaZoom = policy.emphasisZoom * EMBLEM_BACKGROUND_FULL_ALPHA_ZOOM_FACTOR
        val detailHideZoom = detailHideZoom(policy)
        if (zoom >= detailHideZoom) return 0f
        val backgroundAlpha = if (zoom >= backgroundFullAlphaZoom) {
            val progress = smoothStep(
                (detailHideZoom - zoom) / (detailHideZoom - backgroundFullAlphaZoom),
            )
            EMBLEM_BACKGROUND_ALPHA * progress
        } else {
            EMBLEM_BACKGROUND_ALPHA
        }
        val emphasisProgress = emphasisProgress(zoom, policy)
        return (backgroundAlpha + (EMBLEM_MAX_ALPHA - backgroundAlpha) * emphasisProgress).toFloat()
    }

    fun placements(
        candidates: List<PresentedFeatureEmblemCandidate>,
        transform: MapTransform,
        policy: FeatureOverlayEmblemZoomPolicy = FeatureOverlayEmblemZoomPolicy.Default,
    ): List<FeatureOverlayEmblemPlacement> {
        val alpha = alpha(transform.viewport.zoom, policy)
        if (alpha <= 0f) return emptyList()
        val zoom = transform.viewport.zoom
        val sizeScale = 1.0 + EMBLEM_FAR_SIZE_BOOST * emphasisProgress(zoom, policy)
        val visibleBounds = transform.visibleWorldBounds()
        return candidates.asSequence()
            .filter { it.bounds.intersects(visibleBounds) }
            .mapNotNull { candidate ->
                val projectedArea = candidate.mapArea * zoom * zoom
                val preferredSize = (sqrt(projectedArea) * EMBLEM_AREA_SIZE_FACTOR * sizeScale)
                    .coerceIn(EMBLEM_MIN_SIZE_PX, EMBLEM_MAX_SIZE_PX)
                val componentLimit = sqrt(candidate.bounds.width * candidate.bounds.height) *
                    zoom * EMBLEM_BOUNDS_SIZE_FACTOR
                val clearanceLimit = maxOf(
                    EMBLEM_MIN_RENDERED_SIZE_PX,
                    candidate.boundaryClearance * zoom * EMBLEM_CLEARANCE_DIAMETER_FACTOR,
                )
                val size = minOf(preferredSize, componentLimit, clearanceLimit)
                if (!size.isFinite() || size < EMBLEM_MIN_RENDERED_SIZE_PX) return@mapNotNull null
                FeatureOverlayEmblemPlacement(candidate, candidate.anchor, size.toFloat(), alpha)
            }
            .filter { it.intersectsCanvas(transform) }
            .sortedWith(
                compareByDescending<FeatureOverlayEmblemPlacement> {
                    it.candidate.mapArea * zoom * zoom
                }.thenBy { it.candidate.componentKey },
            )
            .toList()
    }

    private fun emphasisProgress(zoom: Double, policy: FeatureOverlayEmblemZoomPolicy): Double {
        val transitionHalfWidth = policy.emphasisZoom * EMBLEM_EMPHASIS_TRANSITION_HALF_WIDTH_FACTOR
        val transitionStart = policy.emphasisZoom + transitionHalfWidth
        return smoothStep(
            (transitionStart - zoom) / (transitionHalfWidth * 2.0),
        )
    }

    internal fun detailHideZoom(policy: FeatureOverlayEmblemZoomPolicy): Double =
        policy.emphasisZoom * EMBLEM_DETAIL_HIDE_ZOOM_FACTOR

    fun readyEmblems(
        placements: List<FeatureOverlayEmblemPlacement>,
        states: Map<String, PresentationEmblemAssetState<ImageBitmap>>,
    ): List<PresentedFeatureEmblem> = placements.mapNotNull { placement ->
        val ready = states[placement.candidate.reference.key]
            as? PresentationEmblemAssetState.Ready<ImageBitmap> ?: return@mapNotNull null
        PresentedFeatureEmblem(
            anchor = placement.anchor,
            image = ready.asset,
            sizePx = placement.sizePx,
            alpha = placement.alpha,
            clipTerritory = placement.candidate.clipTerritory,
        )
    }

    private fun smoothStep(value: Double): Double {
        val clamped = value.coerceIn(0.0, 1.0)
        return clamped * clamped * (3.0 - 2.0 * clamped)
    }
}

/**
 * Keeps the capped emblem set stable while the viewport pans at one zoom level.
 * Eligible emblems are released only after their rendered rectangle leaves the canvas;
 * a zoom change intentionally starts a fresh priority selection.
 */
internal class StableFeatureOverlayEmblemSelector(
    private val capacity: Int = MAX_VISIBLE_EMBLEM_REQUESTS,
) {
    private var selectedZoom: Double? = null
    private var selectedComponentKeys = emptySet<String>()

    init {
        require(capacity > 0)
    }

    fun select(
        eligiblePlacements: List<FeatureOverlayEmblemPlacement>,
        zoom: Double,
    ): List<FeatureOverlayEmblemPlacement> {
        require(zoom.isFinite() && zoom > 0.0)
        if (selectedZoom != zoom) {
            selectedZoom = zoom
            selectedComponentKeys = emptySet()
        }

        val eligibleKeys = eligiblePlacements.asSequence()
            .map { it.candidate.componentKey }
            .toSet()
        val nextKeys = LinkedHashSet<String>(capacity)
        selectedComponentKeys.forEach { key ->
            if (key in eligibleKeys && nextKeys.size < capacity) nextKeys += key
        }
        eligiblePlacements.forEach { placement ->
            if (nextKeys.size < capacity) nextKeys += placement.candidate.componentKey
        }
        selectedComponentKeys = nextKeys
        return eligiblePlacements.filter { it.candidate.componentKey in nextKeys }
    }
}

private fun FeatureOverlayEmblemPlacement.intersectsCanvas(transform: MapTransform): Boolean {
    val center = transform.worldToScreen(anchor)
    val renderedSize = sizePx.toInt().coerceAtLeast(1)
    val left = (center.x - renderedSize / 2.0).toInt()
    val top = (center.y - renderedSize / 2.0).toInt()
    val right = left + renderedSize
    val bottom = top + renderedSize
    return right > 0 &&
        left < transform.canvasSize.width &&
        bottom > 0 &&
        top < transform.canvasSize.height
}

internal fun interface PresentationEmblemLoader<T> {
    suspend fun load(reference: PresentationEmblemReference): T
}

internal fun interface PresentationEmblemDownloader {
    suspend fun download(reference: PresentationEmblemReference): ByteArray
}

internal fun interface PresentationEmblemDecoder<T> {
    fun decode(bytes: ByteArray): T
}

internal sealed interface PresentationEmblemAssetState<out T> {
    data class Ready<T>(val asset: T) : PresentationEmblemAssetState<T>
    data class Failed(val message: String) : PresentationEmblemAssetState<Nothing>
}

internal class PresentationEmblemAssetRepository<T>(
    private val scope: CoroutineScope,
    private val loader: PresentationEmblemLoader<T>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val capacity: Int = EMBLEM_MEMORY_CACHE_CAPACITY,
) {
    private val cache = LinkedHashMap<String, PresentationEmblemAssetState<T>>(capacity, 0.75f, true)
    private val inFlight = linkedMapOf<String, Deferred<PresentationEmblemAssetState<T>>>()
    private val mutableStates = MutableStateFlow<Map<String, PresentationEmblemAssetState<T>>>(emptyMap())
    val states: StateFlow<Map<String, PresentationEmblemAssetState<T>>> = mutableStates.asStateFlow()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun request(reference: PresentationEmblemReference): Deferred<PresentationEmblemAssetState<T>> {
        cache[reference.key]?.let { return CompletableDeferred(it) }
        inFlight[reference.key]?.let { return it }
        val deferred = scope.async(dispatcher, start = CoroutineStart.LAZY) {
            val result: PresentationEmblemAssetState<T> = try {
                PresentationEmblemAssetState.Ready(loader.load(reference))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                PresentationEmblemAssetState.Failed(
                    failure.message?.take(160) ?: failure::class.simpleName ?: "Emblem load failed",
                )
            }
            complete(reference.key, result)
            result
        }
        inFlight[reference.key] = deferred
        deferred.invokeOnCompletion { removeInFlight(reference.key, deferred) }
        deferred.start()
        return deferred
    }

    @Synchronized
    fun state(key: String): PresentationEmblemAssetState<T>? = cache[key]

    @Synchronized
    fun cachedKeys(): Set<String> = cache.keys.toSet()

    @Synchronized
    private fun complete(key: String, state: PresentationEmblemAssetState<T>) {
        cache[key] = state
        while (cache.size > capacity) {
            val eldest = cache.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        mutableStates.value = cache.toMap()
    }

    @Synchronized
    private fun removeInFlight(key: String, deferred: Deferred<PresentationEmblemAssetState<T>>) {
        inFlight.remove(key, deferred)
    }
}

internal class JdkPresentationEmblemImageLoader(
    cacheDirectory: Path = defaultPresentationEmblemCacheDirectory(),
    httpClient: HttpClient = defaultEmblemHttpClient(),
    warningSink: (String, Throwable?) -> Unit = AppDiagnostics::warning,
) : PresentationEmblemLoader<ImageBitmap> {
    private val delegate = DiskCachedPresentationEmblemLoader(
        cacheDirectory = cacheDirectory,
        downloader = JdkPresentationEmblemDownloader(httpClient),
        decoder = PresentationEmblemDecoder { bytes -> Image.makeFromEncoded(bytes).toComposeImageBitmap() },
        warningSink = warningSink,
    )

    override suspend fun load(reference: PresentationEmblemReference): ImageBitmap = delegate.load(reference)
}

internal class JdkPresentationEmblemDownloader(
    private val httpClient: HttpClient = defaultEmblemHttpClient(),
) : PresentationEmblemDownloader {
    override suspend fun download(reference: PresentationEmblemReference): ByteArray {
        val uri = validatedEmblemUri(reference.url)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(EMBLEM_REQUEST_TIMEOUT_SECONDS))
            .header("Accept", "image/png")
            .header("User-Agent", "EVE-Static-Map-Planner alliance-emblem/1")
            .GET()
            .build()
        return downloadBytes(request)
    }

    private fun downloadBytes(request: HttpRequest): ByteArray {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        return response.body().use { input ->
            check(response.statusCode() in 200..299) { "Emblem server returned HTTP ${response.statusCode()}" }
            input.readNBytes(MAX_EMBLEM_BYTES + 1).also(::validateEmblemBytes)
        }
    }
}

internal class DiskCachedPresentationEmblemLoader<T>(
    cacheDirectory: Path,
    private val downloader: PresentationEmblemDownloader,
    private val decoder: PresentationEmblemDecoder<T>,
    private val warningSink: (String, Throwable?) -> Unit = { _, _ -> },
    private val retryAttempts: Int = EMBLEM_NETWORK_ATTEMPTS,
    private val retryBaseDelayMillis: Long = EMBLEM_RETRY_BASE_DELAY_MILLIS,
    maxConcurrentDownloads: Int = EMBLEM_MAX_CONCURRENT_DOWNLOADS,
    private val delayBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) : PresentationEmblemLoader<T> {
    private val cacheDirectory = cacheDirectory.toAbsolutePath().normalize()
    private val networkPermits = Semaphore(maxConcurrentDownloads)

    init {
        require(retryAttempts > 0)
        require(retryBaseDelayMillis >= 0L)
        require(maxConcurrentDownloads > 0)
    }

    override suspend fun load(reference: PresentationEmblemReference): T {
        readCached(reference)?.let { return it }
        return networkPermits.withPermit { downloadWithRetry(reference) }
    }

    private fun readCached(reference: PresentationEmblemReference): T? {
        val cachePath = cachePath(reference)
        if (!Files.isRegularFile(cachePath)) return null
        return try {
            decoder.decode(readValidatedBytes(cachePath))
        } catch (failure: Exception) {
            warningSink("Alliance Logo cache is invalid for ${reference.key}; downloading a fresh copy", failure)
            runCatching { Files.deleteIfExists(cachePath) }
                .onFailure { deleteFailure ->
                    warningSink("Alliance Logo invalid cache could not be deleted for ${reference.key}", deleteFailure)
                }
            null
        }
    }

    private suspend fun downloadWithRetry(reference: PresentationEmblemReference): T {
        var lastFailure: Exception? = null
        repeat(retryAttempts) { attempt ->
            try {
                val bytes = downloader.download(reference).also(::validateEmblemBytes)
                val decoded = decoder.decode(bytes)
                writeCached(reference, bytes)
                return decoded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
                val attemptNumber = attempt + 1
                warningSink(
                    "Alliance Logo download failed for ${reference.key} " +
                        "(attempt $attemptNumber/$retryAttempts)",
                    failure,
                )
                if (attemptNumber < retryAttempts) {
                    delayBeforeRetry(retryDelayMillis(attemptNumber))
                }
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun writeCached(reference: PresentationEmblemReference, bytes: ByteArray) {
        val target = cachePath(reference)
        var temporary: Path? = null
        try {
            Files.createDirectories(cacheDirectory)
            temporary = Files.createTempFile(cacheDirectory, ".${target.fileName}-", ".part")
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            warningSink("Alliance Logo cache write failed for ${reference.key}", failure)
        } finally {
            temporary?.let { part -> runCatching { Files.deleteIfExists(part) } }
        }
    }

    internal fun cachePath(reference: PresentationEmblemReference): Path =
        cacheDirectory.resolve(emblemCacheFileName(reference))

    private fun retryDelayMillis(failedAttemptNumber: Int): Long =
        retryBaseDelayMillis * (1L shl (failedAttemptNumber - 1).coerceAtMost(20))
}

internal fun defaultPresentationEmblemCacheDirectory(
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name"),
    userHome: Path = Path.of(System.getProperty("user.home")),
): Path = ApplicationDirectories.root(environment, osName, userHome)
    .resolve("cache")
    .resolve("alliance-logos")
    .toAbsolutePath()
    .normalize()

internal fun emblemCacheFileName(reference: PresentationEmblemReference): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "${reference.key}\u0000${reference.url}".toByteArray(StandardCharsets.UTF_8),
    )
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) } + ".png"
}

private fun readValidatedBytes(path: Path): ByteArray {
    val size = Files.size(path)
    check(size in 1..MAX_EMBLEM_BYTES.toLong()) {
        "Emblem cache size is invalid: $size bytes"
    }
    return Files.readAllBytes(path).also(::validateEmblemBytes)
}

private fun validateEmblemBytes(bytes: ByteArray) {
    check(bytes.isNotEmpty()) { "Emblem response was empty" }
    check(bytes.size <= MAX_EMBLEM_BYTES) { "Emblem response exceeded $MAX_EMBLEM_BYTES bytes" }
}

private fun defaultEmblemHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(EMBLEM_CONNECT_TIMEOUT_SECONDS))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .version(HttpClient.Version.HTTP_1_1)
    .build()

internal fun validatedEmblemUri(url: String): URI {
    val uri = URI.create(url)
    require(uri.scheme.equals("https", ignoreCase = true)) { "Emblem URL must use HTTPS" }
    require(uri.host.equals(OFFICIAL_IMAGE_HOST, ignoreCase = true)) { "Emblem URL host is not allowed" }
    require(uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "Emblem URL authority is not allowed" }
    return uri
}

internal const val EMBLEM_DEFAULT_EMPHASIS_ZOOM = 0.75
internal const val EMBLEM_BACKGROUND_ALPHA = 0.28
internal const val EMBLEM_MAX_ALPHA = 0.94f
internal const val EMBLEM_MIN_RENDERED_SIZE_PX = 56.0
internal const val EMBLEM_MIN_SIZE_PX = 72.0
internal const val EMBLEM_MAX_SIZE_PX = 272.0
private const val EMBLEM_EMPHASIS_TRANSITION_HALF_WIDTH_FACTOR = 2.0 / 15.0
private const val EMBLEM_BACKGROUND_FULL_ALPHA_ZOOM_FACTOR = 4.0 / 3.0
private const val EMBLEM_DETAIL_HIDE_ZOOM_FACTOR = 8.0 / 3.0
private const val EMBLEM_FAR_SIZE_BOOST = 0.20
private const val EMBLEM_AREA_SIZE_FACTOR = 0.70
private const val EMBLEM_BOUNDS_SIZE_FACTOR = 0.78
private const val EMBLEM_CLEARANCE_DIAMETER_FACTOR = 1.8
internal const val MAX_VISIBLE_EMBLEM_REQUESTS = 24
internal const val EMBLEM_MEMORY_CACHE_CAPACITY = 64
private const val EMBLEM_CONNECT_TIMEOUT_SECONDS = 12L
private const val EMBLEM_REQUEST_TIMEOUT_SECONDS = 20L
internal const val EMBLEM_NETWORK_ATTEMPTS = 3
internal const val EMBLEM_RETRY_BASE_DELAY_MILLIS = 250L
internal const val EMBLEM_MAX_CONCURRENT_DOWNLOADS = 4
private const val MAX_EMBLEM_BYTES = 2 * 1024 * 1024
private const val OFFICIAL_IMAGE_HOST = "images.evetech.net"
