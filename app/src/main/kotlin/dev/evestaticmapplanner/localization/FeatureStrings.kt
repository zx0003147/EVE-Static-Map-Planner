package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexAccessStatus
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.ImportDiagnostic
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.PlannerToolRisk
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.preferences.MiniMapFollowMode
import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole

interface AiAssistantStrings {
    val title: String
    val chats: String
    val newChat: String
    val chatLifetimeHelper: String
    val openAiSettings: String
    val startConversation: String
    val messagePlaceholder: String
    val stopRecordingAndTranscribe: String
    val cancelTranscription: String
    val startVoiceInput: String
    val stopGenerating: String
    val sendMessage: String
    val stopState: String
    val sendState: String
    val renameChat: String
    val collapseChatSidebar: String
    val expandChatSidebar: String
    val leftChevron: String
    val rightChevron: String
    val you: String
    val ai: String
    val waitingForConfirmation: String
    val thinking: String
    val stopReading: String
    val readAloud: String
    val confirmAiAction: String
    val action: String
    val target: String
    val risk: String
    val effect: String
    val allow: String

    fun riskLabel(risk: PlannerToolRisk): String
    fun credentialSource(source: AiCredentialSource): String
    fun providerDescription(providerName: String?, modelId: String?, source: AiCredentialSource?): String
    fun providerAction(providerConfigured: Boolean): String
    fun voiceStatus(status: AiVoiceStatus): String
    fun voiceControllerMessage(message: String): String
    fun contextNotice(message: String): String
}

enum class AiVoiceStatus {
    CANCELLED,
    RECORDING,
    TRANSCRIBING,
    TRANSCRIPTION_COMPLETE,
    TRANSCRIPTION_CANCELLED,
    PREPARING_SPEECH,
    PLAYING,
}

data class AiVoiceStatusUiMessage(val status: AiVoiceStatus) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.aiAssistant.voiceStatus(status)
}

interface MarkerStrings {
    val managerTitle: String
    val savedMarkerManager: String
    val searchSavedMarkers: String
    val loadingSavedMarkers: String
    val noSavedMarkers: String
    val noSavedMarkersMatch: String
    val showOnMap: String
    val removeSavedMarkerTitle: String
    val markerOperationFailed: String
    val removing: String
    val system: String
    val name: String
    val notes: String
    val color: String
    val addTemporaryMarker: String
    val addSavedMarker: String
    val editMarker: String
    val searchSystem: String
    val saving: String
    val save: String
    val tags: String
    val noTagsAssigned: String
    val allTagsAssigned: String
    val addTag: String
    val createdByAi: String
    val unknown: String
    val clearTemporaryTitle: String

    fun fallbackSystem(systemId: Int): String
    fun removeSavedMarker(systemName: String): String
    fun removeSavedMarkerCannotUndo(systemName: String): String
    fun selectedSystem(systemName: String, systemId: Int): String
    fun markerColor(color: MarkerColor): String
    fun childType(type: SavedMarkerChildType?, fallback: String): String
    fun databaseUnavailable(technicalDetail: String): String
    fun clearTemporaryMessage(count: Int): String
    fun message(id: MarkerMessage, systemId: Int? = null, tag: String? = null, technicalDetail: String? = null): String
}

enum class MarkerMessage {
    CREATE_TEMPORARY_FAILED,
    OPERATION_IN_PROGRESS,
    MARKER_MISSING,
    SAVED_CANNOT_UPDATE_AS_TEMPORARY,
    SAVED_CANNOT_REMOVE_AS_TEMPORARY,
    TEMPORARY_OPERATION_IN_PROGRESS,
    CREATE_SAVED_FAILED,
    UPDATE_SAVED_FAILED,
    REMOVE_SAVED_FAILED,
    TAG_ALREADY_ASSIGNED,
    ADD_TAG_FAILED,
    TAG_MISSING,
    REMOVE_TAG_FAILED,
    STILL_LOADING,
    ALREADY_SAVED,
    SAVE_TEMPORARY_FAILED,
    TEMPORARY_CANNOT_USE_SAVED_OPERATION,
    SYSTEM_ALREADY_MARKED,
    TEMPORARY_CONFLICT,
    SAVED_CONFLICT,
}

data class MarkerUiMessage(
    val id: MarkerMessage,
    val systemId: Int? = null,
    val tag: String? = null,
    val technicalDetail: String? = null,
) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.marker.message(id, systemId, tag, technicalDetail)
}

data class MarkerDatabaseUnavailableUiMessage(val technicalDetail: String) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.marker.databaseUnavailable(technicalDetail)
}

interface SharedMapStrings {
    val sharedMarkerManager: String
    val workspace: String
    val none: String
    val status: String
    val role: String
    val markers: String
    val accessRemoved: String
    val staleDataReadOnly: String
    val readOnlyUntilOnline: String
    val searchMarker: String
    val system: String
    val updated: String
    val name: String
    val refresh: String
    val noSnapshot: String
    val noMarkers: String
    val noMarkersMatch: String
    val focus: String
    val view: String
    val edit: String
    val addSharedMarker: String
    val editSharedMarker: String
    val sharedMarker: String
    val deleteSharedMarkerTitle: String
    val deleting: String
    val saving: String
    val color: String
    val tags: String
    val updatedBy: String
    val solarSystem: String
    val notes: String
    val tagsHelper: String
    val temporarilyUnavailable: String
    val markerMissing: String
    val markerChanged: String
    val reloadBeforeEditing: String
    val reloadLatest: String
    val saveSharedMarker: String
    val saveChanges: String
    val deleteSharedMarker: String
    val reloadLatestTitle: String
    val discardUnsavedChanges: String
    val commonTags: String
    val membersTitle: String
    val loadingMembers: String
    val dismiss: String
    val active: String
    val revoked: String
    val addMember: String
    val createInvite: String
    val removeMemberTitle: String
    val displayName: String
    val initialRole: String
    val adding: String
    val oneTimeInviteTitle: String
    val oneTimeInviteHelp: String
    val invite: String
    val copied: String
    val copy: String
    val routePublishedToWeb: String
    val operationFailed: String

    fun workspaceLabel(name: String): String
    fun statusLabel(value: String): String
    fun roleLabel(value: String): String
    fun markersLabel(count: Int): String
    fun connectionState(state: SharedConnectionState, stale: Boolean = false): String
    fun role(role: SharedWorkspaceRole): String
    fun markerColor(color: SharedMarkerColor): String
    fun deleteSharedMarker(name: String, systemName: String): String
    fun memberCount(count: Int): String
    fun removeMember(displayName: String, workspaceName: String): String
    fun error(error: SharedMapError): String
    fun unknownSystem(systemId: Int): String
    fun statusMessage(message: String?): String?
}

data object SharedMapOperationFailedUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.sharedMap.operationFailed
}

data object RoutePublishedToWebUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.sharedMap.routePublishedToWeb
}

interface WormholeStrings {
    val managerTitle: String
    val sessionOnlyHelp: String
    val addWormhole: String
    val from: String
    val to: String
    val currentWormholes: String
    val noActiveConnections: String
    val clearAll: String
    val clearAllTitle: String
    val clearAllMessage: String
    val createWormhole: String

    fun connectionCount(count: Int): String
    fun connectionsTitle(systemName: String): String
    fun fallbackSystem(systemId: Int): String
    fun message(id: WormholeMessage, count: Int? = null, technicalDetail: String? = null): String
}

enum class WormholeMessage {
    ADDED,
    REMOVED,
    MISSING,
    DUPLICATE,
    SAME_ENDPOINT,
    INVALID_SELECTION,
    CLEARED,
    LOAD_FAILED,
    SEARCH_FAILED,
}

data class WormholeUiMessage(
    val id: WormholeMessage,
    val count: Int? = null,
    val technicalDetail: String? = null,
) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.wormhole.message(id, count, technicalDetail)
}

interface AnsiblexStrings {
    val sectionTitle: String
    val managerTitle: String
    val importCsvJson: String
    val working: String
    val importAndPreview: String
    val discard: String
    val manualAdd: String
    val allianceIdentity: String
    val identitySource: String
    val esiIdentity: String
    val manualIdentity: String
    val currentCharacter: String
    val currentAlliance: String
    val currentAllianceId: String
    val applyAllianceIdentity: String
    val clearAllianceIdentity: String
    val ownerAllianceId: String
    val ownerAllianceName: String
    val ownerAllianceTicker: String
    val fromNameOrId: String
    val toNameOrId: String
    val connectionNameOptional: String
    val notesOptional: String
    val bidirectional: String
    val fromTo: String
    val addConnection: String
    val connections: String
    val clearImported: String
    val dangerZone: String
    val clearAllAnsiblex: String
    val deleteAllTitle: String
    val deleteImportedTitle: String
    val deleteAllWarning: String
    val deleteImportedWarning: String
    val typeDeleteManual: String
    val deleteEverything: String
    val fileChooserTitle: String
    val fileChooserFilter: String
    val switchIdentity: String
    val showUnavailable: String
    val esiUnavailable: String
    val simulateOtherAlliance: String
    val searchAlliance: String
    val advancedAllianceId: String
    val verifyAllianceId: String
    val verificationFailed: String
    val fileImport: String
    val pasteImport: String
    val pasteTitle: String
    val pasteHint: String
    val parse: String
    val ownerResolution: String
    val needsConfirmation: String

    fun importMode(mode: AnsiblexImportMode): String
    fun summary(enabled: Int, total: Int, databasePath: String): String
    fun previewCounts(rows: Int, valid: Int, invalid: Int, duplicates: Int, conflicts: Int): String
    fun previewChanges(additions: Int, updates: Int, unchanged: Int, removals: Int): String
    fun applyBlockedByConflicts(count: Int): String
    fun applyBlockedByUnresolvedOwners(count: Int): String
    fun applyBlockedByOtherErrors(count: Int): String
    fun diagnostic(diagnostic: ImportDiagnostic): String
    fun direction(direction: AnsiblexDirection): String
    fun source(source: AnsiblexSource): String
    fun accessStatus(status: AnsiblexAccessStatus): String
    fun permissionSummary(usable: Int, enabled: Int): String
    fun message(
        id: AnsiblexMessage,
        count: Int? = null,
        updatedCount: Int? = null,
        removedCount: Int? = null,
        value: String? = null,
        technicalDetail: String? = null,
    ): String
}

enum class AnsiblexMessage {
    PREVIEW_FAILED,
    APPLY_FAILED,
    IMPORT_APPLIED,
    UNKNOWN_FROM,
    UNKNOWN_TO,
    MANUAL_ADDED,
    ADD_FAILED,
    CONNECTION_MISSING,
    ENABLED,
    DISABLED,
    DELETED,
    CLEARED_IMPORTED,
    CLEARED_ALL,
    UPDATE_FAILED,
}

data class AnsiblexUiMessage(
    val id: AnsiblexMessage,
    val count: Int? = null,
    val updatedCount: Int? = null,
    val removedCount: Int? = null,
    val value: String? = null,
    val technicalDetail: String? = null,
) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.ansiblex.message(
        id,
        count,
        updatedCount,
        removedCount,
        value,
        technicalDetail,
    )
}

interface MiniMapStrings {
    val title: String
    val selectCharacter: String
    val noTrackedCharacters: String
    val automaticFollow: String
    val pinnedFollow: String
    val options: String
    val showDiagnostics: String
    val hideDiagnostics: String
    val bindCurrentClient: String
    val bindPrompt: String
    val unknownCharacter: String
    val diagnostics: String
    val never: String
    val none: String
    val noCharacterSelected: String
    val locationUnavailable: String
    val unknownSystem: String

    fun followMode(mode: MiniMapFollowMode): String
    fun portraitDescription(characterName: String): String
    fun trackingSummary(total: Int, tracked: Int, current: Int, stale: Int, degraded: Int, unknown: Int): String
    fun validationSummary(lastValidatedAt: String, errorCategory: String): String
    fun routeScope(hops: Int, includeAnsiblex: Boolean): String
    fun status(status: TrackedCharacterLocationStatus): String
    fun locationLine(systemName: String, status: TrackedCharacterLocationStatus): String
    fun diagnosticText(message: String): String
}
