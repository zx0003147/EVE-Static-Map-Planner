package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Real3DInteractionTest {
    @Test
    fun `single click still selects`() {
        val tracker = Real3DPointerGestureTracker(dragThresholdPx = 4.0)
        tracker.press(MapPointerButton.PRIMARY, MapPoint(10.0, 10.0))

        assertNull(tracker.move(MapPoint(12.0, 12.0)))
        val selected = mutableListOf<MapPoint>()
        val focused = mutableListOf<MapPoint>()
        dispatchMapClick(
            click = requireNotNull(tracker.release(MapPoint(12.0, 12.0), clickCount = 1)),
            onSelect = selected::add,
            onFocus = focused::add,
            onContextMenu = {},
        )

        assertEquals(listOf(MapPoint(12.0, 12.0)), selected)
        assertTrue(focused.isEmpty())
        assertFalse(tracker.isActive)
    }

    @Test
    fun `double click triggers focus`() {
        val tracker = Real3DPointerGestureTracker(dragThresholdPx = 4.0)
        tracker.press(MapPointerButton.PRIMARY, MapPoint(20.0, 20.0))
        val selected = mutableListOf<MapPoint>()
        val focused = mutableListOf<MapPoint>()

        dispatchMapClick(
            click = requireNotNull(tracker.release(MapPoint(21.0, 21.0), clickCount = 2)),
            onSelect = selected::add,
            onFocus = focused::add,
            onContextMenu = {},
        )

        assertTrue(selected.isEmpty())
        assertEquals(listOf(MapPoint(21.0, 21.0)), focused)
    }

    @Test
    fun `drag does not trigger focus`() {
        val tracker = Real3DPointerGestureTracker(dragThresholdPx = 4.0)
        tracker.press(MapPointerButton.PRIMARY, MapPoint(10.0, 10.0))
        assertEquals(
            MapDragUpdate(MapPointerButton.PRIMARY, MapPoint(5.0, 0.0)),
            tracker.move(MapPoint(15.0, 10.0)),
        )
        val focused = mutableListOf<MapPoint>()

        tracker.release(MapPoint(15.0, 10.0), clickCount = 2)?.let { click ->
            dispatchMapClick(click, onSelect = {}, onFocus = focused::add, onContextMenu = {})
        }

        assertTrue(focused.isEmpty())
    }

    @Test
    fun `secondary drag emits rotation deltas and suppresses context click`() {
        val tracker = Real3DPointerGestureTracker(dragThresholdPx = 4.0)
        tracker.press(MapPointerButton.SECONDARY, MapPoint(10.0, 10.0))

        val first = tracker.move(MapPoint(14.0, 13.0))
        val second = tracker.move(MapPoint(17.0, 15.0))

        assertEquals(MapDragUpdate(MapPointerButton.SECONDARY, MapPoint(4.0, 3.0)), first)
        assertEquals(MapDragUpdate(MapPointerButton.SECONDARY, MapPoint(3.0, 2.0)), second)
        assertTrue(tracker.isDragging)
        assertNull(tracker.release(MapPoint(17.0, 15.0)))
    }

    @Test
    fun `cancel clears active gesture`() {
        val tracker = Real3DPointerGestureTracker()
        tracker.press(MapPointerButton.PRIMARY, MapPoint(0.0, 0.0))
        tracker.cancel()

        assertFalse(tracker.isActive)
        assertNull(tracker.move(MapPoint(10.0, 10.0)))
        assertNull(tracker.release(MapPoint(10.0, 10.0)))
    }
}
