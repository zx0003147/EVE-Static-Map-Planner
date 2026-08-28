package dev.evestaticmapplanner.map

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextMenuPositionTest {
    private val viewport = IntSize(1_000, 700)
    private val popup = IntSize(210, 420)

    @Test
    fun `center anchor opens down and right`() {
        assertPosition(IntOffset(300, 120), IntOffset(300, 120))
    }

    @Test
    fun `bottom anchor opens upward`() {
        assertPosition(IntOffset(400, 650), IntOffset(400, 230))
    }

    @Test
    fun `top anchor remains below anchor`() {
        assertPosition(IntOffset(400, 4), IntOffset(400, 4))
    }

    @Test
    fun `left anchor clamps inside viewport`() {
        assertPosition(IntOffset(-12, 100), IntOffset(0, 100))
    }

    @Test
    fun `right anchor opens left`() {
        assertPosition(IntOffset(980, 100), IntOffset(770, 100))
    }

    @Test
    fun `bottom right anchor opens up and left`() {
        assertPosition(IntOffset(990, 690), IntOffset(780, 270))
    }

    @Test
    fun `popup larger than remaining bottom space stays within viewport`() {
        val tallPopup = IntSize(210, 520)
        val result = calculateContextMenuPosition(IntOffset(500, 300), tallPopup, viewport)
        assertEquals(IntOffset(500, 0), result)
        assertWithinViewport(result, tallPopup)
    }

    private fun assertPosition(anchor: IntOffset, expected: IntOffset) {
        val result = calculateContextMenuPosition(anchor, popup, viewport)
        assertEquals(expected, result)
        assertWithinViewport(result, popup)
    }

    private fun assertWithinViewport(position: IntOffset, size: IntSize) {
        assertTrue(position.x >= 0)
        assertTrue(position.y >= 0)
        assertTrue(position.x + size.width <= viewport.width)
        assertTrue(position.y + size.height <= viewport.height)
    }
}
