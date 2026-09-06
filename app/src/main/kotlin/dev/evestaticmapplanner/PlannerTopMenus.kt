package dev.evestaticmapplanner

import dev.evestaticmapplanner.ui.EveMenuItemSpec
import dev.evestaticmapplanner.ui.EveMenuSpec

internal data class PlannerTopMenuState(
    val markerManagerOpen: Boolean,
    val sharedMarkerManagerOpen: Boolean,
    val temporaryMarkerCount: Int,
    val miniMapEnabled: Boolean,
    val staticDataOpen: Boolean,
)

internal data class PlannerTopMenuActions(
    val openMarkerManager: () -> Unit,
    val openSharedMarkerManager: () -> Unit,
    val clearTemporaryMarkers: () -> Unit,
    val openMarkerSettings: () -> Unit,
    val toggleMiniMap: () -> Unit,
    val openMiniMapSettings: () -> Unit,
    val openPreferences: () -> Unit,
    val openStaticData: () -> Unit,
)

internal fun plannerTopMenus(
    state: PlannerTopMenuState,
    actions: PlannerTopMenuActions,
): List<EveMenuSpec> = listOf(
    EveMenuSpec(
        "Marker",
        listOf(
            EveMenuItemSpec(
                "Marker Manager…",
                enabled = !state.markerManagerOpen,
                onClick = actions.openMarkerManager,
            ),
            EveMenuItemSpec(
                "Shared Marker Manager…",
                enabled = !state.sharedMarkerManagerOpen,
                onClick = actions.openSharedMarkerManager,
            ),
            EveMenuItemSpec(
                "Clear All Temporary Markers…",
                enabled = state.temporaryMarkerCount > 0,
                separatorBefore = true,
                onClick = actions.clearTemporaryMarkers,
            ),
            EveMenuItemSpec(
                "Marker Settings…",
                separatorBefore = true,
                onClick = actions.openMarkerSettings,
            ),
        ),
    ),
    EveMenuSpec(
        "Mini-map",
        listOf(
            EveMenuItemSpec(if (state.miniMapEnabled) "Hide Mini-map" else "Show Mini-map", onClick = actions.toggleMiniMap),
            EveMenuItemSpec(
                "Mini-map Settings…",
                separatorBefore = true,
                onClick = actions.openMiniMapSettings,
            ),
        ),
    ),
    EveMenuSpec(
        "Preferences",
        listOf(EveMenuItemSpec("Preferences…", onClick = actions.openPreferences)),
    ),
    EveMenuSpec(
        "Static Data",
        listOf(
            EveMenuItemSpec(
                "Static Data…",
                enabled = !state.staticDataOpen,
                onClick = actions.openStaticData,
            ),
        ),
    ),
)
