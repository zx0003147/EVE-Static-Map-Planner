package dev.evestaticmapplanner.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.model.SolarSystem

enum class SearchSuggestionsPresentation {
    INLINE,
    DROPDOWN,
}

@Composable
fun SystemSearchField(
    value: String,
    label: String,
    results: List<SolarSystem>,
    onValueChange: (String) -> Unit,
    onSelect: (SolarSystem) -> Unit,
    modifier: Modifier = Modifier,
    suggestionsPresentation: SearchSuggestionsPresentation = SearchSuggestionsPresentation.INLINE,
) {
    if (suggestionsPresentation == SearchSuggestionsPresentation.DROPDOWN) {
        DropdownSystemSearchField(value, label, results, onValueChange, onSelect, modifier)
    } else {
        InlineSystemSearchField(value, label, results, onValueChange, onSelect, modifier)
    }
}

@Composable
private fun InlineSystemSearchField(
    value: String,
    label: String,
    results: List<SolarSystem>,
    onValueChange: (String) -> Unit,
    onSelect: (SolarSystem) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        results.take(6).forEach { system ->
            TextButton(onClick = { onSelect(system) }, modifier = Modifier.fillMaxWidth()) {
                Text("${system.name}  ·  ${system.id}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DropdownSystemSearchField(
    value: String,
    label: String,
    results: List<SolarSystem>,
    onValueChange: (String) -> Unit,
    onSelect: (SolarSystem) -> Unit,
    modifier: Modifier,
) {
    var dismissed by remember(value) { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = results.isNotEmpty() && !dismissed,
            onDismissRequest = { dismissed = true },
            modifier = Modifier.widthIn(min = SEARCH_DROPDOWN_MIN_WIDTH, max = SEARCH_DROPDOWN_MAX_WIDTH),
        ) {
            results.take(6).forEach { system ->
                DropdownMenuItem(
                    text = { Text("${system.name}  ·  ${system.id}", style = MaterialTheme.typography.bodySmall) },
                    onClick = { onSelect(system) },
                )
            }
        }
    }
}

internal val SEARCH_DROPDOWN_MIN_WIDTH = 260.dp
internal val SEARCH_DROPDOWN_MAX_WIDTH = 360.dp
