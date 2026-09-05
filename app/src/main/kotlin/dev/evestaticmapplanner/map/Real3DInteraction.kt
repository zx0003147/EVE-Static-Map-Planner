package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import kotlin.math.hypot

internal enum class MapPointerButton {
    PRIMARY,
    SECONDARY,
}

internal data class MapDragUpdate(
    val button: MapPointerButton,
    val screenDelta: MapPoint,
)

internal data class MapClick(
    val button: MapPointerButton,
    val screenPosition: MapPoint,
)

/** Small deterministic state machine that keeps click and drag semantics independent of Compose/AWT event quirks. */
internal class Real3DPointerGestureTracker(
    private val dragThresholdPx: Double = REAL_3D_DRAG_THRESHOLD_PX,
) {
    private var button: MapPointerButton? = null
    private var pressedAt: MapPoint? = null
    private var lastPosition: MapPoint? = null
    private var dragging = false

    val isActive: Boolean get() = button != null
    val isDragging: Boolean get() = dragging

    fun press(button: MapPointerButton, position: MapPoint) {
        this.button = button
        pressedAt = position
        lastPosition = position
        dragging = false
    }

    fun move(position: MapPoint): MapDragUpdate? {
        val activeButton = button ?: return null
        val start = pressedAt ?: return null
        val last = lastPosition ?: return null
        if (!dragging && hypot(position.x - start.x, position.y - start.y) >= dragThresholdPx) dragging = true
        lastPosition = position
        return if (dragging) {
            MapDragUpdate(activeButton, MapPoint(position.x - last.x, position.y - last.y))
        } else {
            null
        }
    }

    fun release(position: MapPoint): MapClick? {
        val result = button?.takeUnless { dragging }?.let { MapClick(it, position) }
        cancel()
        return result
    }

    fun cancel() {
        button = null
        pressedAt = null
        lastPosition = null
        dragging = false
    }
}

internal const val REAL_3D_DRAG_THRESHOLD_PX = 4.0
