package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureOverlaySystemMarkerTest {
    @Test
    fun `hit test is marker-only and bounded to avatar pin head`() {
        val marker = PresentedOverlaySystemMarker(
            systemId = 42,
            center = Offset(100f, 100f),
            node = Offset(100f, 134f),
            images = listOf(ImageBitmap(1, 1)),
            overflowCount = 0,
            tooltipLines = listOf("Character — 90000001"),
        )

        assertEquals(marker, hitTestOverlaySystemMarker(listOf(marker), MapPoint(100.0, 100.0)))
        assertNull(hitTestOverlaySystemMarker(listOf(marker), MapPoint(100.0, 134.0)))
    }
}
