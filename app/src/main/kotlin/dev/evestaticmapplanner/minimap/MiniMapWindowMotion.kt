package dev.evestaticmapplanner.minimap

/** Coordinates from AWT's virtual desktop. All values are logical screen pixels. */
internal data class MiniMapScreenPoint(
    val x: Int,
    val y: Int,
)

internal data class MiniMapWindowPixelSize(
    val width: Int,
    val height: Int,
)

internal data class MiniMapWindowDragSession(
    private val pointerAtStart: MiniMapScreenPoint,
    private val windowAtStart: MiniMapScreenPoint,
) {
    fun positionAt(pointer: MiniMapScreenPoint): MiniMapScreenPoint = MiniMapScreenPoint(
        x = windowAtStart.x + pointer.x - pointerAtStart.x,
        y = windowAtStart.y + pointer.y - pointerAtStart.y,
    )
}

internal data class MiniMapWindowResizeSession(
    private val pointerAtStart: MiniMapScreenPoint,
    private val sizeAtStart: MiniMapWindowPixelSize,
) {
    fun sizeAt(
        pointer: MiniMapScreenPoint,
        minimum: MiniMapWindowPixelSize,
        maximum: MiniMapWindowPixelSize,
    ): MiniMapWindowPixelSize = MiniMapWindowPixelSize(
        width = (sizeAtStart.width + pointer.x - pointerAtStart.x).coerceIn(minimum.width, maximum.width),
        height = (sizeAtStart.height + pointer.y - pointerAtStart.y).coerceIn(minimum.height, maximum.height),
    )
}
