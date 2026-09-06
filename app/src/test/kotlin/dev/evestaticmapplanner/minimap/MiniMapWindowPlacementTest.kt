package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.preferences.MiniMapWindowBounds
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniMapWindowPlacementTest {
    private val primary = MiniMapScreenWorkArea(0f, 0f, 1920f, 1040f)
    private val left = MiniMapScreenWorkArea(-1280f, 0f, 1280f, 984f)

    @Test
    fun `offscreen window is recovered into the nearest monitor work area`() {
        val recovered = MiniMapWindowPlacement.recover(
            MiniMapWindowBounds(4_000f, 3_000f, 420f, 360f),
            listOf(primary, left),
        )

        assertEquals(1500f, recovered.x)
        assertEquals(680f, recovered.y)
    }

    @Test
    fun `negative monitor coordinates are preserved and constrained to its work area`() {
        val recovered = MiniMapWindowPlacement.recover(
            MiniMapWindowBounds(-1200f, 100f, 420f, 360f),
            listOf(primary, left),
        )

        assertEquals(-1200f, recovered.x)
        assertEquals(100f, recovered.y)
    }

    @Test
    fun `snapping uses work area edges rather than taskbar-covered screen edges`() {
        val snapped = MiniMapWindowPlacement.snap(
            MiniMapWindowBounds(1491f, 672f, 420f, 360f),
            listOf(primary),
            threshold = 14f,
        )

        assertEquals(1500f, snapped.x)
        assertEquals(680f, snapped.y)
    }

    @Test
    fun `left-monitor edge snapping supports negative desktop coordinates`() {
        val snapped = MiniMapWindowPlacement.snap(
            MiniMapWindowBounds(-1272f, 7f, 420f, 360f),
            listOf(primary, left),
        )

        assertEquals(-1280f, snapped.x)
        assertEquals(0f, snapped.y)
    }
}
