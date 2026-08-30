package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.feature.api.OverlayImage
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

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
        }
    }

    @Test
    fun `encoded portrait reaches marker presentation while invalid bytes keep safe fallback`() {
        val decoded = decodeOverlaySystemMarkerImages(listOf(OverlayImage("image/png", PNG_BYTES)))

        assertEquals(1, decoded.size)
        assertEquals(1, decoded.single().width)
        assertEquals(1, decoded.single().height)
        assertTrue(decodeOverlaySystemMarkerImages(listOf(OverlayImage("image/png", byteArrayOf(1)))).isEmpty())
    }

    private companion object {
        val PNG_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
