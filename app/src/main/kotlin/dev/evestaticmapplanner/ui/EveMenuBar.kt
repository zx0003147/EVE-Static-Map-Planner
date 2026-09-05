package dev.evestaticmapplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

data class EveMenuItemSpec(
    val label: String,
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
    val onClick: () -> Unit,
)

data class EveMenuSpec(
    val label: String,
    val items: List<EveMenuItemSpec>,
)

@Composable
fun EveTopMenuBar(menus: List<EveMenuSpec>, modifier: Modifier = Modifier) {
    var expandedMenu by remember { mutableStateOf<String?>(null) }
    Row(
        modifier = modifier.fillMaxWidth().height(EveDimensions.MenuBarHeight)
            .background(EveColors.PrimarySurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        menus.forEach { menu ->
            val interactionSource = remember(menu.label) { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            Box {
                Box(
                    modifier = Modifier.height(EveDimensions.MenuBarHeight)
                        .background(
                            if (hovered || expandedMenu == menu.label) EveColors.HoverSurface else EveColors.PrimarySurface,
                        )
                        .hoverable(interactionSource)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = {
                                expandedMenu = if (expandedMenu == menu.label) null else menu.label
                            },
                        )
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        menu.label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = if (expandedMenu == menu.label) EveColors.PrimaryAccent else EveColors.PrimaryText,
                    )
                }
                EveDropdownMenu(
                    expanded = expandedMenu == menu.label,
                    onDismissRequest = { expandedMenu = null },
                ) {
                    menu.items.forEach { item ->
                        if (item.separatorBefore) EveDivider()
                        EveDropdownMenuItem(
                            text = { Text(item.label) },
                            enabled = item.enabled,
                            onClick = {
                                expandedMenu = null
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
    EveDivider(color = TOP_MENU_DIVIDER_COLOR)
}

internal val TOP_MENU_DIVIDER_COLOR = EveColors.Border
