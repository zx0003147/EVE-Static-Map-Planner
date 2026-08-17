package dev.evestaticmapplanner.core.map

import kotlin.math.min

data class MapViewport(
    val center: MapPoint,
    val zoom: Double,
) {
    init {
        require(zoom.isFinite() && zoom > 0.0) { "Map zoom must be finite and positive" }
    }

    fun panBy(screenDelta: MapPoint): MapViewport = copy(
        center = MapPoint(
            x = center.x - screenDelta.x / zoom,
            y = center.y - screenDelta.y / zoom,
        ),
    )

    companion object {
        fun fit(bounds: MapBounds, canvasSize: MapSize, paddingPx: Double = 48.0): MapViewport {
            require(!canvasSize.isEmpty) { "Cannot fit an empty canvas" }
            require(paddingPx.isFinite() && paddingPx >= 0.0)
            val usableWidth = (canvasSize.width - paddingPx * 2.0).coerceAtLeast(1.0)
            val usableHeight = (canvasSize.height - paddingPx * 2.0).coerceAtLeast(1.0)
            val width = bounds.width.coerceAtLeast(MIN_WORLD_SPAN)
            val height = bounds.height.coerceAtLeast(MIN_WORLD_SPAN)
            return MapViewport(bounds.center, min(usableWidth / width, usableHeight / height))
        }
    }
}

class MapTransform(
    val viewport: MapViewport,
    val canvasSize: MapSize,
) {
    init {
        require(!canvasSize.isEmpty) { "Map transform requires a non-empty canvas" }
    }

    fun worldToScreen(point: MapPoint): MapPoint = MapPoint(
        x = (point.x - viewport.center.x) * viewport.zoom + canvasSize.width / 2.0,
        y = (point.y - viewport.center.y) * viewport.zoom + canvasSize.height / 2.0,
    )

    fun screenToWorld(point: MapPoint): MapPoint = MapPoint(
        x = (point.x - canvasSize.width / 2.0) / viewport.zoom + viewport.center.x,
        y = (point.y - canvasSize.height / 2.0) / viewport.zoom + viewport.center.y,
    )

    fun zoomAt(
        screenAnchor: MapPoint,
        factor: Double,
        minZoom: Double,
        maxZoom: Double,
    ): MapViewport {
        require(factor.isFinite() && factor > 0.0)
        val worldAnchor = screenToWorld(screenAnchor)
        val newZoom = (viewport.zoom * factor).coerceIn(minZoom, maxZoom)
        val newCenter = MapPoint(
            x = worldAnchor.x - (screenAnchor.x - canvasSize.width / 2.0) / newZoom,
            y = worldAnchor.y - (screenAnchor.y - canvasSize.height / 2.0) / newZoom,
        )
        return MapViewport(newCenter, newZoom)
    }

    fun visibleWorldBounds(marginPx: Double = 0.0): MapBounds {
        require(marginPx.isFinite() && marginPx >= 0.0)
        return MapBounds(
            minX = viewport.center.x - (canvasSize.width / 2.0 + marginPx) / viewport.zoom,
            minY = viewport.center.y - (canvasSize.height / 2.0 + marginPx) / viewport.zoom,
            maxX = viewport.center.x + (canvasSize.width / 2.0 + marginPx) / viewport.zoom,
            maxY = viewport.center.y + (canvasSize.height / 2.0 + marginPx) / viewport.zoom,
        )
    }
}

private const val MIN_WORLD_SPAN = 1e-9
