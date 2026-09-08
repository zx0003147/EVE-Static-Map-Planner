package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlay
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedSystemNode
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import kotlinx.browser.window

class WebMapView(
    private val canvas: HTMLCanvasElement,
    private val scene: ProjectedMapScene,
    private val onSelect: (Int?) -> Unit,
    private val onHover: (Int?) -> Unit,
    private val onContextAction: (Int, MapPoint, String) -> Unit,
) {
    private val context = canvas.getContext("2d") as CanvasRenderingContext2D
    private var state = WebPlannerState()
    private var sharedMarkerState = WebSharedMarkerState()
    private var canvasSize = MapSize(1.0, 1.0)
    private var viewport = MapViewport.fit(scene.defaultFitBounds, canvasSize)
    private var fitZoom = viewport.zoom
    private val pointers = mutableMapOf<Int, PointerContact>()
    private var primaryPointerId: Int? = null
    private var gestureConsumed = false
    private var dragging = false
    private var longPressTriggered = false
    private var longPressTimer: Int? = null
    private var pinchCenter: MapPoint? = null
    private var pinchDistance: Double? = null
    private var maxPinchFocalDriftPx = 0.0
    private var normalRouteOverlay: ProjectedRouteOverlay? = null
    private var renderPending = false
    private var renderCount = 0
    private var longRenderCount = 0
    private var maxRenderMillis = 0.0

    init {
        bindInput()
        resize()
        window.addEventListener("resize", { resize() })
        val observerConstructor = window.asDynamic().ResizeObserver
        if (observerConstructor != null) {
            val callback: (dynamic) -> Unit = { resize() }
            js("new observerConstructor(callback)").observe(canvas)
        }
    }

    fun update(newState: WebPlannerState, newSharedMarkerState: WebSharedMarkerState) {
        if (state.normalRoute != newState.normalRoute) {
            normalRouteOverlay = newState.normalRoute?.let { ProjectedRouteOverlayBuilder.build(it, scene) }
        }
        state = newState
        sharedMarkerState = newSharedMarkerState
        scheduleRender()
    }

    fun fit() {
        viewport = MapViewport.fit(scene.defaultFitBounds, canvasSize)
        fitZoom = viewport.zoom
        scheduleRender()
    }

    fun centerOn(systemId: Int): Boolean {
        val point = scene.nodesById[systemId]?.position ?: return false
        viewport = MapViewport(point, max(viewport.zoom, fitZoom * 5.0).coerceAtMost(fitZoom * MAX_ZOOM_FACTOR))
        scheduleRender()
        return true
    }

    fun resize() {
        val bounds = canvas.getBoundingClientRect()
        val width = max(1.0, bounds.width)
        val height = max(1.0, bounds.height)
        val pixelRatio = (window.devicePixelRatio).coerceIn(1.0, 3.0)
        val pixelWidth = (width * pixelRatio).toInt()
        val pixelHeight = (height * pixelRatio).toInt()
        val firstLayout = canvasSize.width <= 1.0 && canvasSize.height <= 1.0
        if (canvas.width != pixelWidth || canvas.height != pixelHeight) {
            canvas.width = pixelWidth
            canvas.height = pixelHeight
            canvas.style.width = "${width}px"
            canvas.style.height = "${height}px"
        }
        canvasSize = MapSize(width, height)
        if (firstLayout) {
            viewport = MapViewport.fit(scene.defaultFitBounds, canvasSize)
            fitZoom = viewport.zoom
        } else {
            fitZoom = MapViewport.fit(scene.defaultFitBounds, canvasSize).zoom
        }
        scheduleRender()
    }

    fun diagnostics(): dynamic {
        val value = js("({})")
        value.centerX = viewport.center.x
        value.centerY = viewport.center.y
        value.zoom = viewport.zoom
        value.fitZoom = fitZoom
        value.canvasCssWidth = canvasSize.width
        value.canvasCssHeight = canvasSize.height
        value.canvasPixelWidth = canvas.width
        value.canvasPixelHeight = canvas.height
        value.effectiveDpr = canvas.width.toDouble() / canvasSize.width
        value.selectedSystemId = state.selectedSystemId
        value.renderCount = renderCount
        value.longRenderCount = longRenderCount
        value.maxRenderMillis = maxRenderMillis
        value.maxPinchFocalDriftPx = maxPinchFocalDriftPx
        return value
    }

    private fun bindInput() {
        canvas.addEventListener("wheel", { raw ->
            val event = raw.asDynamic()
            event.preventDefault()
            val point = pointerPoint(event)
            val factor = exp(-(event.deltaY as Number).toDouble() * 0.0015)
            viewport = MapTransform(viewport, canvasSize).zoomAt(
                point,
                factor,
                fitZoom * MIN_ZOOM_FACTOR,
                fitZoom * MAX_ZOOM_FACTOR,
            )
            scheduleRender()
        }, js("({ passive: false })"))

        canvas.addEventListener("pointerdown", { raw ->
            val event = raw.asDynamic()
            if ((event.button as? Number)?.toInt()?.let { it != 0 } == true) return@addEventListener
            event.preventDefault()
            val pointerId = (event.pointerId as Number).toInt()
            val point = pointerPoint(event)
            pointers[pointerId] = PointerContact(point, point, pointerType(event))
            canvas.asDynamic().setPointerCapture(event.pointerId)
            if (pointers.size == 1) {
                primaryPointerId = pointerId
                gestureConsumed = false
                dragging = false
                longPressTriggered = false
                scheduleLongPress(pointerId)
            } else {
                cancelLongPress()
                gestureConsumed = true
                dragging = false
                beginPinch()
            }
        })
        canvas.addEventListener("pointermove", { raw ->
            val event = raw.asDynamic()
            val pointerId = (event.pointerId as Number).toInt()
            val point = pointerPoint(event)
            val contact = pointers[pointerId]
            if (contact != null) {
                event.preventDefault()
                val previous = contact.current
                contact.current = point
                if (pointers.size >= 2) {
                    updatePinch()
                } else if (!gestureConsumed && pointerId == primaryPointerId) {
                    val threshold = dragThreshold(contact.pointerType)
                    if (!dragging && distance(point, contact.start) >= threshold) {
                        dragging = true
                        cancelLongPress()
                        viewport = viewport.panBy(point - contact.start)
                        scheduleRender()
                    } else if (dragging) {
                        viewport = viewport.panBy(point - previous)
                        scheduleRender()
                    }
                }
            } else if (pointers.isEmpty() && pointerType(event) != "touch") {
                onHover(pick(point, PICK_RADIUS_PX))
            }
        })
        canvas.addEventListener("pointerup", { raw -> finishPointer(raw) })
        canvas.addEventListener("pointercancel", { raw -> finishPointer(raw, cancelled = true) })
        canvas.addEventListener("pointerleave", { _ -> if (pointers.isEmpty()) onHover(null) })
        canvas.addEventListener("contextmenu", { raw ->
            val event = raw.asDynamic()
            event.preventDefault()
            val point = pointerPoint(event)
            pick(point, CONTEXT_PICK_RADIUS_PX)?.let { onContextAction(it, point, "mouse") }
        })
    }

    private fun finishPointer(raw: Event, cancelled: Boolean = false) {
        val event = raw.asDynamic()
        val pointerId = (event.pointerId as Number).toInt()
        val contact = pointers[pointerId] ?: return
        val point = pointerPoint(event)
        val wasOnlyPointer = pointers.size == 1
        val shouldTap = !cancelled && wasOnlyPointer && pointerId == primaryPointerId && !gestureConsumed &&
            !dragging && !longPressTriggered && distance(point, contact.start) < dragThreshold(contact.pointerType)
        pointers.remove(pointerId)
        runCatching { canvas.asDynamic().releasePointerCapture(event.pointerId) }
        cancelLongPress()
        if (shouldTap) onSelect(pick(point, pickRadius(contact.pointerType)))
        if (pointers.isEmpty()) {
            primaryPointerId = null
            gestureConsumed = false
            dragging = false
            longPressTriggered = false
            pinchCenter = null
            pinchDistance = null
        } else {
            gestureConsumed = true
            beginPinch()
        }
        if (contact.pointerType != "touch") onHover(if (cancelled) null else pick(point, PICK_RADIUS_PX))
    }

    private fun scheduleLongPress(pointerId: Int) {
        cancelLongPress()
        val contact = pointers[pointerId] ?: return
        if (contact.pointerType == "mouse") return
        longPressTimer = window.setTimeout({
            val current = pointers[pointerId]
            if (current != null && pointers.size == 1 && !dragging && !gestureConsumed &&
                distance(current.start, current.current) < dragThreshold(current.pointerType)
            ) {
                pick(current.current, CONTEXT_PICK_RADIUS_PX)?.let { systemId ->
                    longPressTriggered = true
                    gestureConsumed = true
                    onContextAction(systemId, current.current, current.pointerType)
                }
            }
            longPressTimer = null
        }, LONG_PRESS_MILLIS)
    }

    private fun cancelLongPress() {
        longPressTimer?.let(window::clearTimeout)
        longPressTimer = null
    }

    private fun beginPinch() {
        val pair = pointers.values.take(2)
        if (pair.size < 2) {
            pinchCenter = null
            pinchDistance = null
            return
        }
        pinchCenter = midpoint(pair[0].current, pair[1].current)
        pinchDistance = distance(pair[0].current, pair[1].current).coerceAtLeast(1.0)
    }

    private fun updatePinch() {
        val pair = pointers.values.take(2)
        if (pair.size < 2) return
        val center = midpoint(pair[0].current, pair[1].current)
        val currentDistance = distance(pair[0].current, pair[1].current).coerceAtLeast(1.0)
        val previousCenter = pinchCenter ?: center
        val previousDistance = pinchDistance ?: currentDistance
        val focalWorldBefore = MapTransform(viewport, canvasSize).screenToWorld(previousCenter)
        viewport = viewport.panBy(center - previousCenter)
        viewport = MapTransform(viewport, canvasSize).zoomAt(
            center,
            currentDistance / previousDistance,
            fitZoom * MIN_ZOOM_FACTOR,
            fitZoom * MAX_ZOOM_FACTOR,
        )
        val focalWorldAfter = MapTransform(viewport, canvasSize).screenToWorld(center)
        maxPinchFocalDriftPx = max(maxPinchFocalDriftPx, distance(focalWorldBefore, focalWorldAfter) * viewport.zoom)
        pinchCenter = center
        pinchDistance = currentDistance
        gestureConsumed = true
        scheduleRender()
    }

    private fun pointerPoint(event: dynamic): MapPoint {
        val bounds = canvas.getBoundingClientRect()
        return MapPoint(
            (event.clientX as Number).toDouble() - bounds.left,
            (event.clientY as Number).toDouble() - bounds.top,
        )
    }

    private fun pick(screenPoint: MapPoint, radiusPixels: Double): Int? {
        val transform = MapTransform(viewport, canvasSize)
        val world = transform.screenToWorld(screenPoint)
        return scene.spatialIndex.nearest(world, radiusPixels / viewport.zoom)
    }

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        window.requestAnimationFrame {
            renderPending = false
            render()
        }
    }

    private fun render() {
        val startedAt = window.performance.now()
        val pixelRatio = (canvas.width.toDouble() / canvasSize.width).coerceAtLeast(1.0)
        context.setTransform(pixelRatio, 0.0, 0.0, pixelRatio, 0.0, 0.0)
        context.clearRect(0.0, 0.0, canvasSize.width, canvasSize.height)
        context.fillStyle = "#08111b"
        context.fillRect(0.0, 0.0, canvasSize.width, canvasSize.height)

        val transform = MapTransform(viewport, canvasSize)
        val visibleBounds = transform.visibleWorldBounds(32.0)
        val visibleNodes = scene.spatialIndex.query(visibleBounds).map(scene.nodesById::getValue)
        val visibleSystemIds = visibleNodes.mapTo(mutableSetOf()) { it.system.id }
        drawGrid()
        drawStargates(transform, visibleBounds)
        drawJumpCoverage(transform, visibleSystemIds)
        drawNormalRoute(transform)
        drawCapitalRoute(transform)
        drawNodes(transform, visibleNodes)
        drawSharedMarkers(transform, visibleSystemIds)
        drawLabels(transform, visibleNodes)
        val elapsed = window.performance.now() - startedAt
        renderCount++
        maxRenderMillis = max(maxRenderMillis, elapsed)
        if (elapsed > LONG_RENDER_MILLIS) longRenderCount++
    }

    private fun drawGrid() {
        context.strokeStyle = "rgba(80, 122, 151, 0.08)"
        context.lineWidth = 1.0
        context.beginPath()
        val spacing = 80.0
        var x = canvasSize.width % spacing
        while (x < canvasSize.width) { context.moveTo(x, 0.0); context.lineTo(x, canvasSize.height); x += spacing }
        var y = canvasSize.height % spacing
        while (y < canvasSize.height) { context.moveTo(0.0, y); context.lineTo(canvasSize.width, y); y += spacing }
        context.stroke()
    }

    private fun drawStargates(transform: MapTransform, bounds: MapBounds) {
        context.strokeStyle = "rgba(104, 135, 156, 0.22)"
        context.lineWidth = 0.8
        context.setLineDash(emptyArray())
        context.beginPath()
        scene.edges.asSequence().filter { it.bounds.intersects(bounds) }.forEach { edge ->
            val first = transform.worldToScreen(edge.first)
            val second = transform.worldToScreen(edge.second)
            context.moveTo(first.x, first.y)
            context.lineTo(second.x, second.y)
        }
        context.stroke()
    }

    private fun drawJumpCoverage(transform: MapTransform, visibleIds: Set<Int>) {
        state.coverageCounts.asSequence().filter { it.key in visibleIds }.forEach { (systemId, count) ->
            val point = scene.nodesById[systemId]?.position?.let(transform::worldToScreen) ?: return@forEach
            val radius = 7.0 + min(count, 4) * 2.0
            context.beginPath()
            context.arc(point.x, point.y, radius, 0.0, PI2)
            context.fillStyle = if (count > 1) "rgba(245, 114, 82, 0.20)" else "rgba(177, 108, 255, 0.16)"
            context.fill()
            context.strokeStyle = if (count > 1) "rgba(255, 145, 92, 0.85)" else "rgba(188, 132, 255, 0.72)"
            context.lineWidth = if (count > 1) 2.0 else 1.2
            context.stroke()
        }
        state.jumpOverlays.forEach { overlay ->
            val point = scene.nodesById[overlay.originSystemId]?.position?.let(transform::worldToScreen) ?: return@forEach
            context.fillStyle = "#d99aff"
            context.fillRect(point.x - 4.5, point.y - 4.5, 9.0, 9.0)
        }
    }

    private fun drawNormalRoute(transform: MapTransform) {
        val overlay = normalRouteOverlay ?: return
        overlay.legs.forEach { leg ->
            val first = transform.worldToScreen(leg.from)
            val second = transform.worldToScreen(leg.to)
            context.beginPath()
            context.moveTo(first.x, first.y)
            context.lineTo(second.x, second.y)
            if (leg.edge.type == RouteEdgeType.ANSIBLEX) {
                context.strokeStyle = "#ffad52"
                context.lineWidth = 3.2
                context.setLineDash(arrayOf(8.0, 5.0))
            } else {
                context.strokeStyle = "#4ed7ed"
                context.lineWidth = 2.6
                context.setLineDash(emptyArray())
            }
            context.stroke()
        }
        context.setLineDash(emptyArray())
    }

    private fun drawCapitalRoute(transform: MapTransform) {
        val route = state.capitalRoute ?: return
        context.strokeStyle = "#ee6dff"
        context.lineWidth = 3.0
        context.setLineDash(arrayOf(4.0, 5.0))
        route.systems.zipWithNext().forEach { (fromId, toId) ->
            val from = scene.nodesById[fromId]?.position?.let(transform::worldToScreen) ?: return@forEach
            val to = scene.nodesById[toId]?.position?.let(transform::worldToScreen) ?: return@forEach
            context.beginPath()
            context.moveTo(from.x, from.y)
            context.lineTo(to.x, to.y)
            context.stroke()
        }
        context.setLineDash(emptyArray())
    }

    private fun drawNodes(transform: MapTransform, nodes: List<ProjectedSystemNode>) {
        val routeIds = state.routeSystemIds
        val waypointIds = state.normalWaypointSystemIds.toSet()
        nodes.forEach { node ->
            val systemId = node.system.id
            val point = transform.worldToScreen(node.position)
            val selected = systemId == state.selectedSystemId
            val hovered = systemId == state.hoveredSystemId
            val inRoute = systemId in routeIds
            val radius = when {
                selected -> 5.5
                systemId in waypointIds -> 4.8
                inRoute -> 4.0
                hovered -> 3.6
                else -> 1.8
            }
            context.beginPath()
            context.arc(point.x, point.y, radius, 0.0, PI2)
            context.fillStyle = when {
                selected -> "#ffe182"
                systemId in waypointIds -> "#ffca5d"
                inRoute -> "#5fe5f6"
                node.system.securityStatus >= 0.45 -> "#6fc98d"
                node.system.securityStatus > 0.0 -> "#e7cb6b"
                else -> "#d36d75"
            }
            context.fill()
            if (selected) {
                context.beginPath()
                context.arc(point.x, point.y, 9.0, 0.0, PI2)
                context.strokeStyle = "rgba(255, 225, 130, 0.8)"
                context.lineWidth = 1.5
                context.stroke()
            }
        }
    }

    private fun drawSharedMarkers(transform: MapTransform, visibleIds: Set<Int>) {
        sharedMarkerState.markers.values.asSequence()
            .filter { it.systemId in visibleIds }
            .forEach { marker ->
                val point = scene.nodesById[marker.systemId]?.position?.let(transform::worldToScreen) ?: return@forEach
                val selected = marker.markerId == sharedMarkerState.selectedMarkerId
                context.beginPath()
                context.arc(point.x, point.y, if (selected) 13.5 else 10.5, 0.0, PI2)
                context.strokeStyle = sharedMarkerColor(marker.color)
                context.lineWidth = if (selected) 3.0 else 1.8
                context.setLineDash(if (sharedMarkerState.status == WebSharedMarkerStatus.RECONNECTING) arrayOf(3.0, 3.0) else emptyArray())
                context.stroke()
                context.setLineDash(emptyArray())
                context.beginPath()
                context.arc(point.x + 7.0, point.y + 7.0, if (selected) 4.5 else 3.5, 0.0, PI2)
                context.fillStyle = sharedMarkerColor(marker.color)
                context.fill()
                context.strokeStyle = "#07101a"
                context.lineWidth = 1.0
                context.stroke()
            }
    }

    private fun drawLabels(transform: MapTransform, visibleNodes: List<ProjectedSystemNode>) {
        val zoomRatio = viewport.zoom / fitZoom
        val budget = when {
            zoomRatio < 1.3 -> 70
            zoomRatio < 3.0 -> 160
            zoomRatio < 8.0 -> 420
            else -> 1_200
        }
        val priority = state.routeSystemIds + state.normalWaypointSystemIds + sharedMarkerState.markersBySystemId.keys +
            listOfNotNull(state.selectedSystemId, state.hoveredSystemId)
        val nodes = visibleNodes.sortedWith(
            compareBy<ProjectedSystemNode>({ it.system.id !in priority }, { it.system.name.lowercase() }),
        )
        val occupied = mutableSetOf<String>()
        context.font = "11px Inter, system-ui, sans-serif"
        context.asDynamic().textBaseline = "middle"
        var drawn = 0
        for (node in nodes) {
            val forced = node.system.id in priority
            if (!forced && drawn >= budget) continue
            val point = transform.worldToScreen(node.position)
            val key = "${(point.x / LABEL_CELL_WIDTH).toInt()}:${(point.y / LABEL_CELL_HEIGHT).toInt()}"
            if (!forced && !occupied.add(key)) continue
            context.fillStyle = if (forced) "rgba(239, 247, 252, 0.98)" else "rgba(194, 211, 222, 0.72)"
            context.fillText(node.system.name, point.x + 6.0, point.y)
            drawn++
        }
    }
}

private data class PointerContact(
    val start: MapPoint,
    var current: MapPoint,
    val pointerType: String,
)

private fun pointerType(event: dynamic): String = (event.pointerType as? String)?.ifBlank { "mouse" } ?: "mouse"

private fun midpoint(first: MapPoint, second: MapPoint): MapPoint =
    MapPoint((first.x + second.x) / 2.0, (first.y + second.y) / 2.0)

private fun distance(first: MapPoint, second: MapPoint): Double = hypot(second.x - first.x, second.y - first.y)

private fun dragThreshold(pointerType: String): Double = if (pointerType == "mouse") MOUSE_DRAG_THRESHOLD_PX else TOUCH_DRAG_THRESHOLD_PX

private fun pickRadius(pointerType: String): Double = if (pointerType == "mouse") PICK_RADIUS_PX else TOUCH_PICK_RADIUS_PX

private fun sharedMarkerColor(color: String): String = when (color) {
    "RED" -> "#ff5d73"
    "ORANGE" -> "#ff9f43"
    "YELLOW" -> "#ffd166"
    "GREEN" -> "#57e389"
    "PURPLE" -> "#a98bff"
    "WHITE" -> "#f1f5f8"
    else -> "#42bff5"
}

private const val PICK_RADIUS_PX = 12.0
private const val TOUCH_PICK_RADIUS_PX = 22.0
private const val CONTEXT_PICK_RADIUS_PX = 24.0
private const val MOUSE_DRAG_THRESHOLD_PX = 5.0
private const val TOUCH_DRAG_THRESHOLD_PX = 10.0
private const val LONG_PRESS_MILLIS = 550
private const val LONG_RENDER_MILLIS = 100.0
private const val MIN_ZOOM_FACTOR = 0.35
private const val MAX_ZOOM_FACTOR = 90.0
private const val LABEL_CELL_WIDTH = 78.0
private const val LABEL_CELL_HEIGHT = 20.0
private const val PI2 = 6.283185307179586
