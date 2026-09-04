package dev.evestaticmapplanner.marker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.search.SearchSuggestionsPresentation
import dev.evestaticmapplanner.search.SystemSearchField
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDropdownMenu as DropdownMenu
import dev.evestaticmapplanner.ui.EveDropdownMenuItem as DropdownMenuItem
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EvePanel
import dev.evestaticmapplanner.ui.EveTextButton as TextButton

enum class MarkerEditorMode {
    CREATE_SAVED,
    EDIT_TEMPORARY,
    EDIT_SAVED,
}

data class MarkerEditorRequest(
    val mode: MarkerEditorMode,
    val systemId: Int?,
    val systemName: String?,
    val marker: Marker? = null,
)

data class MarkerEditorSystemSearch(
    val query: String,
    val results: List<SolarSystem>,
    val selectedSystem: SolarSystem?,
)

@Composable
fun MarkerEditorDialog(
    request: MarkerEditorRequest,
    isBusy: Boolean,
    error: String?,
    saveEnabled: Boolean = true,
    systemSearch: MarkerEditorSystemSearch? = null,
    onSystemQueryChange: (String) -> Unit = {},
    onSystemSelected: (SolarSystem) -> Unit = {},
    children: List<SavedMarkerChild> = emptyList(),
    onAddChild: (SavedMarkerChildType) -> Unit = {},
    onRemoveChild: (String) -> Unit = {},
    onSave: (Int, MarkerDraft, List<SavedMarkerChildType>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(request) { mutableStateOf(request.marker?.name.orEmpty()) }
    var notes by remember(request) { mutableStateOf(request.marker?.notes.orEmpty()) }
    var color by remember(request) { mutableStateOf(request.marker?.color ?: MarkerColor.YELLOW) }
    var initialTags by remember(request) { mutableStateOf(emptyList<SavedMarkerChildType>()) }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text(if (request.mode == MarkerEditorMode.CREATE_SAVED) "Add Saved Marker" else "Edit Marker") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = markerEditorContentMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (request.marker?.createdBy == SavedMarkerCreatedBy.AI) {
                    SavedMarkerProvenanceBadge(SavedMarkerCreatedBy.AI)
                }
                if (request.systemId != null) {
                    OutlinedTextField(
                        value = request.systemName.orEmpty(),
                        onValueChange = {},
                        label = { Text("Solar System") },
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (systemSearch != null) {
                    SystemSearchField(
                        value = systemSearch.query,
                        label = "Search system",
                        results = systemSearch.results,
                        onValueChange = onSystemQueryChange,
                        onSelect = onSystemSelected,
                        modifier = Modifier.fillMaxWidth(),
                        suggestionsPresentation = SearchSuggestionsPresentation.DROPDOWN,
                    )
                    systemSearch.selectedSystem?.let { selected ->
                        Text("Selected: ${selected.name} · ${selected.id}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                MarkerColorPalette(color, onSelected = { color = it })
                if (request.mode == MarkerEditorMode.CREATE_SAVED || request.mode == MarkerEditorMode.EDIT_SAVED) {
                    val assignedTypes = if (request.mode == MarkerEditorMode.CREATE_SAVED) {
                        initialTags
                    } else {
                        children.map { it.type }
                    }
                    SavedMarkerTagsEditor(
                        types = assignedTypes,
                        enabled = !isBusy,
                        onAddChild = { type ->
                            if (request.mode == MarkerEditorMode.CREATE_SAVED) {
                                initialTags = SavedMarkerChildType.normalizeSupported(initialTags + type)
                            } else {
                                onAddChild(type)
                            }
                        },
                        onRemoveChild = { type ->
                            if (request.mode == MarkerEditorMode.CREATE_SAVED) {
                                initialTags = initialTags.filterNot { it == type }
                            } else {
                                children.firstOrNull { it.type == type }?.let { onRemoveChild(it.id) }
                            }
                        },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = saveEnabled && !isBusy && markerEditorSystemId(request, systemSearch) != null,
                onClick = {
                    val systemId = markerEditorSystemId(request, systemSearch) ?: return@TextButton
                    onSave(
                        systemId,
                        MarkerDraft.create(name, notes, color),
                        if (request.mode == MarkerEditorMode.CREATE_SAVED) initialTags else emptyList(),
                    )
                },
            ) { Text(if (isBusy) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(enabled = !isBusy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private val markerEditorContentMaxHeight = 460.dp

@Composable
private fun SavedMarkerTagsEditor(
    types: List<SavedMarkerChildType>,
    enabled: Boolean,
    onAddChild: (SavedMarkerChildType) -> Unit,
    onRemoveChild: (SavedMarkerChildType) -> Unit,
) {
    var addMenuExpanded by remember { mutableStateOf(false) }
    val available = remember(types) { SavedMarkerChildVisuals.availableForTypes(types) }
    Text("Tags", style = MaterialTheme.typography.labelLarge)
    if (types.isEmpty()) {
        Text("No tags assigned.", style = MaterialTheme.typography.bodySmall, color = EveColors.SecondaryText)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            types.forEach { type ->
                val visual = SavedMarkerChildVisuals.resolve(type)
                EvePanel(
                    secondary = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 9.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                    ) {
                        SavedMarkerChildIcon(visual, Modifier.size(20.dp))
                        Text(visual.label, modifier = Modifier.weight(1f))
                        TextButton(enabled = enabled, onClick = { onRemoveChild(type) }) { Text("×") }
                    }
                }
            }
        }
    }
    Box {
        TextButton(
            enabled = enabled && available.isNotEmpty(),
            onClick = { addMenuExpanded = true },
        ) { Text(if (available.isEmpty()) "All tags assigned" else "+ Add Tag") }
        DropdownMenu(
            expanded = addMenuExpanded,
            onDismissRequest = { addMenuExpanded = false },
        ) {
            available.forEach { visual ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            SavedMarkerChildIcon(visual, Modifier.size(20.dp))
                            Text(visual.label)
                        }
                    },
                    onClick = {
                        addMenuExpanded = false
                        onAddChild(checkNotNull(visual.type))
                    },
                )
            }
        }
    }
}

internal fun markerEditorSystemId(
    request: MarkerEditorRequest,
    systemSearch: MarkerEditorSystemSearch?,
): Int? = request.systemId ?: systemSearch?.selectedSystem?.id

@Composable
private fun MarkerColorPalette(selected: MarkerColor, onSelected: (MarkerColor) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        MarkerColor.entries.forEach { option ->
            Surface(
                shape = CircleShape,
                color = markerColor(option),
                border = if (option == selected) BorderStroke(3.dp, Color.White) else null,
                modifier = Modifier.size(30.dp).clickable { onSelected(option) },
            ) { Box(Modifier.padding(2.dp)) }
        }
    }
}
