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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val initial = request.marker
    var name by remember(request) { mutableStateOf(initial?.name.orEmpty()) }
    var notes by remember(request) { mutableStateOf(initial?.notes.orEmpty()) }
    var color by remember(request) { mutableStateOf(initial?.color ?: SharedMarkerColor.YELLOW) }
    var tagText by remember(request) { mutableStateOf(initial?.tags?.joinToString(", ").orEmpty()) }
    var expectedVersion by remember(request) { mutableStateOf(initial?.version) }
    var localError by remember(request) { mutableStateOf<String?>(null) }
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
        localError = error.message ?: "One or more fields are invalid."
        null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when (request.mode) {
                    SharedMarkerEditorMode.CREATE -> "Add Shared Marker"
                    SharedMarkerEditorMode.EDIT -> "Edit Shared Marker"
                    SharedMarkerEditorMode.VIEW -> "Shared Marker"
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
                    label = { Text("Solar System") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.codePointCount(0, it.length) <= 80) name = it },
                    label = { Text("Name") },
                    supportingText = { Text("${name.codePointCount(0, name.length)} / 80") },
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                SharedMarkerColorPalette(
                    selected = color,
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
                    onSelected = { color = it },
                )
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text("Tags") },
                    supportingText = { Text("Comma or space separated; up to 9 lowercase tags") },
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
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
                    label = { Text("Notes") },
                    supportingText = { Text("${notes.codePointCount(0, notes.length)} / 2000") },
                    enabled = canWrite && !remoteDeleted && request.mode != SharedMarkerEditorMode.VIEW && !busy,
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!canWrite && request.mode != SharedMarkerEditorMode.VIEW) {
                    Text("Shared Map is temporarily unavailable or your role is read-only.", color = Color(0xFFFFB86B))
                }
                if (remoteDeleted) {
                    Text("This Shared Marker no longer exists.", color = MaterialTheme.colorScheme.error)
                }
                if (conflict != null) {
                    Text("This Shared Marker was changed by another user.", color = MaterialTheme.colorScheme.error)
                    Text("Reload the latest server version before editing again.", color = Color(0xFFAAB9C7))
                } else {
                    (localError ?: operationError?.let(::sharedMapErrorMessage))?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when {
                conflict != null -> TextButton(onClick = { confirmReload = true }) { Text("Reload Latest") }
                request.mode == SharedMarkerEditorMode.VIEW || remoteDeleted || !canWrite ->
                    TextButton(onClick = onDismiss) { Text("Close") }
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
                            "Deleting…"
                        } else if (busy) "Saving…" else if (request.mode == SharedMarkerEditorMode.CREATE) {
                            "Save Shared Marker"
                        } else {
                            "Save Changes"
                        },
                    )
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (request.mode == SharedMarkerEditorMode.EDIT && canWrite && !remoteDeleted && conflict == null) {
                    TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("Delete Shared Marker") }
                }
                if (request.mode != SharedMarkerEditorMode.VIEW && !remoteDeleted) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )

    if (confirmDelete && request.marker != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false },
            title = { Text("Delete Shared Marker?") },
            text = { Text("Delete shared marker “${request.marker.name}” from ${request.systemName}?") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        onClearFeedback()
                        pendingOperationId = onDelete(request.marker.markerId, checkNotNull(expectedVersion))
                        confirmDelete = false
                    },
                ) { Text(if (busy) "Deleting…" else "Delete") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (confirmReload) {
        val latest = conflict?.currentMarker ?: currentMarker
        AlertDialog(
            onDismissRequest = { confirmReload = false },
            title = { Text("Reload latest Shared Marker?") },
            text = { Text("Your unsaved changes will be discarded.") },
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
                ) { Text("Reload Latest") }
            },
            dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("Cancel") } },
        )
    }
}

internal fun parseSharedMarkerTags(value: String): List<String> = value
    .split(Regex("[,\\s]+"))
    .map(String::trim)
    .filter(String::isNotEmpty)

internal fun sharedMapErrorMessage(error: SharedMapError): String = when (error) {
    is SharedMapError.Forbidden -> "You no longer have permission to change Shared Markers."
    is SharedMapError.Authentication -> "Authentication is required before Shared Markers can be changed."
    is SharedMapError.MarkerAlreadyExists -> "This system already has a Shared Marker. Reload the latest snapshot."
    is SharedMapError.MarkerVersionConflict -> "This Shared Marker was changed by another user."
    is SharedMapError.InvalidArgument -> error.message
    is SharedMapError.RateLimited -> "Too many requests. Please try again shortly."
    is SharedMapError.Network -> "The Shared Map server could not be reached. Your changes were not confirmed."
    is SharedMapError.Server -> "The Shared Map server could not complete this operation."
    is SharedMapError.NotFound -> "This Shared Marker no longer exists."
    is SharedMapError.MemberVersionConflict -> "This member was changed by another administrator. Reload members."
    is SharedMapError.LastAdminRequired -> "The Workspace must retain at least one active Admin."
    is SharedMapError.IdempotencyResponseNotReplayable ->
        "The invite was created, but its one-time secret could not be replayed. Revoke it and create another."
    is SharedMapError.Protocol -> error.message
    is SharedMapError.InvalidResponse -> "The Shared Map server returned an invalid response."
    is SharedMapError.InvalidConfiguration -> error.message
}

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
    Text("Common tags", style = MaterialTheme.typography.labelMedium, color = Color(0xFFAAB9C7))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        COMMON_SHARED_MARKER_TAGS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { tag ->
                    TextButton(enabled = enabled, onClick = { onToggle(tag) }) {
                        Text(if (tag in current) "✓ $tag" else tag)
                    }
                }
            }
        }
    }
}

private val COMMON_SHARED_MARKER_TAGS = listOf(
    "staging", "rally", "danger", "logistics", "home", "backup", "industrial", "strategic", "keepstar",
)
