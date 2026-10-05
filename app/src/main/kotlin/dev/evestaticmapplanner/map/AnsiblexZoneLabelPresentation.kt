package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.forceprojection.ForceProjectionZoneSnapshot
import dev.evestaticmapplanner.core.forceprojection.shortLabel
import dev.evestaticmapplanner.preferences.MapDisplayPreferences
import kotlin.math.atan2
import kotlin.math.hypot

internal data class AnsiblexZoneLabel(
    val text: String,
    val first: Offset,
    val second: Offset,
    val angleDegrees: Float,
    val color: Color,
)

internal data class AnsiblexZoneLabelCurveSegment(
    val start: Offset,
    val control: Offset,
    val end: Offset,
)

internal data class AnsiblexZoneLabelLayoutResult(
    val center: Offset,
    val gapStart: Offset,
    val gapEnd: Offset,
    val gapStartT: Float,
    val gapEndT: Float,
    val firstSegment: AnsiblexZoneLabelCurveSegment,
    val secondSegment: AnsiblexZoneLabelCurveSegment,
)

/** Screen-space label semantics shared by the 2D and Real 3D renderers. */
internal object AnsiblexZoneLabelPresentation {
    private const val CLOSE_COORDINATE_EPSILON_PX = 0.5f

    fun build(
        connection: AnsiblexConnection,
        first: Offset,
        second: Offset,
        zones: ForceProjectionZoneSnapshot,
        color: Color = Color.Unspecified,
    ): AnsiblexZoneLabel {
        val firstZone = zones.get(connection.firstSystemId)?.zone?.shortLabel ?: "?"
        val secondZone = zones.get(connection.secondSystemId)?.zone?.shortLabel ?: "?"
        val text = when (connection.direction) {
            AnsiblexDirection.FIRST_TO_SECOND -> "[$secondZone]"
            AnsiblexDirection.SECOND_TO_FIRST -> "[$firstZone]"
            AnsiblexDirection.BIDIRECTIONAL -> {
                if (firstZone == secondZone && firstZone != "?") {
                    "[$firstZone]"
                } else {
                    val firstIsLeft = firstEndpointPrecedesSecond(
                        connection.firstSystemId,
                        connection.secondSystemId,
                        first,
                        second,
                    )
                    val leftZone = if (firstIsLeft) firstZone else secondZone
                    val rightZone = if (firstIsLeft) secondZone else firstZone
                    "[$leftZone/$rightZone]"
                }
            }
        }
        return AnsiblexZoneLabel(
            text = text,
            first = first,
            second = second,
            angleDegrees = readableAngleDegrees(first, second),
            color = color,
        )
    }

    private fun firstEndpointPrecedesSecond(
        firstSystemId: Int,
        secondSystemId: Int,
        first: Offset,
        second: Offset,
    ): Boolean = when {
        first.x < second.x - CLOSE_COORDINATE_EPSILON_PX -> true
        second.x < first.x - CLOSE_COORDINATE_EPSILON_PX -> false
        first.y < second.y - CLOSE_COORDINATE_EPSILON_PX -> true
        second.y < first.y - CLOSE_COORDINATE_EPSILON_PX -> false
        else -> firstSystemId < secondSystemId
    }

    private fun readableAngleDegrees(first: Offset, second: Offset): Float {
        var angle = Math.toDegrees(
            atan2((second.y - first.y).toDouble(), (second.x - first.x).toDouble()),
        ).toFloat()
        if (angle > 90f) angle -= 180f
        if (angle < -90f) angle += 180f
        return angle
    }
}

/** Converts measured label width into a real gap in the already-projected Ansiblex curve. */
internal object AnsiblexZoneLabelLayout {
    private const val HORIZONTAL_PADDING_PX = 3f
    private const val MAX_HALF_GAP_PARAMETER = 0.49f
    private const val ZERO_LENGTH_EPSILON_PX = 0.001f

    fun build(
        label: AnsiblexZoneLabel,
        control: Offset,
        measuredLabelWidthPx: Float,
    ): AnsiblexZoneLabelLayoutResult {
        require(measuredLabelWidthPx >= 0f) { "Measured label width must not be negative" }
        val chordLength = hypot(label.second.x - label.first.x, label.second.y - label.first.y)
        val halfGapPx = measuredLabelWidthPx / 2f + HORIZONTAL_PADDING_PX
        val halfGapT = if (chordLength <= ZERO_LENGTH_EPSILON_PX) {
            MAX_HALF_GAP_PARAMETER
        } else {
            (halfGapPx / chordLength).coerceIn(0f, MAX_HALF_GAP_PARAMETER)
        }
        val gapStartT = 0.5f - halfGapT
        val gapEndT = 0.5f + halfGapT
        val gapStart = quadraticPoint(label.first, control, label.second, gapStartT)
        val gapEnd = quadraticPoint(label.first, control, label.second, gapEndT)
        return AnsiblexZoneLabelLayoutResult(
            center = quadraticPoint(label.first, control, label.second, 0.5f),
            gapStart = gapStart,
            gapEnd = gapEnd,
            gapStartT = gapStartT,
            gapEndT = gapEndT,
            firstSegment = AnsiblexZoneLabelCurveSegment(
                start = label.first,
                control = lerp(label.first, control, gapStartT),
                end = gapStart,
            ),
            secondSegment = AnsiblexZoneLabelCurveSegment(
                start = gapEnd,
                control = lerp(control, label.second, gapEndT),
                end = label.second,
            ),
        )
    }

    private fun quadraticPoint(start: Offset, control: Offset, end: Offset, t: Float): Offset {
        val oneMinusT = 1f - t
        return Offset(
            x = oneMinusT * oneMinusT * start.x + 2f * oneMinusT * t * control.x + t * t * end.x,
            y = oneMinusT * oneMinusT * start.y + 2f * oneMinusT * t * control.y + t * t * end.y,
        )
    }

    private fun lerp(start: Offset, end: Offset, t: Float) = Offset(
        x = start.x + (end.x - start.x) * t,
        y = start.y + (end.y - start.y) * t,
    )
}

/** Lightweight high-contrast treatment shared by 2D and Real 3D. */
internal fun ansiblexZoneLabelTextStyle(
    preferences: MapDisplayPreferences,
    color: Color,
): TextStyle = TextStyle(
    color = color,
    fontSize = preferences.ansiblexZoneLabelFontSizeSp.sp,
    fontWeight = FontWeight.Bold,
    shadow = Shadow(
        color = Color.Black.copy(alpha = (color.alpha * 0.6f).coerceIn(0f, 0.6f)),
        offset = Offset(0.5f, 0.5f),
        blurRadius = 1.25f,
    ),
)

internal fun DrawScope.drawAnsiblexConnectionWithZoneLabel(
    geometry: QuadraticMapConnectionGeometry,
    connection: AnsiblexConnection,
    zones: ForceProjectionZoneSnapshot,
    textMeasurer: TextMeasurer,
    preferences: MapDisplayPreferences,
    color: Color,
    strokeWidth: Float,
    pathEffect: PathEffect,
    scratchPath: Path,
) {
    val label = AnsiblexZoneLabelPresentation.build(
        connection = connection,
        first = Offset(geometry.start.x.toFloat(), geometry.start.y.toFloat()),
        second = Offset(geometry.end.x.toFloat(), geometry.end.y.toFloat()),
        zones = zones,
        color = color,
    )
    val textLayout = textMeasurer.measure(
        text = label.text,
        style = ansiblexZoneLabelTextStyle(preferences, label.color),
        softWrap = false,
    )
    val layout = AnsiblexZoneLabelLayout.build(
        label = label,
        control = Offset(geometry.control.x.toFloat(), geometry.control.y.toFloat()),
        measuredLabelWidthPx = textLayout.size.width.toFloat(),
    )
    drawAnsiblexCurveSegment(layout.firstSegment, label.color, strokeWidth, pathEffect, scratchPath)
    drawAnsiblexCurveSegment(layout.secondSegment, label.color, strokeWidth, pathEffect, scratchPath)
    drawInlineAnsiblexZoneLabel(textLayout, layout.center, label.angleDegrees)
}

private fun DrawScope.drawAnsiblexCurveSegment(
    segment: AnsiblexZoneLabelCurveSegment,
    color: Color,
    strokeWidth: Float,
    pathEffect: PathEffect,
    path: Path,
) {
    path.reset()
    path.moveTo(segment.start.x, segment.start.y)
    path.quadraticTo(segment.control.x, segment.control.y, segment.end.x, segment.end.y)
    drawPath(path, color, style = Stroke(width = strokeWidth, pathEffect = pathEffect))
}

private fun DrawScope.drawInlineAnsiblexZoneLabel(
    textLayout: TextLayoutResult,
    center: Offset,
    angleDegrees: Float,
) {
    rotate(degrees = angleDegrees, pivot = center) {
        drawText(
            textLayoutResult = textLayout,
            topLeft = Offset(
                center.x - textLayout.size.width / 2f,
                center.y - textLayout.size.height / 2f,
            ),
        )
    }
}
