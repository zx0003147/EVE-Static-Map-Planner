package dev.evestaticmapplanner.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.shared.model.SharedMember
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveLazyColumn
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
internal fun SharedMapMembersDialog(
    workspaceName: String,
    state: SharedAdminUiState,
    canAdmin: Boolean,
    onLoad: () -> Unit,
    onCreateMember: (String, SharedWorkspaceRole) -> Boolean,
    onChangeRole: (String, Long, SharedWorkspaceRole) -> Boolean,
    onRemoveMember: (String, Long) -> Boolean,
    onCreateInvite: (String, Long) -> Boolean,
    onClearError: () -> Unit,
    onClearInvite: () -> Unit,
    onDismiss: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.sharedMap
    var selectedMemberId by remember(state.workspaceId) { mutableStateOf<String?>(null) }
    var showCreateMember by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<SharedMember?>(null) }
    val selected = state.members.singleOrNull { it.memberId == selectedMemberId }

    LaunchedEffect(state.workspaceId, canAdmin) {
        if (canAdmin) onLoad() else onDismiss()
    }
    LaunchedEffect(state.members, selectedMemberId) {
        if (selectedMemberId != null && selected == null) selectedMemberId = null
    }

    AlertDialog(
        onDismissRequest = { if (state.busyMemberId == null) onDismiss() },
        title = { Text(strings.membersTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(workspaceName, color = EveColors.SecondaryText)
                Text(strings.memberCount(state.members.size))
                if (state.loading) Text(strings.loadingMembers, color = EveColors.SecondaryText)
                state.error?.let {
                    Text(strings.error(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onClearError) { Text(strings.dismiss) }
                }
                EveLazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 330.dp)) {
                    items(state.members, key = SharedMember::memberId) { member ->
                        val interactionSource = remember(member.memberId) { MutableInteractionSource() }
                        val hovered by interactionSource.collectIsHoveredAsState()
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(
                                    when {
                                        member.memberId == selectedMemberId -> EveColors.SelectedSurface
                                        hovered -> EveColors.HoverSurface
                                        else -> EveColors.PrimarySurface
                                    },
                                )
                                .hoverable(interactionSource)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                ) { selectedMemberId = member.memberId }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(member.displayName, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(strings.role(member.role), modifier = Modifier.width(70.dp))
                            Text(if (member.isActive) strings.active else strings.revoked, modifier = Modifier.width(75.dp))
                        }
                    }
                }
                if (selected != null && selected.isActive) {
                    Text(strings.role, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        SharedWorkspaceRole.entries.forEach { role ->
                            TextButton(
                                enabled = state.busyMemberId == null && role != selected.role,
                                selected = role == selected.role,
                                onClick = { onChangeRole(selected.memberId, selected.version, role) },
                            ) { Text(strings.role(role)) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = state.busyMemberId == null,
                        onClick = { showCreateMember = true },
                    ) { Text(strings.addMember) }
                    TextButton(
                        enabled = selected?.isActive == true && state.busyMemberId == null,
                        onClick = { selected?.let { onCreateInvite(it.memberId, 72) } },
                    ) { Text(strings.createInvite) }
                    TextButton(
                        enabled = selected?.isActive == true && state.busyMemberId == null,
                        onClick = { pendingRemove = selected },
                    ) { Text(appStrings.common.remove) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onLoad, enabled = !state.loading && state.busyMemberId == null) {
                        Text(strings.refresh)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = state.busyMemberId == null, onClick = onDismiss) { Text(appStrings.common.close) }
        },
    )

    if (showCreateMember) {
        CreateSharedMemberDialog(
            busy = state.busyMemberId != null,
            onCreate = { name, role ->
                if (onCreateMember(name, role)) showCreateMember = false
            },
            onDismiss = { showCreateMember = false },
        )
    }

    pendingRemove?.let { member ->
        AlertDialog(
            onDismissRequest = { if (state.busyMemberId == null) pendingRemove = null },
            title = { Text(strings.removeMemberTitle) },
            text = { Text(strings.removeMember(member.displayName, workspaceName)) },
            confirmButton = {
                TextButton(
                    enabled = state.busyMemberId == null,
                    onClick = {
                        if (onRemoveMember(member.memberId, member.version)) pendingRemove = null
                    },
                ) { Text(appStrings.common.remove) }
            },
            dismissButton = {
                TextButton(enabled = state.busyMemberId == null, onClick = { pendingRemove = null }) { Text(appStrings.common.cancel) }
            },
        )
    }

    state.oneTimeInvite?.let { oneTimeInvite ->
        OneTimeInviteDialog(oneTimeInvite, onDismiss = onClearInvite)
    }
}

@Composable
private fun CreateSharedMemberDialog(
    busy: Boolean,
    onCreate: (String, SharedWorkspaceRole) -> Unit,
    onDismiss: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.sharedMap
    var displayName by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(SharedWorkspaceRole.VIEWER) }
    val validName = displayName.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) <= 80 }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.addMember.removeSuffix("…")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { if (it.codePointCount(0, it.length) <= 80) displayName = it },
                    label = { Text(strings.displayName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(strings.initialRole)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    SharedWorkspaceRole.entries.forEach { option ->
                        TextButton(enabled = !busy, selected = role == option, onClick = { role = option }) {
                            Text(if (role == option) "✓ ${strings.role(option)}" else strings.role(option))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = validName && !busy, onClick = { onCreate(displayName, role) }) {
                Text(if (busy) strings.adding else strings.addMember.removeSuffix("…"))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(appStrings.common.cancel) } },
    )
}

@Composable
private fun OneTimeInviteDialog(invite: OneTimeSharedInvite, onDismiss: () -> Unit) {
    val appStrings = LocalAppStrings.current
    val strings = appStrings.sharedMap
    var inviteText by remember(invite) { mutableStateOf(invite.useSecret { it }) }
    var copied by remember(invite) { mutableStateOf(false) }
    DisposableEffect(invite) {
        onDispose { inviteText = "" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.oneTimeInviteTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.oneTimeInviteHelp)
                OutlinedTextField(
                    value = inviteText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(strings.invite) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (copied) Text(strings.copied, color = EveColors.SecondaryText)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(inviteText), null)
                    copied = true
                },
            ) { Text(strings.copy) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appStrings.common.close) } },
    )
}
