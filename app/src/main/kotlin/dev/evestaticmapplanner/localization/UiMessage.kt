package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.embeddedai.VoiceErrorCode

sealed interface UiMessage {
    fun resolve(strings: AppStrings): String
}

data class RouteFoundUiMessage(val jumps: Int) : UiMessage {
    init {
        require(jumps >= 0) { "Jump count must not be negative" }
    }

    override fun resolve(strings: AppStrings): String = strings.route.routeFound(jumps)
}

data object UnableToLoadMapUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.map.unableToLoadMap
}

data class FocusSwitchedToReal3DUiMessage(val systemName: String) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.map.focusSwitchedToReal3D(systemName)
}

data object UnableToLoadRouteGraphUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.unableToLoadRouteGraph
}

data object UnableToLoadCapitalRouteDataUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.unableToLoadCapitalRouteData
}

data object UnableToLoadJumpOverlayDataUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.unableToLoadJumpOverlayData
}

data object JumpOverlayCalculationFailedUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.jumpOverlayCalculationFailed
}

data object ManualMaximumLyMustBeNumberUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.manualMaximumLyMustBeNumber
}

data object ManualMaximumLyMustBePositiveUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.manualMaximumLyMustBePositive
}

data object MissingTerminalStopUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.addWaypointOrDestination
}

data object InvalidNavigationStopUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.invalidNavigationStop
}

data class AdjacentNavigationStopsUiMessage(val systemName: String) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.adjacentDuplicate(systemName)
}

data class NavigationSegmentFailureUiMessage(
    val fromRole: NavigationStopUiRole,
    val fromSystemName: String,
    val toRole: NavigationStopUiRole,
    val toSystemName: String,
) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.segmentFailure(
        strings.route.navigationStop(fromRole, fromSystemName),
        strings.route.navigationStop(toRole, toSystemName),
    )
}

data object AnsiblexDataUnavailableUiMessage : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.route.ansiblexDataUnavailable
}

data class PreferencesUiMessage(
    val id: PreferencesMessage,
    val argument: String? = null,
    val technicalDetail: String? = null,
) : UiMessage {
    override fun resolve(strings: AppStrings): String =
        strings.preferences.message(id, argument, technicalDetail)
}

data class VoiceFailureUiMessage(
    val code: VoiceErrorCode?,
    val technicalDetail: String? = null,
) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.preferences.voiceFailure(code, technicalDetail)
}

data class StaticDataUpdateFailedUiMessage(val technicalDetail: String? = null) : UiMessage {
    override fun resolve(strings: AppStrings): String = strings.staticData.updateFailed(technicalDetail)
}

data class StaticDatabaseStartupUiMessage(
    val issue: StaticDatabaseStartupIssue,
    val expectedSchema: Int,
    val actualSchema: Int? = null,
) : UiMessage {
    override fun resolve(strings: AppStrings): String =
        strings.staticData.startupError(issue, expectedSchema, actualSchema)
}
