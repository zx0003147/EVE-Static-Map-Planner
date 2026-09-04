package dev.evestaticmapplanner.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveDropdownMenu as DropdownMenu
import dev.evestaticmapplanner.ui.EveDropdownMenuItem as DropdownMenuItem
import dev.evestaticmapplanner.ui.EveDimensions
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveOutlinedTextFieldColors
import dev.evestaticmapplanner.ui.EveShapes
import dev.evestaticmapplanner.ui.EveTextButton as TextButton

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
    var editingValue by remember { mutableStateOf(initialSearchFieldValue(value)) }
    val synchronizedEditingValue = synchronizeSearchFieldValue(editingValue, value)
    SideEffect {
        if (editingValue != synchronizedEditingValue) editingValue = synchronizedEditingValue
    }
    val onEditingValueChange: (TextFieldValue) -> Unit = { updatedValue ->
        editingValue = updatedValue
        if (updatedValue.text != value) onValueChange(updatedValue.text)
    }

    if (suggestionsPresentation == SearchSuggestionsPresentation.DROPDOWN) {
        DropdownSystemSearchField(
            synchronizedEditingValue,
            label,
            results,
            onEditingValueChange,
            onSelect,
            modifier,
            compact,
        )
    } else {
        InlineSystemSearchField(
            synchronizedEditingValue,
            label,
            results,
            onEditingValueChange,
            onSelect,
            modifier,
            compact,
        )
    }
}

@Composable
private fun InlineSystemSearchField(
    value: TextFieldValue,
    label: String,
    results: List<SolarSystem>,
    onValueChange: (TextFieldValue) -> Unit,
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
    value: TextFieldValue,
    label: String,
    results: List<SolarSystem>,
    onValueChange: (TextFieldValue) -> Unit,
    onSelect: (SolarSystem) -> Unit,
    modifier: Modifier,
    compact: Boolean,
) {
    var dismissed by remember { mutableStateOf(false) }
    val expanded = results.isNotEmpty() && !dismissed
    Box(modifier) {
        SearchTextField(
            value = value,
            onValueChange = { updatedValue ->
                if (updatedValue.text != value.text) dismissed = false
                onValueChange(updatedValue)
            },
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
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
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

    CompactOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier.testTag(COMPACT_SEARCH_FIELD_TEST_TAG),
        labelModifier = Modifier.testTag(COMPACT_SEARCH_PLACEHOLDER_TEST_TAG),
    )
}

@Composable
internal fun CompactOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        modifier = modifier.defaultMinSize(minHeight = COMPACT_SEARCH_FIELD_MIN_HEIGHT)
            .background(EveColors.InputSurface, EveShapes.small),
        decorationBox = { innerTextField ->
            CompactOutlinedDecoration(value, label, interactionSource, Modifier, innerTextField)
        },
    )
}

@Composable
private fun CompactOutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier,
    labelModifier: Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        modifier = modifier.defaultMinSize(minHeight = COMPACT_SEARCH_FIELD_MIN_HEIGHT)
            .background(EveColors.InputSurface, EveShapes.small),
        decorationBox = { innerTextField ->
            CompactOutlinedDecoration(value.text, label, interactionSource, labelModifier, innerTextField)
        },
    )
}

@Composable
private fun CompactOutlinedDecoration(
    value: String,
    label: String,
    interactionSource: MutableInteractionSource,
    labelModifier: Modifier,
    innerTextField: @Composable () -> Unit,
) {
    OutlinedTextFieldDefaults.DecorationBox(
        value = value,
        innerTextField = innerTextField,
        enabled = true,
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        interactionSource = interactionSource,
        label = { Text(label, modifier = labelModifier) },
        colors = EveOutlinedTextFieldColors(),
        contentPadding = PaddingValues(
            horizontal = EveDimensions.InputHorizontalPadding,
            vertical = EveDimensions.InputVerticalPadding,
        ),
    )
}

internal val SEARCH_DROPDOWN_MIN_WIDTH = 260.dp
internal val SEARCH_DROPDOWN_MAX_WIDTH = 360.dp
internal val COMPACT_SEARCH_FIELD_MIN_HEIGHT = EveDimensions.InputMinimumHeight
internal const val COMPACT_SEARCH_FIELD_TEST_TAG = "compact-search-field"
internal const val COMPACT_SEARCH_PLACEHOLDER_TEST_TAG = "compact-search-placeholder"
internal val SYSTEM_SEARCH_POPUP_PROPERTIES = PopupProperties(focusable = false)

internal fun initialSearchFieldValue(text: String): TextFieldValue = TextFieldValue(
    text = text,
    selection = TextRange(text.length),
)

internal fun synchronizeSearchFieldValue(current: TextFieldValue, externalText: String): TextFieldValue =
    if (current.text == externalText) current else initialSearchFieldValue(externalText)
