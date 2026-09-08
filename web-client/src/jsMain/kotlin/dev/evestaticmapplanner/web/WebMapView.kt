package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedSystemNode
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.math.abs
import kotlin.math.exp
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
) {
    private val context = canvas.getContext("2d") as CanvasRenderingContext2D
    private var state = WebPlannerState()
    private var canvasSize = MapSize(1.0, 1.0)
    private var viewport = MapViewport.fit(scene.defaultFitBounds, canvasSize)
    private var fitZoom = viewport.zoom
    private var pointerDown: MapPoint? = null
    private var lastPointer: MapPoint? = null
    private var pointerMoved = false
    private var activePointerId: Int? = null

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

    fun update(newState: WebPlannerState) {
        state = newState
        render()
    }

    fun fit() {
        viewport = MapViewport.fit(scene.defaultFitBounds, canvasSize)
        fitZoom = viewport.zoom
        render()
    }

    fun centerOn(systemId: Int): Boolean {
        val point = scene.nodesById[systemId]?.position ?: return false
        viewport = MapViewport(point, max(viewport.zoom, fitZoom * 5.0).coerceAtMost(fitZoom * MAX_ZOOM_FACTOR))
        render()
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
        render()
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
            render()
        }, js("({ passive: false })"))

        canvas.addEventListener("pointerdown", { raw ->
            val event = raw.asDynamic()
            if (activePointerId != null) return@addEventListener
            activePointerId = (event.pointerId as Number).toInt()
            val point = pointerPoint(event)
            pointerDown = point
            lastPointer = point
            pointerMoved = false
            canvas.asDynamic().setPointerCapture(event.pointerId)
        })
        canvas.addEventListener("pointermove", { raw ->
            val event = raw.asDynamic()
            val point = pointerPoint(event)
            if (activePointerId == (event.pointerId as Number).toInt()) {
                val previous = lastPointer ?: point
                val start = pointerDown ?: point
                if (abs(point.x - start.x) + abs(point.y - start.y) > DRAG_THRESHOLD_PX) pointerMoved = true
                viewport = viewport.panBy(point - previous)
                lastPointer = point
                if (pointerMoved) render()
            } else if (activePointerId == null) {
                onHover(pick(point))
            }
        })
        canvas.addEventListener("pointerup", { raw -> finishPointer(raw) })
        canvas.addEventListener("pointercancel", { raw -> finishPointer(raw, cancelled = true) })
        canvas.addEventListener("pointerleave", { _ -> if (activePointerId == null) onHover(null) })
    }

    private fun finishPointer(raw: Event, cancelled: Boolean = false) {
        val event = raw.asDynamic()
        if (activePointerId != (event.pointerId as Number).toInt()) return
        val point = pointerPoint(event)
        if (!cancelled && !pointerMoved) onSelect(pick(point))
        activePointerId = null
        pointerDown = null
        lastPointer = null
        pointerMoved = false
        runCatching { canvas.asDynamic().releasePointerCapture(event.pointerId) }
        onHover(if (cancelled) null else pick(point))
    }

    private fun pointerPoint(event: dynamic): MapPoint {
        val bounds = canvas.getBoundingClientRect()
        return MapPoint(
            (event.clientX as Number).toDouble() - bounds.left,
            (event.clientY as Number).toDouble() - bounds.top,
        )
    }

    private fun pick(screenPoint: MapPoint): Int? {
        val transform = MapTransform(viewport, canvasSize)
        val world = transform.screenToWorld(screenPoint)
        return scene.spatialIndex.nearest(world, PICK_RADIUS_PX / viewport.zoom)
    }

    private fun render() {
        val pixelRatio = (canvas.width.toDouble() / canvasSize.width).coerceAtLeast(1.0)
        context.setTransform(pixelRatio, 0.0, 0.0, pixelRatio, 0.0, 0.0)
        context.clearRect(0.0, 0.0, canvasSize.width, canvasSize.height)
        context.fillStyle = "#08111b"
        context.fillRect(0.0, 0.0, canvasSize.width, canvasSize.height)

        val transform = MapTransform(viewport, canvasSize)
        val visibleBounds = transform.visibleWorldBounds(32.0)
        drawGrid()
        drawStargates(transform, visibleBounds)
        drawJumpCoverage(transform, visibleBounds)
        drawNormalRoute(transform)
        drawCapitalRoute(transform)
        drawNodes(transform, visibleBounds)
        drawLabels(transform, visibleBounds)
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

    private fun drawJumpCoverage(transform: MapTransform, bounds: MapBounds) {
        val visibleIds = scene.spatialIndex.query(bounds).toSet()
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
        val route = state.normalRoute ?: return
        val overlay = ProjectedRouteOverlayBuilder.build(route, scene)
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

    private fun drawNodes(transform: MapTransform, bounds: MapBounds) {
        val routeIds = state.routeSystemIds
        val waypointIds = state.normalWaypointSystemIds.toSet()
        scene.spatialIndex.query(bounds).forEach { systemId ->
            val node = scene.nodesById.getValue(systemId)
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

    private fun drawLabels(transform: MapTransform, bounds: MapBounds) {
        val zoomRatio = viewport.zoom / fitZoom
        val budget = when {
            zoomRatio < 1.3 -> 70
            zoomRatio < 3.0 -> 160
            zoomRatio < 8.0 -> 420
            else -> 1_200
        }
        val priority = state.routeSystemIds + state.normalWaypointSystemIds + listOfNotNull(state.selectedSystemId, state.hoveredSystemId)
        val nodes = scene.spatialIndex.query(bounds).map(scene.nodesById::getValue).sortedWith(
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

private const val PICK_RADIUS_PX = 12.0
private const val DRAG_THRESHOLD_PX = 5.0
private const val MIN_ZOOM_FACTOR = 0.35
private const val MAX_ZOOM_FACTOR = 90.0
private const val LABEL_CELL_WIDTH = 78.0
private const val LABEL_CELL_HEIGHT = 20.0
private const val PI2 = 6.283185307179586
