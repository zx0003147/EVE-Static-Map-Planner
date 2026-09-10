package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.MapVisualSemantics
import dev.evestaticmapplanner.core.map.PrimarySystemNodeShape
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlay
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedSystemNode
import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
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
    private val ansiblexLinks: () -> List<WebAnsiblexLink> = { emptyList() },
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
    private var semanticMode = WebSemanticLabelMode.REGION
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
        if (state.mapPreferences != newState.mapPreferences) {
            semanticMode = WebSemanticZoomPolicy.initial(viewport.zoom, newState.mapPreferences)
        }
        state = newState
        sharedMarkerState = newSharedMarkerState
        scheduleRender()
    }

    fun fit() {
        viewport = MapViewport.fit(scene.defaultFitBounds, canvasSize)
        fitZoom = viewport.zoom
        semanticMode = WebSemanticZoomPolicy.initial(viewport.zoom, state.mapPreferences)
        scheduleRender()
    }

    fun centerOn(systemId: Int): Boolean {
        val point = scene.nodesById[systemId]?.position ?: return false
        viewport = MapViewport(point, max(viewport.zoom, fitZoom * 5.0).coerceAtMost(fitZoom * MAX_ZOOM_FACTOR))
        semanticMode = WebSemanticZoomPolicy.transition(semanticMode, viewport.zoom, state.mapPreferences)
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
            semanticMode = WebSemanticZoomPolicy.initial(viewport.zoom, state.mapPreferences)
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
            semanticMode = WebSemanticZoomPolicy.transition(semanticMode, viewport.zoom, state.mapPreferences)
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
        semanticMode = WebSemanticZoomPolicy.transition(semanticMode, viewport.zoom, state.mapPreferences)
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
        val primaryNodeShapesBySystemId = resolvePrimaryNodeShapes(visibleSystemIds)
        drawGrid()
        drawStargates(transform, visibleBounds)
        drawAnsiblexNetwork(transform, visibleBounds)
        drawJumpCoverage(transform, visibleSystemIds)
        drawNormalRoute(transform)
        drawCapitalRoute(transform)
        drawNodes(transform, visibleNodes, primaryNodeShapesBySystemId)
        drawSharedMarkers(transform, visibleSystemIds)
        drawLabels(transform, visibleNodes)
        drawInteraction(transform, primaryNodeShapesBySystemId)
        drawWaypoints(transform)
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
        context.strokeStyle = cssArgb(MapVisualSemantics.stargateNetwork.argb)
        context.lineWidth = MapVisualSemantics.stargateNetwork.widthPx
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

    private fun drawAnsiblexNetwork(transform: MapTransform, bounds: MapBounds) {
        ansiblexLinks().forEach { link ->
            val firstWorld = scene.nodesById[link.firstSystemId]?.position ?: return@forEach
            val secondWorld = scene.nodesById[link.secondSystemId]?.position ?: return@forEach
            if (!MapBounds.between(firstWorld, secondWorld).intersects(bounds)) return@forEach
            val geometry = ansiblexGeometry(
                link.firstSystemId,
                link.secondSystemId,
                transform.worldToScreen(firstWorld),
                transform.worldToScreen(secondWorld),
            )
            context.beginPath()
            context.moveTo(geometry.start.x, geometry.start.y)
            context.quadraticCurveTo(geometry.control.x, geometry.control.y, geometry.end.x, geometry.end.y)
            context.strokeStyle = cssArgb(MapVisualSemantics.ansiblexNetwork.argb)
            context.lineWidth = MapVisualSemantics.ansiblexNetwork.widthPx
            context.setLineDash(MapVisualSemantics.ansiblexNetwork.dashPatternPx.toTypedArray())
            context.stroke()
        }
        context.setLineDash(emptyArray())
    }

    private fun drawJumpCoverage(transform: MapTransform, visibleIds: Set<Int>) {
        state.jumpOverlays.filter { it.enabled }.forEachIndexed { index, overlay ->
            val color = JUMP_OVERLAY_COLORS[index % JUMP_OVERLAY_COLORS.size]
            val radius = 5.0 + (index % 4) * 2.2
            overlay.reachableSystemIds.asSequence().filter { it in visibleIds }.forEach { systemId ->
                val point = scene.nodesById[systemId]?.position?.let(transform::worldToScreen) ?: return@forEach
                context.beginPath()
                context.arc(point.x, point.y, radius, 0.0, PI2)
                context.strokeStyle = color
                context.lineWidth = 1.5
                context.stroke()
            }
            val point = scene.nodesById[overlay.originSystemId]?.position?.let(transform::worldToScreen)
                ?: return@forEachIndexed
            context.beginPath()
            context.arc(point.x, point.y, 11.0, 0.0, PI2)
            context.fillStyle = colorWithAlpha(color, 0.20)
            context.fill()
            context.beginPath()
            context.arc(point.x, point.y, 7.0 + (index % 3), 0.0, PI2)
            context.strokeStyle = color
            context.lineWidth = 2.0
            context.stroke()
        }
        state.coverageCounts.asSequence().filter { it.key in visibleIds && it.value > 1 }.forEach { (systemId, _) ->
            val point = scene.nodesById[systemId]?.position?.let(transform::worldToScreen) ?: return@forEach
            context.beginPath()
            context.arc(point.x, point.y, 13.0, 0.0, PI2)
            context.fillStyle = "rgba(255, 209, 102, 0.24)"
            context.fill()
            context.beginPath()
            context.arc(point.x, point.y, 10.0, 0.0, PI2)
            context.strokeStyle = "#ffd166"
            context.lineWidth = 2.5
            context.stroke()
        }
    }

    private fun drawNormalRoute(transform: MapTransform) {
        val overlay = normalRouteOverlay ?: return
        overlay.legs.forEach { leg ->
            val first = transform.worldToScreen(leg.from)
            val second = transform.worldToScreen(leg.to)
            val style = MapVisualSemantics.normalRouteByEdgeType.getValue(leg.edge.type)
            val geometry = if (style.curved) {
                ansiblexGeometry(leg.edge.fromSystemId, leg.edge.toSystemId, first, second)
            } else null
            context.beginPath()
            context.moveTo(first.x, first.y)
            if (style.curved) {
                context.quadraticCurveTo(geometry!!.control.x, geometry.control.y, second.x, second.y)
            } else {
                context.lineTo(second.x, second.y)
            }
            val routeColor = cssArgb(style.argb)
            val routeWidth = style.widthPx
            context.setLineDash(style.dashPatternPx.toTypedArray())
            context.strokeStyle = routeColor
            context.lineWidth = routeWidth
            context.stroke()
            drawRouteArrows(
                geometry ?: QuadraticGeometry(first, midpoint(first, second), second),
                geometry != null,
                routeColor,
                routeWidth,
            )
        }
        context.setLineDash(emptyArray())
        drawEndpoint(transform, overlay.route.startSystemId, "#57e389", 12.0, 8.0)
        drawEndpoint(transform, overlay.route.destinationSystemId, "#ff5d73", 12.0, 8.0)
    }

    private fun drawCapitalRoute(transform: MapTransform) {
        val route = state.capitalRoute ?: return
        val style = MapVisualSemantics.capitalRoute
        val routeColor = cssArgb(style.argb)
        context.strokeStyle = routeColor
        context.lineWidth = style.widthPx
        context.setLineDash(emptyArray())
        route.systems.zipWithNext().forEach { (fromId, toId) ->
            val from = scene.nodesById[fromId]?.position?.let(transform::worldToScreen) ?: return@forEach
            val to = scene.nodesById[toId]?.position?.let(transform::worldToScreen) ?: return@forEach
            context.beginPath()
            context.moveTo(from.x, from.y)
            context.lineTo(to.x, to.y)
            context.stroke()
            drawRouteArrows(QuadraticGeometry(from, midpoint(from, to), to), false, routeColor, style.widthPx)
        }
        drawEndpoint(transform, route.startSystemId, "#a98bff", 13.0, 9.0)
        drawEndpoint(transform, route.destinationSystemId, "#ff7eb6", 13.0, 9.0)
    }

    private fun drawNodes(
        transform: MapTransform,
        nodes: List<ProjectedSystemNode>,
        primaryNodeShapesBySystemId: Map<Int, PrimarySystemNodeShape>,
    ) {
        val routeIds = state.routeSystemIds
        val waypointIds = (state.normalWaypointSystemIds + state.capitalWaypointSystemIds).toSet()
        nodes.forEach { node ->
            val systemId = node.system.id
            val point = transform.worldToScreen(node.position)
            val inRoute = systemId in routeIds
            val shape = primaryNodeShapesBySystemId[systemId] ?: PrimarySystemNodeShape.SYSTEM
            if (shape != PrimarySystemNodeShape.SYSTEM) {
                val stroke = MapVisualSemantics.nodeOutlineSemantics(
                    shape,
                    dev.evestaticmapplanner.core.map.SystemNodeInteractionState.NORMAL,
                )
                drawPrimaryNodeOutline(point, shape, primaryNodeColor(systemId, shape, inRoute), stroke.widthPx)
                return@forEach
            }
            val radius = systemNodeRadius(node, systemId)
            context.beginPath()
            context.arc(point.x, point.y, radius, 0.0, PI2)
            context.fillStyle = when {
                systemId in waypointIds -> "#ffca5d"
                inRoute -> "#5fe5f6"
                node.isStargateConnected -> "#75b9e7"
                else -> "#596673"
            }
            context.fill()
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
        val priority = state.routeSystemIds + state.normalWaypointSystemIds + sharedMarkerState.markersBySystemId.keys +
            state.fortizarSystemIds + state.keepstarSystemIds + listOfNotNull(state.selectedSystemId, state.hoveredSystemId)
        if (semanticMode == WebSemanticLabelMode.REGION) {
            context.font = "16px Inter, system-ui, sans-serif"
            context.asDynamic().textAlign = "center"
            context.asDynamic().textBaseline = "middle"
            context.fillStyle = "rgba(232, 242, 250, 0.86)"
            scene.regions.forEach { region ->
                val point = transform.worldToScreen(region.canonicalAnchor)
                if (point.x in -100.0..(canvasSize.width + 100.0) && point.y in -40.0..(canvasSize.height + 40.0)) {
                    context.fillText(region.name, point.x, point.y)
                }
            }
        } else {
            context.font = "20px Inter, system-ui, sans-serif"
            context.asDynamic().textAlign = "center"
            context.fillStyle = "rgba(215, 230, 242, 0.07)"
            scene.regions.forEach { region ->
                val point = transform.worldToScreen(region.canonicalAnchor)
                context.fillText(region.name, point.x, point.y)
            }
        }
        if (semanticMode == WebSemanticLabelMode.CONSTELLATION) {
            context.font = "13px Inter, system-ui, sans-serif"
            context.fillStyle = "rgba(196, 217, 234, 0.80)"
            scene.constellations.forEach { constellation ->
                val point = transform.worldToScreen(constellation.canonicalAnchor)
                if (point.x in -100.0..(canvasSize.width + 100.0) && point.y in -40.0..(canvasSize.height + 40.0)) {
                    context.fillText(constellation.name, point.x, point.y)
                }
            }
        }
        context.asDynamic().textAlign = "start"
        val nodes = visibleNodes.sortedWith(
            compareBy<ProjectedSystemNode>({ it.system.id !in priority }, { it.system.name.lowercase() }),
        )
        val occupied = mutableSetOf<String>()
        context.font = "11px Inter, system-ui, sans-serif"
        context.asDynamic().textBaseline = "middle"
        var drawn = 0
        for (node in nodes) {
            val forced = node.system.id in priority
            if (!forced && (semanticMode != WebSemanticLabelMode.SYSTEM || visibleNodes.size > 700)) continue
            val point = transform.worldToScreen(node.position)
            val key = "${(point.x / LABEL_CELL_WIDTH).toInt()}:${(point.y / LABEL_CELL_HEIGHT).toInt()}"
            if (!forced && !occupied.add(key)) continue
            context.fillStyle = if (forced) "rgba(239, 247, 252, 0.98)" else "rgba(194, 211, 222, 0.72)"
            context.fillText(node.system.name, point.x + 6.0, point.y)
            drawn++
        }
    }

    private fun drawInteraction(
        transform: MapTransform,
        primaryNodeShapesBySystemId: Map<Int, PrimarySystemNodeShape>,
    ) {
        state.selectedSystemId?.let {
            drawFocus(transform, it, primaryNodeShapesBySystemId, isHovered = false, isSelected = true)
        }
        state.hoveredSystemId?.takeIf { it != state.selectedSystemId }?.let {
            drawFocus(transform, it, primaryNodeShapesBySystemId, isHovered = true, isSelected = false)
        }
    }

    private fun drawFocus(
        transform: MapTransform,
        systemId: Int,
        primaryNodeShapesBySystemId: Map<Int, PrimarySystemNodeShape>,
        isHovered: Boolean,
        isSelected: Boolean,
    ) {
        val node = scene.nodesById[systemId] ?: return
        val point = transform.worldToScreen(node.position)
        val shape = primaryNodeShapesBySystemId[systemId] ?: PrimarySystemNodeShape.SYSTEM
        val interaction = MapVisualSemantics.interactionState(isHovered, isSelected)
        val stroke = MapVisualSemantics.nodeOutlineSemantics(shape, interaction)
        val baseArgb = if (shape == PrimarySystemNodeShape.SYSTEM) {
            0L
        } else {
            primaryNodeColor(systemId, shape, systemId in state.routeSystemIds).toOpaqueArgb()
        }
        val color = cssArgb(MapVisualSemantics.nodeOutlineColorArgb(shape, interaction, baseArgb))
        if (shape == PrimarySystemNodeShape.SYSTEM) {
            context.beginPath()
            context.arc(point.x, point.y, systemNodeRadius(node, systemId), 0.0, PI2)
            context.strokeStyle = color
            context.lineWidth = stroke.widthPx
            context.stroke()
        } else {
            drawPrimaryNodeOutline(point, shape, color, stroke.widthPx)
        }
    }

    private fun drawWaypoints(transform: MapTransform) {
        drawWaypointSet(transform, state.normalWaypointSystemIds, "rgba(66, 214, 245, 0.86)")
        drawWaypointSet(transform, state.capitalWaypointSystemIds, "rgba(179, 136, 255, 0.86)")
        context.asDynamic().textAlign = "start"
    }

    private fun drawWaypointSet(transform: MapTransform, systemIds: List<Int>, color: String) {
        systemIds.forEachIndexed { index, systemId ->
            val point = scene.nodesById[systemId]?.position?.let(transform::worldToScreen) ?: return@forEachIndexed
            val center = MapPoint(point.x + 11.0, point.y - 10.0)
            context.beginPath()
            context.arc(center.x, center.y, 7.0, 0.0, PI2)
            context.fillStyle = "rgba(22, 36, 48, 0.90)"
            context.fill()
            context.strokeStyle = color
            context.lineWidth = 1.25
            context.stroke()
            context.font = "8px Inter, system-ui, sans-serif"
            context.asDynamic().textAlign = "center"
            context.asDynamic().textBaseline = "middle"
            context.fillStyle = "#f1f5f8"
            context.fillText((index + 1).toString(), center.x, center.y)
        }
    }

    private fun drawEndpoint(transform: MapTransform, systemId: Int, color: String, haloRadius: Double, radius: Double) {
        val point = scene.nodesById[systemId]?.position?.let(transform::worldToScreen) ?: return
        context.beginPath()
        context.arc(point.x, point.y, haloRadius, 0.0, PI2)
        context.fillStyle = colorWithAlpha(color, 0.25)
        context.fill()
        context.beginPath()
        context.arc(point.x, point.y, radius, 0.0, PI2)
        context.strokeStyle = color
        context.lineWidth = 3.0
        context.stroke()
    }

    private fun drawPrimaryNodeOutline(
        point: MapPoint,
        shape: PrimarySystemNodeShape,
        color: String,
        strokeWidth: Double,
    ) {
        val size = MapVisualSemantics.PRIMARY_STRUCTURE_NODE_SIZE_PX
        val points = MapVisualSemantics.primaryNodeOutline(shape)
        context.beginPath()
        points.forEachIndexed { index, outlinePoint ->
            val x = point.x + (outlinePoint.x - .5) * size
            val y = point.y + (outlinePoint.y - .5) * size
            if (index == 0) context.moveTo(x, y) else context.lineTo(x, y)
        }
        context.closePath()
        context.strokeStyle = color
        context.lineWidth = strokeWidth
        context.setLineDash(emptyArray())
        context.stroke()
    }

    private fun resolvePrimaryNodeShapes(visibleSystemIds: Set<Int>): Map<Int, PrimarySystemNodeShape> {
        val localTaggedSystemIds = (state.fortizarSystemIds + state.keepstarSystemIds).intersect(visibleSystemIds)
        val localTagsBySystemId: Map<Int, Iterable<String>> = localTaggedSystemIds.associateWith { systemId ->
            buildList {
                if (systemId in state.fortizarSystemIds) add(MapVisualSemantics.FORTIZAR_MARKER_TAG)
                if (systemId in state.keepstarSystemIds) add(MapVisualSemantics.KEEPSTAR_MARKER_TAG)
            }
        }
        val sharedTagsBySystemId: Map<Int, Iterable<String>> = sharedMarkerState.markersBySystemId
            .filterKeys { it in visibleSystemIds }
            .mapValues { (_, marker) -> marker.tags }
        return MapVisualSemantics.primaryNodeShapes(localTagsBySystemId, sharedTagsBySystemId)
    }

    private fun primaryNodeColor(systemId: Int, shape: PrimarySystemNodeShape, inRoute: Boolean): String {
        if (inRoute) return "#eef7fc"
        val localTags = buildList {
            if (systemId in state.fortizarSystemIds) add(MapVisualSemantics.FORTIZAR_MARKER_TAG)
            if (systemId in state.keepstarSystemIds) add(MapVisualSemantics.KEEPSTAR_MARKER_TAG)
        }
        val localShape = MapVisualSemantics.primaryNodeShape(localTags, emptyList())
        if (localShape == shape) return "#75b9e7"
        return sharedMarkerState.markersBySystemId[systemId]?.let { sharedMarkerColor(it.color) } ?: "#75b9e7"
    }

    private fun systemNodeRadius(node: ProjectedSystemNode, systemId: Int): Double = when {
        systemId in state.normalWaypointSystemIds || systemId in state.capitalWaypointSystemIds -> 4.8
        systemId in state.routeSystemIds -> 4.0
        node.isStargateConnected -> 2.2
        else -> 1.8
    }

    private fun drawRouteArrows(geometry: QuadraticGeometry, curved: Boolean, color: String, strokeWidth: Double) {
        val length = if (curved) approximateLength(geometry) else distance(geometry.start, geometry.end)
        if (length < 48.0) return
        val count = min(3, max(1, (length / 90.0).toInt()))
        repeat(count) { index ->
            val t = (index + 1.0) / (count + 1.0)
            val point = if (curved) quadraticPoint(geometry, t) else lerp(geometry.start, geometry.end, t)
            val tangent = if (curved) quadraticTangent(geometry, t) else MapPoint(
                geometry.end.x - geometry.start.x,
                geometry.end.y - geometry.start.y,
            )
            val magnitude = hypot(tangent.x, tangent.y).coerceAtLeast(0.001)
            val ux = tangent.x / magnitude
            val uy = tangent.y / magnitude
            val arrowLength = min(13.0, max(10.0, length / 12.0))
            val half = arrowLength * .52
            val tail = MapPoint(point.x - ux * arrowLength, point.y - uy * arrowLength)
            val left = MapPoint(tail.x - uy * half, tail.y + ux * half)
            val right = MapPoint(tail.x + uy * half, tail.y - ux * half)
            listOf(1.8 to "rgba(9, 18, 29, 0.78)", 0.0 to color).forEach { (extra, arrowColor) ->
                context.beginPath()
                context.moveTo(left.x, left.y)
                context.lineTo(point.x, point.y)
                context.lineTo(right.x, right.y)
                context.strokeStyle = arrowColor
                context.lineWidth = strokeWidth + extra
                context.setLineDash(emptyArray())
                context.stroke()
            }
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

private data class QuadraticGeometry(val start: MapPoint, val control: MapPoint, val end: MapPoint)

private fun ansiblexGeometry(firstId: Int, secondId: Int, start: MapPoint, end: MapPoint): QuadraticGeometry {
    val canonicalStart = if (firstId <= secondId) start else end
    val canonicalEnd = if (firstId <= secondId) end else start
    val dx = canonicalEnd.x - canonicalStart.x
    val dy = canonicalEnd.y - canonicalStart.y
    val length = hypot(dx, dy).coerceAtLeast(0.001)
    val offset = (length * 0.22).coerceIn(12.0, 64.0)
    val sign = if (((min(firstId, secondId) * 31L + max(firstId, secondId)) and 1L) == 0L) 1.0 else -1.0
    val control = MapPoint(
        (canonicalStart.x + canonicalEnd.x) / 2.0 - dy / length * offset * sign,
        (canonicalStart.y + canonicalEnd.y) / 2.0 + dx / length * offset * sign,
    )
    return QuadraticGeometry(start, control, end)
}

private fun quadraticPoint(geometry: QuadraticGeometry, t: Double): MapPoint {
    val one = 1.0 - t
    return MapPoint(
        one * one * geometry.start.x + 2.0 * one * t * geometry.control.x + t * t * geometry.end.x,
        one * one * geometry.start.y + 2.0 * one * t * geometry.control.y + t * t * geometry.end.y,
    )
}

private fun quadraticTangent(geometry: QuadraticGeometry, t: Double): MapPoint = MapPoint(
    2.0 * (1.0 - t) * (geometry.control.x - geometry.start.x) + 2.0 * t * (geometry.end.x - geometry.control.x),
    2.0 * (1.0 - t) * (geometry.control.y - geometry.start.y) + 2.0 * t * (geometry.end.y - geometry.control.y),
)

private fun approximateLength(geometry: QuadraticGeometry): Double {
    var total = 0.0
    var previous = geometry.start
    repeat(12) { index ->
        val next = quadraticPoint(geometry, (index + 1) / 12.0)
        total += distance(previous, next)
        previous = next
    }
    return total
}

private fun lerp(first: MapPoint, second: MapPoint, t: Double) = MapPoint(
    first.x + (second.x - first.x) * t,
    first.y + (second.y - first.y) * t,
)

private fun colorWithAlpha(hex: String, alpha: Double): String {
    val value = hex.removePrefix("#")
    if (value.length != 6) return hex
    val red = value.substring(0, 2).toInt(16)
    val green = value.substring(2, 4).toInt(16)
    val blue = value.substring(4, 6).toInt(16)
    return "rgba($red, $green, $blue, $alpha)"
}

private fun cssArgb(argb: Long): String {
    val alpha = ((argb shr 24) and 0xFFL).toDouble() / 255.0
    val red = (argb shr 16) and 0xFFL
    val green = (argb shr 8) and 0xFFL
    val blue = argb and 0xFFL
    return "rgba($red, $green, $blue, $alpha)"
}

private fun String.toOpaqueArgb(): Long {
    val value = removePrefix("#")
    require(value.length == 6) { "Expected an opaque #RRGGBB color" }
    return 0xFF00_0000L or value.toInt(16).toLong()
}

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
private val JUMP_OVERLAY_COLORS = listOf("#57e389", "#42d6f5", "#ff9f43", "#b388ff", "#ff7eb6", "#9fe870")
