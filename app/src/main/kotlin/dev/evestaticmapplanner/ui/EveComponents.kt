package dev.evestaticmapplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun EvePanel(
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    bordered: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = if (secondary) EveColors.SecondarySurface else EveColors.PrimarySurface,
        contentColor = EveColors.PrimaryText,
        shape = EveShapes.small,
        border = if (bordered) BorderStroke(EveDimensions.BorderWidth, EveColors.Border) else null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
fun EveWindowSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = EveColors.PrimarySurface,
        contentColor = EveColors.PrimaryText,
        shape = EveShapes.small,
        border = BorderStroke(EveDimensions.BorderWidth, EveColors.FloatingBorder),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        content = content,
    )
}

@Composable
fun EveDivider(
    modifier: Modifier = Modifier,
    color: Color = EveColors.Divider,
) {
    HorizontalDivider(modifier = modifier, thickness = EveDimensions.BorderWidth, color = color)
}

@Composable
fun EveTab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .background(
                when {
                    selected -> EveColors.SelectedSurface
                    hovered -> EveColors.HoverSurface
                    else -> EveColors.SecondarySurface
                },
                EveShapes.small,
            )
            .hoverable(interactionSource, enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .drawBehind {
                if (selected) {
                    val lineY = size.height - 1.dp.toPx()
                    drawLine(
                        color = EveColors.PrimaryAccent,
                        start = Offset(0f, lineY),
                        end = Offset(size.width, lineY),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Vertical scroll container with the shared 5 px, low-contrast scrollbar. */
@Composable
fun EveVerticalScrollColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(end = EveDimensions.ScrollbarThickness + 3.dp)
                .verticalScroll(scrollState),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).width(EveDimensions.ScrollbarThickness)
                .fillMaxHeight().padding(vertical = 2.dp),
        )
    }
}

/** Lazy list variant with the same always-visible desktop scrollbar. */
@Composable
fun EveLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            modifier = Modifier.fillMaxSize().padding(end = EveDimensions.ScrollbarThickness + 3.dp),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier.align(Alignment.CenterEnd).width(EveDimensions.ScrollbarThickness)
                .fillMaxHeight().padding(vertical = 2.dp),
        )
    }
}
