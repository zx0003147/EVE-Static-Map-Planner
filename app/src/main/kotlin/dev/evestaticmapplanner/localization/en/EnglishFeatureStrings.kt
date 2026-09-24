package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexAccessStatus
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.ImportConflictField
import dev.evestaticmapplanner.data.ansiblex.ImportDiagnostic
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.PlannerToolRisk
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.localization.AiAssistantStrings
import dev.evestaticmapplanner.localization.AiVoiceStatus
import dev.evestaticmapplanner.localization.AnsiblexMessage
import dev.evestaticmapplanner.localization.AnsiblexStrings
import dev.evestaticmapplanner.localization.MarkerMessage
import dev.evestaticmapplanner.localization.MarkerStrings
import dev.evestaticmapplanner.localization.MiniMapStrings
import dev.evestaticmapplanner.localization.SharedMapStrings
import dev.evestaticmapplanner.localization.WormholeMessage
import dev.evestaticmapplanner.localization.WormholeStrings
import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.preferences.MiniMapFollowMode

internal object EnglishAiAssistantStrings : AiAssistantStrings {
    override val title = "Embedded AI Assistant"
    override val chats = "Chats"
    override val newChat = "+ New Chat"
    override val chatLifetimeHelper = "Chats last until the Planner closes."
    override val openAiSettings = "Open AI Settings"
    override val startConversation = "Start a conversation with the Planner."
    override val messagePlaceholder = "Message…"
    override val stopRecordingAndTranscribe = "Stop recording and transcribe"
    override val cancelTranscription = "Cancel transcription"
    override val startVoiceInput = "Start voice input"
    override val stopGenerating = "Stop generating"
    override val sendMessage = "Send message"
    override val stopState = "stop"
    override val sendState = "send"
    override val renameChat = "Rename chat"
    override val collapseChatSidebar = "Collapse chat sidebar"
    override val expandChatSidebar = "Expand chat sidebar"
    override val leftChevron = "Left chevron"
    override val rightChevron = "Right chevron"
    override val you = "You"
    override val ai = "AI"
    override val waitingForConfirmation = "Waiting for confirmation…"
    override val thinking = "Thinking…"
    override val stopReading = "Stop reading"
    override val readAloud = "Read aloud"
    override val confirmAiAction = "Confirm AI action"
    override val action = "Action"
    override val target = "Target"
    override val risk = "Risk"
    override val effect = "Effect"
    override val allow = "Allow"

    override fun riskLabel(risk: PlannerToolRisk) = when (risk) {
        PlannerToolRisk.READ_ONLY -> "Read only"
        PlannerToolRisk.TEMPORARY_UI -> "Temporary map change"
        PlannerToolRisk.PERSISTENT_WRITE -> "Permanent write"
        PlannerToolRisk.DESTRUCTIVE_WRITE -> "Destructive change"
        PlannerToolRisk.EXTERNAL_ACTION -> "External action"
    }

    override fun credentialSource(source: AiCredentialSource) = when (source) {
        AiCredentialSource.SECURE_STORAGE -> "Secure storage"
        AiCredentialSource.SESSION_ONLY -> "This session only"
        AiCredentialSource.ENVIRONMENT -> "Environment variable"
    }

    override fun providerDescription(providerName: String?, modelId: String?, source: AiCredentialSource?) = when {
        providerName == null -> "AI provider is not configured."
        source == null -> "Provider: $providerName · AI API Key is not configured."
        else -> "$providerName · $modelId · ${credentialSource(source)}"
    }

    override fun providerAction(providerConfigured: Boolean) =
        if (providerConfigured) "Configure an AI API Key to begin." else "Configure an AI provider to begin."

    override fun voiceStatus(status: AiVoiceStatus) = when (status) {
        AiVoiceStatus.CANCELLED -> "Voice activity cancelled."
        AiVoiceStatus.RECORDING -> "Recording… click again to transcribe."
        AiVoiceStatus.TRANSCRIBING -> "Transcribing…"
        AiVoiceStatus.TRANSCRIPTION_COMPLETE -> "Transcription complete."
        AiVoiceStatus.TRANSCRIPTION_CANCELLED -> "Transcription cancelled."
        AiVoiceStatus.PREPARING_SPEECH -> "Preparing speech…"
        AiVoiceStatus.PLAYING -> "Playing…"
    }
    override fun voiceControllerMessage(message: String) = message
    override fun contextNotice(message: String) = message
}

internal object EnglishMarkerStrings : MarkerStrings {
    override val managerTitle = "Marker Manager"
    override val savedMarkerManager = "Saved Marker Manager"
    override val searchSavedMarkers = "Search saved markers…"
    override val loadingSavedMarkers = "Loading saved markers…"
    override val noSavedMarkers = "No saved markers."
    override val noSavedMarkersMatch = "No saved markers match this search."
    override val showOnMap = "Show on Map"
    override val removeSavedMarkerTitle = "Remove saved marker?"
    override val markerOperationFailed = "Marker operation failed"
    override val removing = "Removing…"
    override val system = "System"
    override val name = "Name"
    override val notes = "Notes"
    override val color = "Color"
    override val addTemporaryMarker = "Add Temporary Marker"
    override val addSavedMarker = "Add Saved Marker"
    override val editMarker = "Edit Marker"
    override val searchSystem = "Search system"
    override val saving = "Saving…"
    override val save = "Save"
    override val tags = "Tags"
    override val noTagsAssigned = "No tags assigned."
    override val allTagsAssigned = "All tags assigned"
    override val addTag = "+ Add Tag"
    override val createdByAi = "Created by AI"
    override val unknown = "Unknown"
    override val clearTemporaryTitle = "Clear temporary markers?"

    override fun fallbackSystem(systemId: Int) = "System $systemId"
    override fun removeSavedMarker(systemName: String) = "Remove saved marker from $systemName?"
    override fun removeSavedMarkerCannotUndo(systemName: String) =
        "Remove the saved marker from $systemName? This cannot be undone."
    override fun selectedSystem(systemName: String, systemId: Int) = "Selected: $systemName · $systemId"
    override fun markerColor(color: MarkerColor) = color.name.lowercase().replaceFirstChar(Char::uppercase)
    override fun childType(type: SavedMarkerChildType?, fallback: String) = when (type?.key) {
        "staging" -> "Staging"
        "rally" -> "Rally"
        "danger" -> "Danger"
        "logistics" -> "Logistics"
        "home" -> "Home"
        "backup" -> "Backup"
        "industrial" -> "Industrial"
        "strategic" -> "Strategic"
        "fortizar" -> "Fortizar"
        "keepstar" -> "Keepstar"
        else -> fallback
    }
    override fun databaseUnavailable(technicalDetail: String) = "Saved markers are unavailable.\n$technicalDetail"
    override fun clearTemporaryMessage(count: Int) =
        "Remove all $count temporary markers? Saved markers will not be changed."

    override fun message(id: MarkerMessage, systemId: Int?, tag: String?, technicalDetail: String?) = appendDetail(
        when (id) {
            MarkerMessage.CREATE_TEMPORARY_FAILED -> "Unable to create temporary marker."
            MarkerMessage.OPERATION_IN_PROGRESS -> "Marker operation is already in progress for solar system $systemId."
            MarkerMessage.MARKER_MISSING -> "Marker no longer exists for solar system $systemId."
            MarkerMessage.SAVED_CANNOT_UPDATE_AS_TEMPORARY -> "Saved marker cannot be updated as temporary for solar system $systemId."
            MarkerMessage.SAVED_CANNOT_REMOVE_AS_TEMPORARY -> "Saved marker cannot be removed as temporary for solar system $systemId."
            MarkerMessage.TEMPORARY_OPERATION_IN_PROGRESS -> "A temporary marker operation is still in progress."
            MarkerMessage.CREATE_SAVED_FAILED -> "Unable to create saved marker."
            MarkerMessage.UPDATE_SAVED_FAILED -> "Unable to update saved marker."
            MarkerMessage.REMOVE_SAVED_FAILED -> "Unable to remove saved marker."
            MarkerMessage.TAG_ALREADY_ASSIGNED -> "$tag is already assigned to this saved marker."
            MarkerMessage.ADD_TAG_FAILED -> "Unable to add saved marker tag."
            MarkerMessage.TAG_MISSING -> "Saved marker tag no longer exists."
            MarkerMessage.REMOVE_TAG_FAILED -> "Unable to remove saved marker tag."
            MarkerMessage.STILL_LOADING -> "Saved markers are still loading."
            MarkerMessage.ALREADY_SAVED -> "Marker is already saved for solar system $systemId."
            MarkerMessage.SAVE_TEMPORARY_FAILED -> "Unable to save temporary marker permanently."
            MarkerMessage.TEMPORARY_CANNOT_USE_SAVED_OPERATION -> "Temporary marker cannot use a saved marker operation for solar system $systemId."
            MarkerMessage.SYSTEM_ALREADY_MARKED -> "Solar system $systemId already has a marker."
            MarkerMessage.TEMPORARY_CONFLICT -> "This system already has a temporary marker. Use Save Permanently on that marker instead."
            MarkerMessage.SAVED_CONFLICT -> "This system already has a marker."
        },
        technicalDetail,
    )
}

internal object EnglishSharedMapStrings : SharedMapStrings {
    override val sharedMarkerManager = "Shared Marker Manager"
    override val workspace = "Workspace"
    override val none = "None"
    override val status = "Status"
    override val role = "Role"
    override val markers = "Markers"
    override val accessRemoved = "Shared Map access was removed."
    override val staleDataReadOnly = "Showing stale Shared Marker data; editing is disabled."
    override val readOnlyUntilOnline = "Shared Map is read-only until the connection is online."
    override val searchMarker = "Search system, marker, or tag"
    override val system = "System"
    override val updated = "Updated"
    override val name = "Name"
    override val refresh = "Refresh"
    override val noSnapshot = "No Shared Marker snapshot is available."
    override val noMarkers = "No Shared Markers in this Workspace."
    override val noMarkersMatch = "No Shared Markers match this search."
    override val focus = "Focus"
    override val view = "View"
    override val edit = "Edit"
    override val addSharedMarker = "Add Shared Marker"
    override val editSharedMarker = "Edit Shared Marker"
    override val sharedMarker = "Shared Marker"
    override val deleteSharedMarkerTitle = "Delete Shared Marker?"
    override val deleting = "Deleting…"
    override val saving = "Saving…"
    override val color = "Color"
    override val tags = "Tags"
    override val updatedBy = "Updated by"
    override val solarSystem = "Solar System"
    override val notes = "Notes"
    override val tagsHelper = "Comma or space separated; up to 9 lowercase tags"
    override val temporarilyUnavailable = "Shared Map is temporarily unavailable or your role is read-only."
    override val markerMissing = "This Shared Marker no longer exists."
    override val markerChanged = "This Shared Marker was changed by another user."
    override val reloadBeforeEditing = "Reload the latest server version before editing again."
    override val reloadLatest = "Reload Latest"
    override val saveSharedMarker = "Save Shared Marker"
    override val saveChanges = "Save Changes"
    override val deleteSharedMarker = "Delete Shared Marker"
    override val reloadLatestTitle = "Reload latest Shared Marker?"
    override val discardUnsavedChanges = "Your unsaved changes will be discarded."
    override val commonTags = "Common tags"
    override val membersTitle = "Shared Map Members"
    override val loadingMembers = "Loading members…"
    override val dismiss = "Dismiss"
    override val active = "Active"
    override val revoked = "Revoked"
    override val addMember = "Add Member…"
    override val createInvite = "Create Invite"
    override val removeMemberTitle = "Remove member?"
    override val displayName = "Display name"
    override val initialRole = "Initial role"
    override val adding = "Adding…"
    override val oneTimeInviteTitle = "One-time Shared Map Invite"
    override val oneTimeInviteHelp = "This invite is shown once. Send it through a trusted channel."
    override val invite = "Invite"
    override val copied = "Copied to clipboard."
    override val copy = "Copy"
    override val routePublishedToWeb = "Route published to Web."
    override val operationFailed = "The Shared Map operation could not be completed."

    override fun workspaceLabel(name: String) = "$workspace: $name"
    override fun statusLabel(value: String) = "$status: $value"
    override fun roleLabel(value: String) = "$role: $value"
    override fun markersLabel(count: Int) = "$markers: $count"
    override fun connectionState(state: SharedConnectionState, stale: Boolean) =
        state.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) + if (stale) " · stale" else ""
    override fun role(role: SharedWorkspaceRole) = when (role) {
        SharedWorkspaceRole.ADMIN -> "Admin"
        SharedWorkspaceRole.EDITOR -> "Editor"
        SharedWorkspaceRole.VIEWER -> "Viewer"
    }
    override fun markerColor(color: SharedMarkerColor) = color.name.lowercase().replaceFirstChar(Char::uppercase)
    override fun deleteSharedMarker(name: String, systemName: String) = "Delete shared marker “$name” from $systemName?"
    override fun memberCount(count: Int) = "Members: $count"
    override fun removeMember(displayName: String, workspaceName: String) =
        "Remove $displayName from $workspaceName? Their devices will lose access immediately."
    override fun error(error: SharedMapError): String = when (error) {
        is SharedMapError.Forbidden -> "You no longer have permission to change Shared Markers."
        is SharedMapError.Authentication -> "Authentication is required before Shared Markers can be changed."
        is SharedMapError.MarkerAlreadyExists -> "This system already has a Shared Marker. Reload the latest snapshot."
        is SharedMapError.MarkerVersionConflict -> markerChanged
        is SharedMapError.InvalidArgument -> error.message
        is SharedMapError.RateLimited -> "Too many requests. Please try again shortly."
        is SharedMapError.Network -> "The Shared Map server could not be reached. Your changes were not confirmed."
        is SharedMapError.Server -> "The Shared Map server could not complete this operation."
        is SharedMapError.NotFound -> markerMissing
        is SharedMapError.MemberVersionConflict -> "This member was changed by another administrator. Reload members."
        is SharedMapError.LastAdminRequired -> "The Workspace must retain at least one active Admin."
        is SharedMapError.IdempotencyResponseNotReplayable ->
            "The invite was created, but its one-time secret could not be replayed. Revoke it and create another."
        is SharedMapError.Protocol -> error.message
        is SharedMapError.InvalidResponse -> "The Shared Map server returned an invalid response."
        is SharedMapError.InvalidConfiguration -> error.message
    }
    override fun unknownSystem(systemId: Int) = "Unknown System ($systemId)"
    override fun statusMessage(message: String?) = message
}

internal object EnglishWormholeStrings : WormholeStrings {
    override val managerTitle = "Wormhole Manager"
    override val sessionOnlyHelp = "Wormholes exist only for the current application session."
    override val addWormhole = "Add Wormhole"
    override val from = "From"
    override val to = "To"
    override val currentWormholes = "Current Wormholes"
    override val noActiveConnections = "No active Wormhole connections"
    override val clearAll = "Clear All"
    override val clearAllTitle = "Clear all Wormholes?"
    override val clearAllMessage = "This will remove all temporary Wormhole connections for the current application session."
    override val createWormhole = "Create Wormhole"
    override fun connectionCount(count: Int) = "$count active session ${if (count == 1) "connection" else "connections"}"
    override fun connectionsTitle(systemName: String) = "Wormhole Connections — $systemName"
    override fun fallbackSystem(systemId: Int) = "System $systemId"
    override fun message(id: WormholeMessage, count: Int?, technicalDetail: String?) = appendDetail(
        when (id) {
            WormholeMessage.ADDED -> "Wormhole added"
            WormholeMessage.REMOVED -> "Wormhole removed"
            WormholeMessage.MISSING -> "Wormhole connection no longer exists"
            WormholeMessage.DUPLICATE -> "Wormhole connection already exists"
            WormholeMessage.SAME_ENDPOINT -> "Wormhole endpoints must be different systems"
            WormholeMessage.INVALID_SELECTION -> "Select both Wormhole endpoints"
            WormholeMessage.CLEARED -> "Cleared $count Wormhole ${if (count == 1) "connection" else "connections"}"
            WormholeMessage.LOAD_FAILED -> "Unable to load solar systems."
            WormholeMessage.SEARCH_FAILED -> "System search failed."
        },
        technicalDetail,
    )
}

internal object EnglishAnsiblexStrings : AnsiblexStrings {
    override val sectionTitle = "Ansiblex"
    override val managerTitle = "Ansiblex Manager"
    override val importCsvJson = "Import CSV / JSON"
    override val working = "Working…"
    override val importAndPreview = "Import and Preview"
    override val discard = "Discard"
    override val manualAdd = "Manual Add"
    override val allianceIdentity = "Alliance access"
    override val identitySource = "Source"
    override val esiIdentity = "ESI current identity"
    override val manualIdentity = "Manual alliance simulation"
    override val currentCharacter = "Character"
    override val currentAlliance = "Alliance"
    override val currentAllianceId = "Manual alliance ID"
    override val applyAllianceIdentity = "Apply Manual Alliance"
    override val clearAllianceIdentity = "Clear Manual Alliance"
    override val ownerAllianceId = "Owner alliance ID"
    override val ownerAllianceName = "Owner alliance name (optional)"
    override val ownerAllianceTicker = "Owner alliance ticker (optional)"
    override val fromNameOrId = "From name or ID"
    override val toNameOrId = "To name or ID"
    override val connectionNameOptional = "Connection name (optional)"
    override val notesOptional = "Notes (optional)"
    override val bidirectional = "Bidirectional"
    override val fromTo = "From → To"
    override val addConnection = "Add Connection"
    override val connections = "Connections"
    override val clearImported = "Clear Imported"
    override val dangerZone = "Danger zone"
    override val clearAllAnsiblex = "Clear All Ansiblex"
    override val deleteAllTitle = "Delete all Ansiblex data?"
    override val deleteImportedTitle = "Delete imported Ansiblex data?"
    override val deleteAllWarning = "This permanently deletes IMPORT and MANUAL connections from user.db. This cannot be undone."
    override val deleteImportedWarning = "This deletes only source=IMPORT connections. MANUAL connections are preserved."
    override val typeDeleteManual = "Type DELETE MANUAL to confirm:"
    override val deleteEverything = "Delete Everything"
    override val fileChooserTitle = "Select synthetic or user-maintained Ansiblex CSV/JSON"
    override val fileChooserFilter = "Ansiblex CSV or JSON"
    override val switchIdentity = "Switch"
    override val showUnavailable = "Show unavailable Ansiblex"
    override val esiUnavailable = "ESI Identity unavailable"
    override val simulateOtherAlliance = "Simulate another alliance"
    override val searchAlliance = "Search alliance…"
    override val advancedAllianceId = "Advanced Alliance ID"
    override val verifyAllianceId = "Verify ID"
    override val verificationFailed = "ESI could not verify this Alliance ID"
    override val fileImport = "File Import"
    override val pasteImport = "Paste Text Import"
    override val pasteTitle = "Paste Ansiblex Data"
    override val pasteHint = "Paste a The Webway table here"
    override val parse = "Parse"
    override val ownerResolution = "Owner resolution"
    override val needsConfirmation = "Needs confirmation"

    override fun importMode(mode: AnsiblexImportMode) = mode.name
    override fun summary(enabled: Int, total: Int, databasePath: String) = "$enabled enabled / $total total · $databasePath"
    override fun previewCounts(rows: Int, valid: Int, invalid: Int, duplicates: Int, conflicts: Int) =
        "Original rows $rows · valid connections $valid · duplicate display rows $duplicates · " +
            "invalid rows $invalid · conflicting connections $conflicts"
    override fun previewChanges(additions: Int, updates: Int, unchanged: Int, removals: Int) =
        "+$additions  ~$updates  =$unchanged  -$removals"
    override fun applyBlockedByConflicts(count: Int) = "Cannot apply: $count conflicting connection(s) require correction."
    override fun applyBlockedByUnresolvedOwners(count: Int) = "Cannot apply: $count owner alliance value(s) require confirmation."
    override fun applyBlockedByOtherErrors(count: Int) = "Cannot apply: $count other import error(s) require correction."
    override fun diagnostic(diagnostic: ImportDiagnostic): String {
        val conflict = diagnostic.duplicateConflict
        if (diagnostic.code == "CONFLICTING_DUPLICATE" && conflict != null) {
            val first = conflict.firstSystemName?.let { "$it (#${conflict.firstSystemId})" } ?: "#${conflict.firstSystemId}"
            val second = conflict.secondSystemName?.let { "$it (#${conflict.secondSystemId})" } ?: "#${conflict.secondSystemId}"
            val fields = conflict.fields.joinToString("; ") { detail ->
                val label = when (detail.field) {
                    ImportConflictField.DIRECTION -> "direction"
                    ImportConflictField.DISPLAY_NAME -> "display name"
                    ImportConflictField.NOTES -> "notes"
                    ImportConflictField.OWNER_ALLIANCE -> "owner alliance"
                    ImportConflictField.ENABLED -> "status"
                }
                "$label: ${detail.values.joinToString { "row ${it.rowNumber}=${it.value}" }}"
            }
            return "$first ↔ $second · rows ${conflict.rowNumbers.joinToString()} · conflicting fields: $fields"
        }
        return "${diagnostic.rowNumber?.let { "Row $it: " }.orEmpty()}${diagnostic.message}"
    }
    override fun direction(direction: AnsiblexDirection) = when (direction) {
        AnsiblexDirection.BIDIRECTIONAL -> "Bidirectional"
        AnsiblexDirection.FIRST_TO_SECOND -> "First → Second"
        AnsiblexDirection.SECOND_TO_FIRST -> "Second → First"
    }
    override fun source(source: AnsiblexSource) = when (source) {
        AnsiblexSource.IMPORT -> "Import"
        AnsiblexSource.MANUAL -> "Manual"
    }
    override fun accessStatus(status: AnsiblexAccessStatus) = when (status) {
        AnsiblexAccessStatus.AVAILABLE -> "Allowed"
        AnsiblexAccessStatus.DISABLED -> "Disabled"
        AnsiblexAccessStatus.ALLIANCE_NOT_SELECTED -> "Unknown · no current Alliance ID"
        AnsiblexAccessStatus.OWNER_UNKNOWN -> "Unknown · owner Alliance ID missing"
        AnsiblexAccessStatus.ALLIANCE_MISMATCH -> "Denied · Alliance ID mismatch"
    }
    override fun permissionSummary(usable: Int, enabled: Int) = "$usable usable / $enabled enabled"
    override fun message(
        id: AnsiblexMessage,
        count: Int?,
        updatedCount: Int?,
        removedCount: Int?,
        value: String?,
        technicalDetail: String?,
    ) = appendDetail(
        when (id) {
            AnsiblexMessage.PREVIEW_FAILED -> "Unable to preview import."
            AnsiblexMessage.APPLY_FAILED -> "Unable to apply import."
            AnsiblexMessage.IMPORT_APPLIED -> "Applied $count additions, $updatedCount updates, $removedCount removals"
            AnsiblexMessage.UNKNOWN_FROM -> "Unknown or ambiguous From system: $value"
            AnsiblexMessage.UNKNOWN_TO -> "Unknown or ambiguous To system: $value"
            AnsiblexMessage.MANUAL_ADDED -> "Manual connection added"
            AnsiblexMessage.ADD_FAILED -> "Unable to add connection."
            AnsiblexMessage.CONNECTION_MISSING -> "Connection no longer exists"
            AnsiblexMessage.ENABLED -> "Connection enabled"
            AnsiblexMessage.DISABLED -> "Connection disabled"
            AnsiblexMessage.DELETED -> "Connection deleted"
            AnsiblexMessage.CLEARED_IMPORTED -> "Cleared $count imported connections"
            AnsiblexMessage.CLEARED_ALL -> "Cleared $count Ansiblex connections"
            AnsiblexMessage.UPDATE_FAILED -> "Unable to update connection."
        },
        technicalDetail,
    )
}

private fun appendDetail(message: String, detail: String?): String =
    detail?.takeIf { it.isNotBlank() && it != message }?.let { "$message\n$it" } ?: message

internal object EnglishMiniMapStrings : MiniMapStrings {
    override val title = "EVE Mini-map"
    override val selectCharacter = "Select character"
    override val noTrackedCharacters = "No tracked characters"
    override val automaticFollow = "AUTO — Follow foreground EVE client"
    override val pinnedFollow = "PINNED — Keep current character"
    override val options = "Mini-map options"
    override val showDiagnostics = "Show diagnostics"
    override val hideDiagnostics = "Hide diagnostics"
    override val bindCurrentClient = "Bind current EVE client…"
    override val bindPrompt = "Temporarily bind this EVE client to:"
    override val unknownCharacter = "Unknown character"
    override val diagnostics = "Diagnostics"
    override val never = "never"
    override val none = "none"
    override val noCharacterSelected = "No character selected"
    override val locationUnavailable = "Location unavailable"
    override val unknownSystem = "Unknown system"
    override fun followMode(mode: MiniMapFollowMode) = mode.name
    override fun portraitDescription(characterName: String) = "$characterName portrait"
    override fun trackingSummary(total: Int, tracked: Int, current: Int, stale: Int, degraded: Int, unknown: Int) =
        "Tracking $tracked/$total · current $current · stale $stale · degraded $degraded · unknown $unknown"
    override fun validationSummary(lastValidatedAt: String, errorCategory: String) =
        "Validated $lastValidatedAt · Error $errorCategory"
    override fun routeScope(hops: Int, includeAnsiblex: Boolean) =
        "$hops hops · Ansiblex ${if (includeAnsiblex) "included" else "excluded"}"
    override fun status(status: TrackedCharacterLocationStatus) = status.name
    override fun locationLine(systemName: String, status: TrackedCharacterLocationStatus) =
        if (status == TrackedCharacterLocationStatus.STALE) "Last known: $systemName · ${status(status)}"
        else "$systemName · ${status(status)}"
    override fun diagnosticText(message: String) = message
}
