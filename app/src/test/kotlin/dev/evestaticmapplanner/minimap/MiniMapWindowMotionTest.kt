package dev.evestaticmapplanner.minimap

import kotlin.test.Test
import kotlin.test.assertEquals

class MiniMapWindowMotionTest {
    private val startPointer = MiniMapScreenPoint(600, 400)
    private val startWindow = MiniMapScreenPoint(120, 80)
    private val drag = MiniMapWindowDragSession(startPointer, startWindow)

    @Test
    fun `positive horizontal pointer movement moves the window one to one`() {
        assertEquals(MiniMapScreenPoint(220, 80), drag.positionAt(MiniMapScreenPoint(700, 400)))
    }

    @Test
    fun `negative horizontal pointer movement moves the window one to one`() {
        assertEquals(MiniMapScreenPoint(20, 80), drag.positionAt(MiniMapScreenPoint(500, 400)))
    }

    @Test
    fun `positive vertical pointer movement moves the window one to one`() {
        assertEquals(MiniMapScreenPoint(120, 180), drag.positionAt(MiniMapScreenPoint(600, 500)))
    }

    @Test
    fun `negative vertical pointer movement moves the window one to one`() {
        assertEquals(MiniMapScreenPoint(120, -20), drag.positionAt(MiniMapScreenPoint(600, 300)))
    }

    @Test
    fun `diagonal pointer movement preserves both deltas`() {
        assertEquals(MiniMapScreenPoint(220, -20), drag.positionAt(MiniMapScreenPoint(700, 300)))
    }

    @Test
    fun `drag preserves negative virtual desktop coordinates`() {
        val leftMonitorDrag = MiniMapWindowDragSession(
            pointerAtStart = MiniMapScreenPoint(-500, 250),
            windowAtStart = MiniMapScreenPoint(-900, 100),
        )

        assertEquals(
            MiniMapScreenPoint(-1_000, 150),
            leftMonitorDrag.positionAt(MiniMapScreenPoint(-600, 300)),
        )
    }

    @Test
    fun `resize uses the same one to one AWT screen delta`() {
        val resize = MiniMapWindowResizeSession(
            pointerAtStart = startPointer,
            sizeAtStart = MiniMapWindowPixelSize(420, 360),
        )

        assertEquals(
            MiniMapWindowPixelSize(520, 460),
            resize.sizeAt(
                pointer = MiniMapScreenPoint(700, 500),
                minimum = MiniMapWindowPixelSize(280, 240),
                maximum = MiniMapWindowPixelSize(2_000, 2_000),
            ),
        )
    }
}
