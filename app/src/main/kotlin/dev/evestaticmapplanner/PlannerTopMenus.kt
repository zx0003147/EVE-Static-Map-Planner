package dev.evestaticmapplanner

import dev.evestaticmapplanner.localization.MainShellStrings
import dev.evestaticmapplanner.ui.EveMenuItemSpec
import dev.evestaticmapplanner.ui.EveMenuSpec

internal data class PlannerTopMenuState(
    val markerManagerOpen: Boolean,
    val sharedMarkerManagerOpen: Boolean,
    val temporaryMarkerCount: Int,
    val staticDataOpen: Boolean,
)

internal data class PlannerTopMenuActions(
    val openMarkerManager: () -> Unit,
    val openSharedMarkerManager: () -> Unit,
    val clearTemporaryMarkers: () -> Unit,
    val openMarkerSettings: () -> Unit,
    val openPreferences: () -> Unit,
    val openStaticData: () -> Unit,
)

internal fun plannerTopMenus(
    state: PlannerTopMenuState,
    actions: PlannerTopMenuActions,
    strings: MainShellStrings,
): List<EveMenuSpec> = listOfNotNull(
    EveMenuSpec(
        strings.marker,
        listOf(
            EveMenuItemSpec(
                strings.markerManager,
                enabled = !state.markerManagerOpen,
                onClick = actions.openMarkerManager,
            ),
            EveMenuItemSpec(
                strings.sharedMarkerManager,
                enabled = !state.sharedMarkerManagerOpen,
                onClick = actions.openSharedMarkerManager,
            ),
            EveMenuItemSpec(
                strings.clearAllTemporaryMarkers,
                enabled = state.temporaryMarkerCount > 0,
                separatorBefore = true,
                onClick = actions.clearTemporaryMarkers,
            ),
            EveMenuItemSpec(
                strings.markerSettings,
                separatorBefore = true,
                onClick = actions.openMarkerSettings,
            ),
        ),
    ),
    EveMenuSpec(
        strings.preferences,
        listOf(EveMenuItemSpec(strings.openPreferences, onClick = actions.openPreferences)),
    ),
    EveMenuSpec(
        strings.staticData,
        listOf(
            EveMenuItemSpec(
                strings.openStaticData,
                enabled = !state.staticDataOpen,
                onClick = actions.openStaticData,
            ),
        ),
    ),
)
