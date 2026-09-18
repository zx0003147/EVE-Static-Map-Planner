package dev.evestaticmapplanner.shared

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.map.sharedMarkerColor
import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMarkerDraft
import dev.evestaticmapplanner.shared.model.SharedMarkerValidation
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppStringsCatalog
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.localization.SharedMapStrings
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton

internal enum class SharedMarkerEditorMode { CREATE, EDIT, VIEW }

internal data class SharedMarkerEditorRequest(
    val workspaceId: String,
    val mode: SharedMarkerEditorMode,
    val systemId: Int,
    val systemName: String,
    val marker: SharedMarker? = null,
)

@Composable
internal fun SharedMarkerEditorDialog(
    request: SharedMarkerEditorRequest,
    currentMarker: SharedMarker?,
    canWrite: Boolean,
    mutation: SharedMarkerMutationUiState,
    onCreate: (Int, SharedMarkerDraft) -> Long?,
    onUpdate: (String, Long, SharedMarkerDraft) -> Long?,
    onDelete: (String, Long) -> Long?,
    onClearFeedback: () -> Unit,
    onDismiss: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.sharedMap
    val initial = request.marker
    var name by remember(request) { mutableStateOf(initial?.name.orEmpty()) }
    var notes by remember(request) { mutableStateOf(initial?.notes.orEmpty()) }
    var color by remember(request) { mutableStateOf(initial?.color ?: SharedMarkerColor.YELLOW) }
    var tagText by remember(request) { mutableStateOf(initial?.tags?.joinToString(", ").orEmpty()) }
    var expectedVersion by remember(request) { mutableStateOf(initial?.version) }
    var localError by remember(request) { mutableStateOf<SharedMapError?>(null) }
    var pendingOperationId by remember(request) { mutableStateOf<Long?>(null) }
    var confirmDelete by remember(request) { mutableStateOf(false) }
    var confirmReload by remember(request) { mutableStateOf(false) }
    val remoteDeleted = request.mode != SharedMarkerEditorMode.CREATE && currentMarker == null
    val operationMatches = pendingOperationId != null && mutation.operationId == pendingOperationId
    val operationError = mutation.error.takeIf { operationMatches }
    val conflict = operationError as? SharedMapError.MarkerVersionConflict
    val busy = mutation.busy && operationMatches

    LaunchedEffect(mutation.completion, pendingOperationId) {
        if (pendingOperationId != null && mutation.completion?.operationId == pendingOperationId) onDismiss()
    }

    fun draftOrNull(): SharedMarkerDraft? = try {
        SharedMarkerValidation.normalize(
            SharedMarkerDraft(name, color, parseSharedMarkerTags(tagText), notes),
        ).also { localError = null }
    } catch (error: IllegalArgumentException) {
        localError = SharedMapError.InvalidArgument(error.message ?: "One or more fields are invalid.")
        null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when (request.mode) {
                    SharedMarkerEditorMode.CREATE -> strings.addSharedMarker
                    SharedMarkerEditorMode.EDIT -> strings.editSharedMarker
                    SharedMarkerEditorMode.VIEW -> strings.sharedMarker
                },
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = request.systemName,
                    onValueChange = {},
                    label = { Text(strings.solarSystem) },
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.codePointCount(0, it.length) <= 80) name = it },
                    label = { Text(strings.name) },
                    supportingText = { Text("${name.codePointCount(0, name.length)} / 80") },
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(strings.color, style = MaterialTheme.typography.labelLarge)
                SharedMarkerColorPalette(
                    selected = color,
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
                    onSelected = { color = it },
                )
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text(strings.tags) },
                    supportingText = { Text(strings.tagsHelper) },
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CommonSharedMarkerTags(
                    current = parseSharedMarkerTags(tagText),
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
                    onToggle = { tag ->
                        val tags = parseSharedMarkerTags(tagText).toMutableList()
                        if (!tags.remove(tag)) tags += tag
                        tagText = tags.distinct().take(SharedMarkerValidation.MAX_TAGS).joinToString(", ")
                    },
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { if (it.codePointCount(0, it.length) <= 2_000) notes = it },
                    label = { Text(strings.notes) },
                    supportingText = { Text("${notes.codePointCount(0, notes.length)} / 2000") },
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!canWrite && request.mode != SharedMarkerEditorMode.VIEW) {
                    Text(strings.temporarilyUnavailable, color = EveColors.Warning)
                }
                if (remoteDeleted) {
                    Text(strings.markerMissing, color = MaterialTheme.colorScheme.error)
                }
                if (conflict != null) {
                    Text(strings.markerChanged, color = MaterialTheme.colorScheme.error)
                    Text(strings.reloadBeforeEditing, color = EveColors.SecondaryText)
                } else {
                    (localError ?: operationError)?.let {
                        Text(strings.error(it), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when {
                conflict != null -> TextButton(onClick = { confirmReload = true }) { Text(strings.reloadLatest) }
                request.mode == SharedMarkerEditorMode.VIEW || remoteDeleted || !canWrite ->
                    TextButton(onClick = onDismiss) { Text(appStrings.common.close) }
                else -> TextButton(
                    enabled = !busy,
                    onClick = {
                        val draft = draftOrNull() ?: return@TextButton
                        onClearFeedback()
                        pendingOperationId = when (request.mode) {
                            SharedMarkerEditorMode.CREATE -> onCreate(request.systemId, draft)
                            SharedMarkerEditorMode.EDIT -> onUpdate(
                                checkNotNull(request.marker?.markerId),
                                checkNotNull(expectedVersion),
                                draft,
                            )
                            SharedMarkerEditorMode.VIEW -> null
                        }
                    },
                ) {
                    Text(
                        if (busy && mutation.kind == SharedMarkerMutationKind.DELETE) {
                            strings.deleting
                        } else if (busy) strings.saving else if (request.mode == SharedMarkerEditorMode.CREATE) {
                            strings.saveSharedMarker
                        } else {
                            strings.saveChanges
                        },
                    )
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (request.mode == SharedMarkerEditorMode.EDIT && canWrite && !remoteDeleted && conflict == null) {
                    TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text(strings.deleteSharedMarker) }
                }
                if (request.mode != SharedMarkerEditorMode.VIEW && !remoteDeleted) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text(appStrings.common.cancel) }
                }
            }
        },
    )

    if (confirmDelete && request.marker != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false },
            title = { Text(strings.deleteSharedMarkerTitle) },
            text = { Text(strings.deleteSharedMarker(request.marker.name, request.systemName)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        onClearFeedback()
                        pendingOperationId = onDelete(request.marker.markerId, checkNotNull(expectedVersion))
                        confirmDelete = false
                    },
                ) { Text(if (busy) strings.deleting else appStrings.common.delete) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false }) { Text(appStrings.common.cancel) } },
        )
    }

    if (confirmReload) {
        val latest = conflict?.currentMarker ?: currentMarker
        AlertDialog(
            onDismissRequest = { confirmReload = false },
            title = { Text(strings.reloadLatestTitle) },
            text = { Text(strings.discardUnsavedChanges) },
            confirmButton = {
                TextButton(
                    enabled = latest != null,
                    onClick = {
                        val marker = latest ?: return@TextButton
                        name = marker.name
                        notes = marker.notes.orEmpty()
                        color = marker.color
                        tagText = marker.tags.joinToString(", ")
                        expectedVersion = marker.version
                        pendingOperationId = null
                        onClearFeedback()
                        confirmReload = false
                    },
                ) { Text(strings.reloadLatest) }
            },
            dismissButton = { TextButton(onClick = { confirmReload = false }) { Text(appStrings.common.cancel) } },
        )
    }
}

internal fun parseSharedMarkerTags(value: String): List<String> = value
    .split(Regex("[,\\s]+"))
    .map(String::trim)
    .filter(String::isNotEmpty)

internal fun sharedMapErrorMessage(
    error: SharedMapError,
    strings: SharedMapStrings = AppStringsCatalog.forLocale(AppLocale.EN_US).sharedMap,
): String = strings.error(error)

@Composable
private fun SharedMarkerColorPalette(
    selected: SharedMarkerColor,
    enabled: Boolean,
    onSelected: (SharedMarkerColor) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SharedMarkerColor.entries.forEach { option ->
            Surface(
                shape = CircleShape,
                color = sharedMarkerColor(option),
                border = if (option == selected) BorderStroke(3.dp, Color.White) else null,
                modifier = Modifier.size(30.dp).clickable(enabled = enabled) { onSelected(option) },
            ) { Box(Modifier.padding(2.dp)) }
        }
    }
}

@Composable
private fun CommonSharedMarkerTags(current: List<String>, enabled: Boolean, onToggle: (String) -> Unit) {
    Text(LocalAppStrings.current.sharedMap.commonTags, style = MaterialTheme.typography.labelMedium, color = EveColors.SecondaryText)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        COMMON_SHARED_MARKER_TAGS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { tag ->
                    TextButton(enabled = enabled, selected = tag in current, onClick = { onToggle(tag) }) {
                        Text(if (tag in current) "✓ $tag" else tag)
                    }
                }
            }
        }
    }
}

private val COMMON_SHARED_MARKER_TAGS = listOf(
    "staging", "rally", "danger", "logistics", "home", "backup", "industrial", "strategic", "fortizar", "keepstar",
)
