package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureOverlayEmblemsTest {
    @Test
    fun `detail zoom produces no placements or asset requests`() = runTest {
        var loads = 0
        val repository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            loader = PresentationEmblemLoader<String> { loads++; "image" },
        )
        val detailHideZoom = FeatureOverlayEmblemLod.detailHideZoom(FeatureOverlayEmblemZoomPolicy.Default)
        val placements = FeatureOverlayEmblemLod.placements(listOf(candidate()), transform(detailHideZoom))

        placements.forEach { repository.request(it.candidate.reference) }
        runCurrent()

        assertTrue(placements.isEmpty())
        assertEquals(0, loads)
        assertTrue(repository.cachedKeys().isEmpty())
    }

    @Test
    fun `three-zone overview fade is monotonic and rendered size remains bounded`() {
        val alphas = listOf(2.0, 1.75, 1.5, 1.25, 1.0, 0.95, 0.84, 0.75, 0.70, 0.65).map {
            FeatureOverlayEmblemLod.alpha(it)
        }
        assertEquals(0f, alphas.first())
        assertEquals(EMBLEM_MAX_ALPHA, alphas.last())
        assertTrue(alphas.zipWithNext().all { (first, second) -> second >= first })
        assertTrue(FeatureOverlayEmblemLod.alpha(1.5) in 0.13f..0.15f)
        assertEquals(EMBLEM_BACKGROUND_ALPHA.toFloat(), FeatureOverlayEmblemLod.alpha(1.0))
        assertEquals(EMBLEM_BACKGROUND_ALPHA.toFloat(), FeatureOverlayEmblemLod.alpha(0.95))
        assertTrue(FeatureOverlayEmblemLod.alpha(0.84) in 0.28f..0.30f)
        assertTrue(FeatureOverlayEmblemLod.alpha(0.75) in 0.60f..0.62f)
        assertTrue(FeatureOverlayEmblemLod.alpha(0.70) in 0.83f..0.85f)
        assertEquals(EMBLEM_MAX_ALPHA, FeatureOverlayEmblemLod.alpha(0.65))

        val overview = FeatureOverlayEmblemLod.placements(listOf(candidate()), transform(REAL_3D_CANONICAL_FIT_ZOOM)).single()
        assertTrue(overview.alpha > 0f)
        assertTrue(overview.sizePx >= EMBLEM_MIN_RENDERED_SIZE_PX)
        assertTrue(overview.sizePx <= EMBLEM_MAX_SIZE_PX)
    }

    @Test
    fun `measured REAL X-Z zoom levels follow detail medium and bright overview policy`() {
        assertEquals(0f, FeatureOverlayEmblemLod.alpha(REAL_3D_CANONICAL_DETAIL_ZOOM))

        val mediumAlpha = FeatureOverlayEmblemLod.alpha(REAL_3D_CANONICAL_MEDIUM_ZOOM)
        assertTrue(mediumAlpha > 0f)
        assertTrue(mediumAlpha < EMBLEM_MAX_ALPHA)

        assertEquals(EMBLEM_MAX_ALPHA, FeatureOverlayEmblemLod.alpha(REAL_3D_CANONICAL_FIT_ZOOM))
        assertTrue(FeatureOverlayEmblemLod.alpha(REAL_3D_CANONICAL_SCREENSHOT_OVERVIEW_ZOOM) >= 0.87f)
        assertEquals(EMBLEM_MAX_ALPHA, FeatureOverlayEmblemLod.alpha(REAL_3D_CANONICAL_MAXIMUM_OUT_ZOOM))
        assertTrue(
            FeatureOverlayEmblemLod.placements(listOf(candidate()), transform(REAL_3D_CANONICAL_SCREENSHOT_OVERVIEW_ZOOM))
                .single().sizePx >= EMBLEM_MIN_RENDERED_SIZE_PX,
        )
    }

    @Test
    fun `one user threshold continuously moves the political emphasis transition`() {
        val threshold = FeatureOverlayEmblemZoomPolicy(0.80)

        assertEquals(0f, FeatureOverlayEmblemLod.alpha(FeatureOverlayEmblemLod.detailHideZoom(threshold), threshold))
        assertTrue(FeatureOverlayEmblemLod.alpha(1.10, threshold) in 0.27f..0.28f)
        assertTrue(FeatureOverlayEmblemLod.alpha(0.90, threshold) in 0.27f..0.29f)
        assertTrue(FeatureOverlayEmblemLod.alpha(0.80, threshold) in 0.60f..0.62f)
        assertTrue(FeatureOverlayEmblemLod.alpha(0.70, threshold) >= 0.93f)
        assertEquals(EMBLEM_MAX_ALPHA, FeatureOverlayEmblemLod.alpha(0.50, threshold))

        val immediatelyBelow = FeatureOverlayEmblemLod.alpha(0.80 - 0.0001, threshold)
        val immediatelyAbove = FeatureOverlayEmblemLod.alpha(0.80 + 0.0001, threshold)
        assertTrue(immediatelyBelow - immediatelyAbove < 0.01f)
    }

    @Test
    fun `changing one threshold updates alpha immediately at the same viewport zoom`() {
        val zoom = 0.70
        val farOnlyThreshold = FeatureOverlayEmblemZoomPolicy(0.55)
        val highThreshold = FeatureOverlayEmblemZoomPolicy(0.85)

        val backgroundAlpha = FeatureOverlayEmblemLod.alpha(zoom, farOnlyThreshold)
        val politicalAlpha = FeatureOverlayEmblemLod.alpha(zoom, highThreshold)

        assertEquals(EMBLEM_BACKGROUND_ALPHA.toFloat(), backgroundAlpha)
        assertEquals(EMBLEM_MAX_ALPHA, politicalAlpha)
    }

    @Test
    fun `threshold supports the complete map zoom range without an absolute detail cutoff`() {
        val maximumThreshold = FeatureOverlayEmblemZoomPolicy(250.0)

        assertTrue(FeatureOverlayEmblemLod.alpha(250.0, maximumThreshold) > 0.5f)
        assertEquals(EMBLEM_MAX_ALPHA, FeatureOverlayEmblemLod.alpha(200.0, maximumThreshold))

        val closerViewThreshold = FeatureOverlayEmblemZoomPolicy(5.0)
        assertEquals(EMBLEM_MAX_ALPHA, FeatureOverlayEmblemLod.alpha(4.0, closerViewThreshold))
        assertTrue(FeatureOverlayEmblemLod.alpha(6.0, closerViewThreshold) > 0f)
        assertEquals(
            0f,
            FeatureOverlayEmblemLod.alpha(
                FeatureOverlayEmblemLod.detailHideZoom(closerViewThreshold),
                closerViewThreshold,
            ),
        )
    }

    @Test
    fun `ready emblem preserves the selected interior anchor`() {
        val candidate = candidate()
        val placement = FeatureOverlayEmblemLod.placements(listOf(candidate), transform(REAL_3D_CANONICAL_FIT_ZOOM)).single()
        val image = ImageBitmap(8, 8)

        val ready = FeatureOverlayEmblemLod.readyEmblems(
            placements = listOf(placement),
            states = mapOf(REFERENCE.key to PresentationEmblemAssetState.Ready(image)),
        ).single()

        assertSame(image, ready.image)
        assertEquals(placement.anchor, ready.anchor)
        assertSame(candidate.clipTerritory, ready.clipTerritory)
    }

    @Test
    fun `placement size is limited by the selected anchors boundary clearance`() {
        val constrained = candidate().copy(
            boundaryClearance = 20.0,
        )

        val placement = FeatureOverlayEmblemLod.placements(listOf(constrained), transform(1.0)).single()

        assertEquals(EMBLEM_MIN_RENDERED_SIZE_PX.toFloat(), placement.sizePx)
    }

    @Test
    fun `pan and zoom never change the prepared component anchor`() {
        val candidate = candidate()
        val transforms = listOf(
            transform(1.0, center = MapPoint(100.0, 100.0)),
            transform(0.6, center = MapPoint(80.0, 120.0)),
            transform(REAL_3D_CANONICAL_FIT_ZOOM, center = MapPoint(140.0, 70.0)),
        )

        val anchors = transforms.map { current ->
            FeatureOverlayEmblemLod.placements(listOf(candidate), current).single().anchor
        }

        assertEquals(List(transforms.size) { candidate.anchor }, anchors)
    }

    @Test
    fun `emphasis preference changes alpha and size without changing stable anchor`() {
        val candidate = candidate()
        val currentTransform = transform(0.70)
        val highThreshold = FeatureOverlayEmblemLod.placements(
            listOf(candidate),
            currentTransform,
            FeatureOverlayEmblemZoomPolicy(0.85),
        ).single()
        val lowThreshold = FeatureOverlayEmblemLod.placements(
            listOf(candidate),
            currentTransform,
            FeatureOverlayEmblemZoomPolicy(0.55),
        ).single()

        assertEquals(candidate.anchor, highThreshold.anchor)
        assertEquals(candidate.anchor, lowThreshold.anchor)
        assertTrue(highThreshold.alpha > lowThreshold.alpha)
        assertTrue(highThreshold.sizePx > lowThreshold.sizePx)
    }

    @Test
    fun `threshold changes reuse the same decoded logo asset`() = runTest {
        var loads = 0
        val repository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            loader = PresentationEmblemLoader<String> { loads++; "decoded" },
        )
        val currentTransform = transform(0.70)

        listOf(0.55, 0.85).forEach { emphasisZoom ->
            val placement = FeatureOverlayEmblemLod.placements(
                listOf(candidate()),
                currentTransform,
                FeatureOverlayEmblemZoomPolicy(emphasisZoom),
            ).single()
            val request = repository.request(placement.candidate.reference)
            runCurrent()
            assertEquals("decoded", assertIs<PresentationEmblemAssetState.Ready<String>>(request.await()).asset)
        }

        assertEquals(1, loads)
    }

    @Test
    fun `fixed anchor remains rendered until its actual logo rectangle fully leaves the canvas`() {
        val currentTransform = transform(1.0)
        val size = FeatureOverlayEmblemLod.placements(listOf(candidate()), currentTransform).single().sizePx.toDouble()
        val partiallyVisibleAnchor = currentTransform.screenToWorld(MapPoint(-size / 2.0 + 1.0, 400.0))
        val fullyOutsideAnchor = currentTransform.screenToWorld(MapPoint(-size / 2.0 - 1.0, 400.0))

        val partiallyVisible = FeatureOverlayEmblemLod.placements(
            listOf(candidateAt("partial", partiallyVisibleAnchor)),
            currentTransform,
        )
        val fullyOutside = FeatureOverlayEmblemLod.placements(
            listOf(candidateAt("outside", fullyOutsideAnchor)),
            currentTransform,
        )

        assertEquals(partiallyVisibleAnchor, partiallyVisible.single().anchor)
        assertTrue(fullyOutside.isEmpty())
    }

    @Test
    fun `continuous pan retains every still visible capped logo and zoom may recompute selection`() {
        val initialTransform = transform(1.0, center = MapPoint(0.0, 0.0))
        val pannedTransform = transform(1.0, center = MapPoint(100.0, 0.0))
        val zoomedTransform = transform(0.9, center = MapPoint(100.0, 0.0))
        val initiallyVisible = (0 until MAX_VISIBLE_EMBLEM_REQUESTS).map { index ->
            candidateAt(
                componentKey = "retained-$index",
                anchor = MapPoint(-220.0 + index * 18.0, 0.0),
                mapArea = 40_000.0 - index,
            )
        }
        val enteringHighPriority = candidateAt(
            componentKey = "entering-high-priority",
            anchor = MapPoint(700.0, 0.0),
            mapArea = 1_000_000.0,
            boundaryClearance = 1_000.0,
            halfSpan = 250.0,
        )
        val candidates = initiallyVisible + enteringHighPriority
        val selector = StableFeatureOverlayEmblemSelector()

        val initial = selector.select(
            FeatureOverlayEmblemLod.placements(candidates, initialTransform),
            initialTransform.viewport.zoom,
        )
        val afterPan = selector.select(
            FeatureOverlayEmblemLod.placements(candidates, pannedTransform),
            pannedTransform.viewport.zoom,
        )

        assertEquals(initial.map { it.candidate.componentKey }.toSet(), afterPan.map { it.candidate.componentKey }.toSet())
        assertTrue(afterPan.none { it.candidate.componentKey == enteringHighPriority.componentKey })

        val afterZoom = selector.select(
            FeatureOverlayEmblemLod.placements(candidates, zoomedTransform),
            zoomedTransform.viewport.zoom,
        )
        assertTrue(afterZoom.any { it.candidate.componentKey == enteringHighPriority.componentKey })
    }

    @Test
    fun `duplicate requests share one in-flight load and success is reused across projections`() = runTest {
        var loads = 0
        val gate = CompletableDeferred<String>()
        val repository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            loader = PresentationEmblemLoader { loads++; gate.await() },
        )

        val officialRequest = repository.request(REFERENCE)
        val real3DRequest = repository.request(REFERENCE)
        assertSame(officialRequest, real3DRequest)
        runCurrent()
        assertEquals(1, loads)

        gate.complete("decoded-image")
        runCurrent()
        assertEquals("decoded-image", assertIs<PresentationEmblemAssetState.Ready<String>>(officialRequest.await()).asset)

        val cached = repository.request(REFERENCE)
        assertEquals("decoded-image", assertIs<PresentationEmblemAssetState.Ready<String>>(cached.await()).asset)
        assertEquals(1, loads)
    }

    @Test
    fun `exhausted loader failure is session-cached and cannot produce per-frame retry spam`() = runTest {
        var loads = 0
        val repository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            loader = PresentationEmblemLoader<String> {
                loads++
                error("offline")
            },
        )

        val first = repository.request(REFERENCE)
        runCurrent()
        assertEquals("offline", assertIs<PresentationEmblemAssetState.Failed>(first.await()).message)
        repeat(8) {
            assertIs<PresentationEmblemAssetState.Failed>(repository.request(REFERENCE).await())
        }

        assertEquals(1, loads)
    }

    @Test
    fun `bounded memory cache evicts eldest completed emblem`() = runTest {
        val repository = PresentationEmblemAssetRepository(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            capacity = 2,
            loader = PresentationEmblemLoader<String> { it.key },
        )
        val references = (1..3).map { index ->
            PresentationEmblemReference("key-$index", "https://images.evetech.net/alliances/$index/logo?size=256")
        }
        references.forEach { reference ->
            val request = repository.request(reference)
            runCurrent()
            request.await()
        }

        assertEquals(setOf("key-2", "key-3"), repository.cachedKeys())
    }

    @Test
    fun `URL policy accepts official HTTPS image service and rejects unrelated sources`() {
        assertEquals("images.evetech.net", validatedEmblemUri(REFERENCE.url).host)
        listOf(
            "http://images.evetech.net/alliances/42/logo",
            "https://example.com/alliances/42/logo",
            "https://user@images.evetech.net/alliances/42/logo",
        ).forEach { url ->
            assertTrue(runCatching { validatedEmblemUri(url) }.isFailure)
        }
    }

    private fun candidate() = PresentedFeatureEmblemCandidate(
        componentKey = "component-a",
        reference = REFERENCE,
        anchor = MapPoint(100.0, 100.0),
        bounds = MapBounds(0.0, 0.0, 200.0, 200.0),
        mapArea = 40_000.0,
        systemCount = 12,
        boundaryClearance = 100.0,
        clipTerritory = PresentedFeatureTerritory(
            ownerLabel = "Alliance A",
            systemIds = (1..12).toSet(),
            color = Color.Blue,
            polygon = listOf(
                MapPoint(0.0, 0.0),
                MapPoint(200.0, 0.0),
                MapPoint(200.0, 200.0),
                MapPoint(0.0, 200.0),
            ),
            bounds = MapBounds(0.0, 0.0, 200.0, 200.0),
        ),
    )

    private fun candidateAt(
        componentKey: String,
        anchor: MapPoint,
        mapArea: Double = 40_000.0,
        boundaryClearance: Double = 100.0,
        halfSpan: Double = 200.0,
    ): PresentedFeatureEmblemCandidate {
        val bounds = MapBounds(
            anchor.x - halfSpan,
            anchor.y - halfSpan,
            anchor.x + halfSpan,
            anchor.y + halfSpan,
        )
        return candidate().copy(
            componentKey = componentKey,
            anchor = anchor,
            bounds = bounds,
            mapArea = mapArea,
            boundaryClearance = boundaryClearance,
            clipTerritory = candidate().clipTerritory.copy(
                polygon = listOf(
                    MapPoint(bounds.minX, bounds.minY),
                    MapPoint(bounds.maxX, bounds.minY),
                    MapPoint(bounds.maxX, bounds.maxY),
                    MapPoint(bounds.minX, bounds.maxY),
                ),
                bounds = bounds,
            ),
        )
    }

    private fun transform(zoom: Double, center: MapPoint = MapPoint(100.0, 100.0)) = MapTransform(
        viewport = MapViewport(center, zoom),
        canvasSize = MapSize(1_000.0, 800.0),
    )

    private companion object {
        const val REAL_3D_CANONICAL_DETAIL_ZOOM = 6.813138602009427
        const val REAL_3D_CANONICAL_MEDIUM_ZOOM = 1.5845182865666516
        const val REAL_3D_CANONICAL_FIT_ZOOM = 0.5306519681842408
        const val REAL_3D_CANONICAL_SCREENSHOT_OVERVIEW_ZOOM = 0.4422099734868673
        const val REAL_3D_CANONICAL_MAXIMUM_OUT_ZOOM = 0.01
        val REFERENCE = PresentationEmblemReference(
            "eve-alliance:42",
            "https://images.evetech.net/alliances/42/logo?size=256",
        )
    }
}
