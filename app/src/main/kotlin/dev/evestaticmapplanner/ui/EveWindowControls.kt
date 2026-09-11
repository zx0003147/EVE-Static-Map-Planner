package dev.evestaticmapplanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun EveAlwaysOnTopButton(
    isAlwaysOnTop: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val description = if (isAlwaysOnTop) "Disable always on top" else "Keep window on top"
    val iconColor = if (isAlwaysOnTop) EveColors.PrimaryAccent else EveColors.PrimaryText
    Box(
        modifier = modifier
            .width(40.dp)
            .fillMaxHeight()
            .background(
                when {
                    isAlwaysOnTop -> EveColors.SelectedSurface
                    hovered -> EveColors.HoverSurface
                    else -> EveColors.PrimarySurface
                },
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onClick = onClick,
            )
            .semantics {
                role = Role.Switch
                contentDescription = description
                stateDescription = if (isAlwaysOnTop) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            val pin = Path().apply {
                moveTo(size.width * 0.30f, size.height * 0.12f)
                lineTo(size.width * 0.70f, size.height * 0.12f)
                lineTo(size.width * 0.64f, size.height * 0.36f)
                lineTo(size.width * 0.79f, size.height * 0.57f)
                lineTo(size.width * 0.79f, size.height * 0.65f)
                lineTo(size.width * 0.21f, size.height * 0.65f)
                lineTo(size.width * 0.21f, size.height * 0.57f)
                lineTo(size.width * 0.36f, size.height * 0.36f)
                close()
            }
            if (isAlwaysOnTop) {
                drawPath(pin, iconColor)
            } else {
                drawPath(pin, iconColor, style = Stroke(width = 1.35.dp.toPx()))
            }
            drawLine(
                color = iconColor,
                start = Offset(size.width * 0.50f, size.height * 0.65f),
                end = Offset(size.width * 0.50f, size.height * 0.94f),
                strokeWidth = 1.35.dp.toPx(),
            )
        }
    }
}
