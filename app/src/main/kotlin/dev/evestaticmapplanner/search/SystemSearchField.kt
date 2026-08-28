package dev.evestaticmapplanner.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
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
    compact: Boolean = false,
) {
    if (suggestionsPresentation == SearchSuggestionsPresentation.DROPDOWN) {
        DropdownSystemSearchField(value, label, results, onValueChange, onSelect, modifier, compact)
    } else {
        InlineSystemSearchField(value, label, results, onValueChange, onSelect, modifier, compact)
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
    compact: Boolean,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SearchTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            compact = compact,
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
    compact: Boolean,
) {
    var dismissed by remember(value) { mutableStateOf(false) }
    val expanded = results.isNotEmpty() && !dismissed
    Box(modifier) {
        SearchTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            compact = compact,
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (expanded && event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                        dismissed = true
                        true
                    } else {
                        false
                    }
                },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { dismissed = true },
            modifier = Modifier.widthIn(min = SEARCH_DROPDOWN_MIN_WIDTH, max = SEARCH_DROPDOWN_MAX_WIDTH),
            properties = SYSTEM_SEARCH_POPUP_PROPERTIES,
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

@Composable
private fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    compact: Boolean,
    modifier: Modifier,
) {
    if (!compact) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = modifier,
        )
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        modifier = modifier
            .height(COMPACT_SEARCH_FIELD_HEIGHT)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(COMPACT_SEARCH_FIELD_BORDER_WIDTH, borderColor, shape)
            .testTag(COMPACT_SEARCH_FIELD_TEST_TAG),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = COMPACT_SEARCH_HORIZONTAL_PADDING),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = label,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(COMPACT_SEARCH_PLACEHOLDER_TEST_TAG),
                    )
                }
                innerTextField()
            }
        },
    )
}

internal val SEARCH_DROPDOWN_MIN_WIDTH = 260.dp
internal val SEARCH_DROPDOWN_MAX_WIDTH = 360.dp
internal val COMPACT_SEARCH_FIELD_HEIGHT = 48.dp
internal val COMPACT_SEARCH_HORIZONTAL_PADDING = 12.dp
internal val COMPACT_SEARCH_FIELD_BORDER_WIDTH = 1.dp
internal const val COMPACT_SEARCH_FIELD_TEST_TAG = "compact-search-field"
internal const val COMPACT_SEARCH_PLACEHOLDER_TEST_TAG = "compact-search-placeholder"
internal val SYSTEM_SEARCH_POPUP_PROPERTIES = PopupProperties(focusable = false)
