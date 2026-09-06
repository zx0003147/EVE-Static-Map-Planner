package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.preferences.MiniMapWindowBounds
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.abs

internal data class MiniMapScreenWorkArea(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

internal fun interface MiniMapWorkAreaProvider {
    fun workAreas(): List<MiniMapScreenWorkArea>
}

internal object AwtMiniMapWorkAreaProvider : MiniMapWorkAreaProvider {
    override fun workAreas(): List<MiniMapScreenWorkArea> = runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            val configuration = device.defaultConfiguration
            val bounds = configuration.bounds
            val insets = toolkit.getScreenInsets(configuration)
            MiniMapScreenWorkArea(
                x = (bounds.x + insets.left).toFloat(),
                y = (bounds.y + insets.top).toFloat(),
                width = (bounds.width - insets.left - insets.right).coerceAtLeast(1).toFloat(),
                height = (bounds.height - insets.top - insets.bottom).coerceAtLeast(1).toFloat(),
            )
        }
    }.getOrDefault(emptyList())
}

internal object MiniMapWindowPlacement {
    fun recover(bounds: MiniMapWindowBounds, workAreas: List<MiniMapScreenWorkArea>): MiniMapWindowBounds {
        val target = bestArea(bounds, workAreas) ?: return bounds
        val maxX = (target.right - bounds.width).coerceAtLeast(target.x)
        val maxY = (target.bottom - bounds.height).coerceAtLeast(target.y)
        return bounds.copy(
            x = bounds.x.coerceIn(target.x, maxX),
            y = bounds.y.coerceIn(target.y, maxY),
        )
    }

    fun snap(
        bounds: MiniMapWindowBounds,
        workAreas: List<MiniMapScreenWorkArea>,
        threshold: Float = 14f,
    ): MiniMapWindowBounds {
        val target = bestArea(bounds, workAreas) ?: return bounds
        var x = bounds.x
        var y = bounds.y
        if (abs(bounds.x - target.x) <= threshold) x = target.x
        if (abs(bounds.y - target.y) <= threshold) y = target.y
        val rightAlignedX = target.right - bounds.width
        val bottomAlignedY = target.bottom - bounds.height
        if (bounds.width <= target.width && abs(bounds.x - rightAlignedX) <= threshold) x = rightAlignedX
        if (bounds.height <= target.height && abs(bounds.y - bottomAlignedY) <= threshold) y = bottomAlignedY
        return bounds.copy(x = x, y = y)
    }

    private fun bestArea(
        bounds: MiniMapWindowBounds,
        workAreas: List<MiniMapScreenWorkArea>,
    ): MiniMapScreenWorkArea? = workAreas.maxWithOrNull(
        compareBy<MiniMapScreenWorkArea> { intersectionArea(bounds, it) }
            .thenBy { -centerDistanceSquared(bounds, it) },
    )

    private fun intersectionArea(bounds: MiniMapWindowBounds, area: MiniMapScreenWorkArea): Float {
        val width = (minOf(bounds.x + bounds.width, area.right) - maxOf(bounds.x, area.x)).coerceAtLeast(0f)
        val height = (minOf(bounds.y + bounds.height, area.bottom) - maxOf(bounds.y, area.y)).coerceAtLeast(0f)
        return width * height
    }

    private fun centerDistanceSquared(bounds: MiniMapWindowBounds, area: MiniMapScreenWorkArea): Float {
        val dx = bounds.x + bounds.width / 2f - (area.x + area.width / 2f)
        val dy = bounds.y + bounds.height / 2f - (area.y + area.height / 2f)
        return dx * dx + dy * dy
    }
}
