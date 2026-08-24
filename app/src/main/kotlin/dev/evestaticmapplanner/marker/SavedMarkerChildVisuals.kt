package dev.evestaticmapplanner.marker

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import java.util.Locale

enum class SavedMarkerChildIconKind {
    FLAG,
    PEOPLE,
    WARNING,
    PACKAGE,
    HOME,
    SHIELD,
    FACTORY,
    STAR,
    KEEPSTAR_BRACKET,
    GENERIC,
}

data class SavedMarkerChildVisual(
    val type: SavedMarkerChildType?,
    val label: String,
    val iconKind: SavedMarkerChildIconKind,
    val resourcePath: String? = null,
)

object SavedMarkerChildVisuals {
    val known: List<SavedMarkerChildVisual> = listOf(
        known("staging", "Staging", SavedMarkerChildIconKind.FLAG),
        known("rally", "Rally", SavedMarkerChildIconKind.PEOPLE),
        known("danger", "Danger", SavedMarkerChildIconKind.WARNING),
        known("logistics", "Logistics", SavedMarkerChildIconKind.PACKAGE),
        known("home", "Home", SavedMarkerChildIconKind.HOME),
        known("backup", "Backup", SavedMarkerChildIconKind.SHIELD),
        known("industrial", "Industrial", SavedMarkerChildIconKind.FACTORY),
        known("strategic", "Strategic", SavedMarkerChildIconKind.STAR),
        known("keepstar", "Keepstar", SavedMarkerChildIconKind.KEEPSTAR_BRACKET),
    )

    private val byKey = known.associateBy { checkNotNull(it.type).key }

    fun resolve(type: SavedMarkerChildType): SavedMarkerChildVisual = byKey[type.key] ?: SavedMarkerChildVisual(
        type = type,
        label = type.key.split(Regex("[._-]+"))
            .filter(String::isNotBlank)
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
            .ifBlank { "Unknown" },
        iconKind = SavedMarkerChildIconKind.GENERIC,
    )

    fun availableFor(children: List<SavedMarkerChild>): List<SavedMarkerChildVisual> {
        val assigned = children.mapTo(hashSetOf()) { it.type.key }
        return known.filter { checkNotNull(it.type).key !in assigned }
    }

    private fun known(
        key: String,
        label: String,
        iconKind: SavedMarkerChildIconKind,
        resourcePath: String? = null,
    ) = SavedMarkerChildVisual(SavedMarkerChildType.of(key), label, iconKind, resourcePath)
}

@Composable
internal fun SavedMarkerChildIcon(
    visual: SavedMarkerChildVisual,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFFF1F5F8),
) {
    Canvas(modifier) {
        drawSavedMarkerChildIcon(visual, center, size.minDimension * 0.82f, tint)
    }
}

internal fun DrawScope.drawSavedMarkerChildIcon(
    visual: SavedMarkerChildVisual,
    center: Offset,
    size: Float,
    tint: Color,
) {
    val half = size / 2f
    val left = center.x - half
    val top = center.y - half
    val stroke = (size * 0.11f).coerceAtLeast(1f)
    when (visual.iconKind) {
        SavedMarkerChildIconKind.KEEPSTAR_BRACKET -> {
            val path = Path().apply {
                KEEPSTAR_BRACKET_POINTS.forEachIndexed { index, point ->
                    val x = left + size * point.x
                    val y = top + size * point.y
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(path, tint, style = Stroke(stroke))
        }
        SavedMarkerChildIconKind.FLAG -> {
            drawLine(tint, Offset(left + size * 0.24f, top + size * 0.12f), Offset(left + size * 0.24f, top + size * 0.9f), stroke)
            val path = Path().apply {
                moveTo(left + size * 0.28f, top + size * 0.16f)
                lineTo(left + size * 0.84f, top + size * 0.28f)
                lineTo(left + size * 0.28f, top + size * 0.48f)
                close()
            }
            drawPath(path, tint)
        }
        SavedMarkerChildIconKind.PEOPLE -> {
            drawCircle(tint, size * 0.16f, Offset(center.x - size * 0.18f, top + size * 0.34f))
            drawCircle(tint, size * 0.14f, Offset(center.x + size * 0.22f, top + size * 0.39f))
            drawArc(tint, 180f, 180f, false, Offset(left + size * 0.08f, top + size * 0.44f), androidx.compose.ui.geometry.Size(size * 0.56f, size * 0.46f), style = Stroke(stroke))
            drawArc(tint, 180f, 180f, false, Offset(left + size * 0.46f, top + size * 0.51f), androidx.compose.ui.geometry.Size(size * 0.42f, size * 0.34f), style = Stroke(stroke))
        }
        SavedMarkerChildIconKind.WARNING -> {
            val path = Path().apply {
                moveTo(center.x, top + size * 0.08f)
                lineTo(left + size * 0.94f, top + size * 0.9f)
                lineTo(left + size * 0.06f, top + size * 0.9f)
                close()
            }
            drawPath(path, tint, style = Stroke(stroke))
            drawLine(tint, Offset(center.x, top + size * 0.34f), Offset(center.x, top + size * 0.64f), stroke)
            drawCircle(tint, stroke * 0.65f, Offset(center.x, top + size * 0.79f))
        }
        SavedMarkerChildIconKind.PACKAGE -> {
            drawRect(tint, Offset(left + size * 0.12f, top + size * 0.22f), androidx.compose.ui.geometry.Size(size * 0.76f, size * 0.65f), style = Stroke(stroke))
            drawLine(tint, Offset(left + size * 0.12f, top + size * 0.43f), Offset(left + size * 0.88f, top + size * 0.43f), stroke)
            drawLine(tint, Offset(center.x, top + size * 0.22f), Offset(center.x, top + size * 0.43f), stroke)
        }
        SavedMarkerChildIconKind.HOME -> {
            val path = Path().apply {
                moveTo(left + size * 0.08f, top + size * 0.48f)
                lineTo(center.x, top + size * 0.1f)
                lineTo(left + size * 0.92f, top + size * 0.48f)
                moveTo(left + size * 0.2f, top + size * 0.43f)
                lineTo(left + size * 0.2f, top + size * 0.9f)
                lineTo(left + size * 0.8f, top + size * 0.9f)
                lineTo(left + size * 0.8f, top + size * 0.43f)
            }
            drawPath(path, tint, style = Stroke(stroke))
        }
        SavedMarkerChildIconKind.SHIELD -> {
            val path = Path().apply {
                moveTo(center.x, top + size * 0.08f)
                cubicTo(left + size * 0.7f, top + size * 0.18f, left + size * 0.88f, top + size * 0.22f, left + size * 0.88f, top + size * 0.48f)
                cubicTo(left + size * 0.88f, top + size * 0.72f, left + size * 0.7f, top + size * 0.86f, center.x, top + size * 0.96f)
                cubicTo(left + size * 0.3f, top + size * 0.86f, left + size * 0.12f, top + size * 0.72f, left + size * 0.12f, top + size * 0.48f)
                cubicTo(left + size * 0.12f, top + size * 0.22f, left + size * 0.3f, top + size * 0.18f, center.x, top + size * 0.08f)
                close()
            }
            drawPath(path, tint, style = Stroke(stroke))
        }
        SavedMarkerChildIconKind.FACTORY -> {
            val path = Path().apply {
                moveTo(left + size * 0.08f, top + size * 0.9f)
                lineTo(left + size * 0.08f, top + size * 0.42f)
                lineTo(left + size * 0.38f, top + size * 0.58f)
                lineTo(left + size * 0.38f, top + size * 0.42f)
                lineTo(left + size * 0.67f, top + size * 0.58f)
                lineTo(left + size * 0.67f, top + size * 0.2f)
                lineTo(left + size * 0.86f, top + size * 0.2f)
                lineTo(left + size * 0.92f, top + size * 0.9f)
                close()
            }
            drawPath(path, tint, style = Stroke(stroke))
        }
        SavedMarkerChildIconKind.STAR -> {
            val path = Path()
            repeat(10) { index ->
                val angle = Math.toRadians(-90.0 + index * 36.0)
                val radius = if (index % 2 == 0) half * 0.94f else half * 0.42f
                val point = Offset(center.x + kotlin.math.cos(angle).toFloat() * radius, center.y + kotlin.math.sin(angle).toFloat() * radius)
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            path.close()
            drawPath(path, tint, style = Stroke(stroke))
        }
        SavedMarkerChildIconKind.GENERIC -> {
            drawCircle(tint, half * 0.72f, center, style = Stroke(stroke))
            drawCircle(tint, stroke * 0.72f, center)
        }
    }
}

internal val KEEPSTAR_BRACKET_POINTS = listOf(
    Offset(0.40f, 0.13f),
    Offset(0.07f, 0.13f),
    Offset(0.07f, 0.87f),
    Offset(0.93f, 0.87f),
    Offset(0.93f, 0.13f),
    Offset(0.60f, 0.13f),
    Offset(0.60f, 0.47f),
    Offset(0.40f, 0.47f),
)
