package dev.evestaticmapplanner.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayImage
import dev.evestaticmapplanner.feature.api.OverlayState
import dev.evestaticmapplanner.feature.api.OverlaySystemMarker
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import org.jetbrains.skia.Surface

@OptIn(ExperimentalTestApi::class)
class FeatureOverlaySystemMarkerTest {
    @Test
    fun `hit test is marker-only and bounded to avatar pin head`() {
        val marker = PresentedOverlaySystemMarker(
            systemId = 42,
            center = Offset(100f, 100f),
            node = Offset(100f, 115f),
            images = listOf(ImageBitmap(1, 1)),
            overflowCount = 0,
            tooltipLines = listOf("Character — 90000001"),
        )

        assertEquals(marker, hitTestOverlaySystemMarker(listOf(marker), MapPoint(100.0, 100.0)))
        assertNull(hitTestOverlaySystemMarker(listOf(marker), MapPoint(100.0, 115.0)))
    }

    @Test
    fun `portrait marker remains twenty pixel class for one to four images`() {
        assertEquals(20f, OverlaySystemMarkerVisuals.PORTRAIT_DIAMETER_PX)
        assertEquals(1.5f, OverlaySystemMarkerVisuals.OUTER_BORDER_WIDTH_PX)
        assertEquals(1f, OverlaySystemMarkerVisuals.HIGHLIGHT_WIDTH_PX)
        assertEquals(2f, OverlaySystemMarkerVisuals.SHADOW_RADIUS_EXTRA_PX)
        assertEquals(5f, OverlaySystemMarkerVisuals.PIN_TIP_HEIGHT_PX)
        assertTrue(OverlaySystemMarkerVisuals.TOTAL_HEIGHT_PX in 26f..28f)

        (1..4).forEach { count ->
            val marker = PresentedOverlaySystemMarker(
                systemId = count,
                center = Offset(80f, 85f),
                node = Offset(80f, 100f),
                images = List(count) { ImageBitmap(1, 1) },
                overflowCount = 0,
                tooltipLines = emptyList(),
            )
            assertEquals(15f, marker.node.y - marker.center.y)
            assertEquals(20f, OverlaySystemMarkerVisuals.PORTRAIT_DIAMETER_PX)
            assertEquals(
                androidx.compose.ui.geometry.Rect(70f, 75f, 90f, 95f),
                overlaySystemMarkerPortraitRect(marker),
            )
        }
    }

    @Test
    fun `portrait source is center cropped before entering fixed destination rect`() {
        val landscape = ImageBitmap(8, 4)
        val portrait = ImageBitmap(4, 8)

        assertEquals(androidx.compose.ui.unit.IntOffset(2, 0) to androidx.compose.ui.unit.IntSize(4, 4), centeredSquareCrop(landscape))
        assertEquals(androidx.compose.ui.unit.IntOffset(0, 2) to androidx.compose.ui.unit.IntSize(4, 4), centeredSquareCrop(portrait))
    }

    @Test
    fun `encoded portrait reaches marker presentation while invalid bytes keep safe fallback`() {
        val decoded = decodeOverlaySystemMarkerImages(listOf(OverlayImage("image/png", PNG_BYTES)))

        assertEquals(1, decoded.size)
        assertEquals(1, decoded.single().width)
        assertEquals(1, decoded.single().height)
        assertTrue(decodeOverlaySystemMarkerImages(listOf(OverlayImage("image/png", byteArrayOf(1)))).isEmpty())
    }

    @Test
    fun `final canvas clips one portrait to its circle without an image quad`() = runComposeUiTest {
        val portrait = solidImage(0xFFFF0000.toInt())
        val marker = PresentedOverlaySystemMarker(
            systemId = 42,
            center = Offset(40f, 30f),
            node = Offset(40f, 45f),
            images = listOf(portrait),
            overflowCount = 0,
            tooltipLines = emptyList(),
        )
        setContent {
            val textMeasurer = rememberTextMeasurer()
            Canvas(Modifier.size(80.dp).background(TEST_BACKGROUND).testTag(CANVAS_TAG)) {
                drawOverlaySystemMarkers(listOf(marker), textMeasurer)
            }
        }

        val pixels = onNodeWithTag(CANVAS_TAG).captureToImage().toPixelMap()
        val changed = buildList {
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y] != TEST_BACKGROUND) add(x to y)
            }
        }
        val bounds = "${changed.minOf { it.first }},${changed.minOf { it.second }}.." +
            "${changed.maxOf { it.first }},${changed.maxOf { it.second }}"
        assertTrue(
            pixels[40, 30].red > 0.8f,
            "portrait must fill the marker center; center=${pixels[40, 30]}, corner=${pixels[49, 21]}, bounds=$bounds",
        )
        assertEquals(TEST_BACKGROUND, pixels[49, 21], "square image corner must remain clipped")
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val insideMarkerBounds = x in 27..53 && y in 17..46
                if (!insideMarkerBounds) assertEquals(TEST_BACKGROUND, pixels[x, y], "unexpected pixel at $x,$y")
            }
        }
    }

    @Test
    fun `one to four portraits occupy circular sectors at the same marker size`() = runComposeUiTest {
        val colors = listOf(
            0xFFFF0000.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(),
            0xFFFF00FF.toInt(),
        )
        val markers = (1..4).map { count ->
            val center = Offset(20f + (count - 1) * 35f, 30f)
            PresentedOverlaySystemMarker(
                systemId = count,
                center = center,
                node = center + Offset(0f, 15f),
                images = colors.take(count).map(::solidImage),
                overflowCount = 0,
                tooltipLines = emptyList(),
            )
        }
        setContent {
            val textMeasurer = rememberTextMeasurer()
            Canvas(Modifier.size(140.dp, 60.dp).background(TEST_BACKGROUND).testTag(CANVAS_TAG)) {
                drawOverlaySystemMarkers(markers, textMeasurer)
            }
        }

        val pixels = onNodeWithTag(CANVAS_TAG).captureToImage().toPixelMap()
        markers.forEachIndexed { index, marker ->
            val seen = mutableSetOf<Int>()
            for (y in (marker.center.y - 7f).toInt()..(marker.center.y + 7f).toInt()) {
                for (x in (marker.center.x - 7f).toInt()..(marker.center.x + 7f).toInt()) {
                    val pixel = pixels[x, y]
                    colors.forEachIndexed { colorIndex, color ->
                        val expected = Color(color)
                        if (
                            kotlin.math.abs(pixel.red - expected.red) < 0.05f &&
                            kotlin.math.abs(pixel.green - expected.green) < 0.05f &&
                            kotlin.math.abs(pixel.blue - expected.blue) < 0.05f
                        ) seen += colorIndex
                    }
                }
            }
            assertEquals((0..index).toSet(), seen, "marker ${index + 1} must show every portrait sector")
            assertEquals(TEST_BACKGROUND, pixels[(marker.center.x + 9f).toInt(), (marker.center.y - 9f).toInt()])
        }
    }

    @Test
    fun `portrait readiness refreshes presentation at the same system and node`() {
        val projectedScene = singleSystemScene()
        val transform = MapTransform(MapViewport(projectedScene.nodes.single().position, 1.0), MapSize(80.0, 80.0))
        val loading = overlayState(marker = null)
        val ready = overlayState(OverlaySystemMarker(listOf(OverlayImage("image/png", PNG_BYTES))))

        assertTrue(presentOverlaySystemMarkers(loading, projectedScene, transform).isEmpty())
        val presented = presentOverlaySystemMarkers(ready, projectedScene, transform).single()
        assertEquals(Offset(40f, 40f), presented.node)
        assertEquals(Offset(40f, 25f), presented.center)
        assertEquals(1, presented.images.size)
    }

    private fun solidImage(color: Int): ImageBitmap {
        val surface = Surface.makeRasterN32Premul(4, 4)
        surface.canvas.clear(color)
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }

    private fun overlayState(marker: OverlaySystemMarker?) = OverlayState(listOf(
        OverlayLayerState(
            OverlayProviderDescriptor("test.character-location", "Character Location"),
            OverlayLayer("current-location", "Current Location"),
            listOf(
                OverlayEntry(
                    layerId = "current-location",
                    systemId = SYSTEM_ID,
                    title = "Character One",
                    value = MARKER_ONLY_PRESENTATION_VALUE,
                    systemMarker = marker,
                ),
            ),
        ),
    ))

    private fun singleSystemScene() = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(
                SolarSystem(
                    id = SYSTEM_ID,
                    constellationId = 10,
                    regionId = 100,
                    name = "System",
                    securityStatus = 0.0,
                    securityClass = null,
                    position = UniversePosition(0.0, 0.0, 0.0),
                    schematicPosition = SchematicPosition(0.0, 0.0),
                    radius = 1.0,
                    factionId = null,
                    wormholeClassId = null,
                ),
            ),
            connections = emptyList(),
        ),
        OfficialPosition2DProjection,
    )

    private companion object {
        const val CANVAS_TAG = "portrait-marker-canvas"
        const val SYSTEM_ID = 30_000_001
        val TEST_BACKGROUND = Color(0xFF00FF00)
        val PNG_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
