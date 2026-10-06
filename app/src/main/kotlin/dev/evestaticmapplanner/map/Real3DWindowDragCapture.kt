package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import java.awt.AWTEvent
import java.awt.IllegalComponentStateException
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/** Translates window-screen pointer positions back into the map-local coordinate space of the initial press. */
internal class Real3DDragCaptureSession {
    private var localAnchor: MapPoint? = null
    private var screenAnchor: MapPoint? = null

    val isActive: Boolean get() = localAnchor != null

    fun start(localPosition: MapPoint, screenPosition: MapPoint) {
        localAnchor = localPosition
        screenAnchor = screenPosition
    }

    fun positionAt(screenPosition: MapPoint): MapPoint? {
        val local = localAnchor ?: return null
        val screen = screenAnchor ?: return null
        return MapPoint(
            x = local.x + screenPosition.x - screen.x,
            y = local.y + screenPosition.y - screen.y,
        )
    }

    fun releaseAt(screenPosition: MapPoint): MapPoint? = positionAt(screenPosition).also { cancel() }

    fun cancel() {
        localAnchor = null
        screenAnchor = null
    }
}

/**
 * Temporary AWT-level capture for a drag that began on the 3D map.
 *
 * Compose pointer events are dispatched only to the hit composable. A listener scoped to the originating AWT
 * window keeps receiving native drag/release events after the pointer crosses into sibling Compose UI or leaves
 * the map bounds. It observes without consuming events and is removed on release, focus loss, or disposal.
 */
internal class Real3DWindowDragCapture : AutoCloseable {
    private val session = Real3DDragCaptureSession()
    private var capturedWindow: Window? = null
    private var capturedButton: Int = MouseEvent.NOBUTTON
    private var listenerInstalled = false
    private var onMove: (MapPoint) -> Unit = {}
    private var onRelease: (MapPoint, Int) -> Unit = { _, _ -> }
    private var onCancel: () -> Unit = {}

    val isActive: Boolean get() = session.isActive

    fun updateHandlers(
        onMove: (MapPoint) -> Unit,
        onRelease: (MapPoint, Int) -> Unit,
        onCancel: () -> Unit,
    ) {
        this.onMove = onMove
        this.onRelease = onRelease
        this.onCancel = onCancel
    }

    fun start(localPosition: MapPoint, event: MouseEvent): Boolean {
        val window = SwingUtilities.getWindowAncestor(event.component) ?: return false
        val screenPosition = event.screenPositionOrNull() ?: return false
        cancel(notify = false)
        capturedWindow = window
        capturedButton = event.button
        session.start(localPosition, screenPosition)
        installListener()
        return true
    }

    private val eventListener = AWTEventListener { event ->
        when (event) {
            is MouseEvent -> handleMouseEvent(event)
            is WindowEvent -> if (
                event.window === capturedWindow &&
                (event.id == WindowEvent.WINDOW_LOST_FOCUS || event.id == WindowEvent.WINDOW_DEACTIVATED)
            ) {
                cancel(notify = true)
            }
        }
    }

    private fun handleMouseEvent(event: MouseEvent) {
        if (!isActive || !event.belongsTo(capturedWindow)) return
        val screenPosition = event.screenPositionOrNull() ?: return
        when (event.id) {
            MouseEvent.MOUSE_DRAGGED -> session.positionAt(screenPosition)?.let(onMove)
            MouseEvent.MOUSE_RELEASED -> if (event.button == capturedButton) {
                val localPosition = session.releaseAt(screenPosition) ?: return
                uninstallListener()
                capturedWindow = null
                capturedButton = MouseEvent.NOBUTTON
                onRelease(localPosition, event.clickCount)
            }
        }
    }

    private fun installListener() {
        if (listenerInstalled) return
        Toolkit.getDefaultToolkit().addAWTEventListener(
            eventListener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or
                AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK,
        )
        listenerInstalled = true
    }

    private fun uninstallListener() {
        if (!listenerInstalled) return
        Toolkit.getDefaultToolkit().removeAWTEventListener(eventListener)
        listenerInstalled = false
    }

    fun cancel() = cancel(notify = true)

    private fun cancel(notify: Boolean) {
        val wasActive = isActive
        session.cancel()
        capturedWindow = null
        capturedButton = MouseEvent.NOBUTTON
        uninstallListener()
        if (notify && wasActive) onCancel()
    }

    override fun close() = cancel(notify = true)
}

private fun MouseEvent.belongsTo(window: Window?): Boolean =
    window != null && SwingUtilities.getWindowAncestor(component) === window

private fun MouseEvent.screenPositionOrNull(): MapPoint? = try {
    MapPoint(xOnScreen.toDouble(), yOnScreen.toDouble())
} catch (_: IllegalComponentStateException) {
    null
}
