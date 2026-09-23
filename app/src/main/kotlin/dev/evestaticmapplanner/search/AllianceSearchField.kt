package dev.evestaticmapplanner.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.evestaticmapplanner.core.alliance.AllianceDirectorySnapshot
import dev.evestaticmapplanner.core.alliance.AllianceReference
import dev.evestaticmapplanner.core.alliance.AllianceSearchIndex
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton

@Composable
internal fun AllianceSearchField(
    snapshot: AllianceDirectorySnapshot,
    label: String,
    onSelect: (AllianceReference) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val index = remember(snapshot) { AllianceSearchIndex(snapshot) }
    val results = remember(index, query) { index.search(query, ALLIANCE_SEARCH_RESULT_LIMIT) }
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(ALLIANCE_SEARCH_FIELD_TAG),
        )
        results.forEach { result ->
            TextButton(
                onClick = {
                    query = result.alliance.displayLabel()
                    onSelect(result.alliance)
                },
                modifier = Modifier.fillMaxWidth().testTag("$ALLIANCE_SEARCH_RESULT_TAG_PREFIX-${result.alliance.allianceId}"),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(result.alliance.displayLabel(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Alliance ID ${result.alliance.allianceId}",
                        color = EveColors.SecondaryText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

internal fun AllianceReference.displayLabel(): String = listOfNotNull(
    ticker?.let { "[$it]" },
    name,
).joinToString(" ").ifEmpty { "Alliance #$allianceId" }

internal const val ALLIANCE_SEARCH_FIELD_TAG = "alliance-search-field"
internal const val ALLIANCE_SEARCH_RESULT_TAG_PREFIX = "alliance-search-result"
private const val ALLIANCE_SEARCH_RESULT_LIMIT = 8
