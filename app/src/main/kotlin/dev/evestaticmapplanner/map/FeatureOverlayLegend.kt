package dev.evestaticmapplanner.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.ui.EvePanel

@Composable
internal fun FeatureOverlayLegend(
    sections: List<FeatureOverlayLegendSection>,
    modifier: Modifier = Modifier,
) {
    if (sections.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val header = if (sections.size == 1) sections.single().title else "Map overlays"
    EvePanel(modifier = modifier, secondary = true) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Text(
                text = "$header ${if (expanded) "▾" else "▸"}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp),
            )
            if (expanded) {
                sections.forEach { section ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 10.dp),
                    ) {
                        if (sections.size > 1) {
                            Text(section.title, style = MaterialTheme.typography.labelMedium)
                        }
                        section.entries.forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(Modifier.size(9.dp).background(entry.color))
                                Text(entry.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
