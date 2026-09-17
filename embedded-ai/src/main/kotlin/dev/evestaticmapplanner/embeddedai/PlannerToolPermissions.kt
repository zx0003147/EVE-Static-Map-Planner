package dev.evestaticmapplanner.embeddedai

enum class PlannerToolRisk {
    READ_ONLY,
    TEMPORARY_UI,
    PERSISTENT_WRITE,
    DESTRUCTIVE_WRITE,
    EXTERNAL_ACTION,
}

val PlannerToolRisk.requiresConfirmation: Boolean
    get() = this == PlannerToolRisk.PERSISTENT_WRITE ||
        this == PlannerToolRisk.DESTRUCTIVE_WRITE ||
        this == PlannerToolRisk.EXTERNAL_ACTION

data class PlannerToolPermission(
    val name: String,
    val risk: PlannerToolRisk,
)

internal interface ConfirmationRequiredPlannerTool

internal object PlannerToolPermissions {
    val registered = listOf(
        PlannerToolPermission(GetSystemInfoTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(GetSystemMarkersTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(SearchSystemTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(CalculateNormalRouteTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(CalculateCapitalRouteTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(OptimizeMultiPointRouteTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(FocusSystemTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(BeginMissionTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(GetMissionTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(ShowNormalRouteTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ShowCapitalRouteTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(RemoveMissionRouteTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ClearMissionRoutesTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ShowJumpRangeTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(RemoveJumpRangeTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ClearMissionJumpRangesTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(AddMissionMarkerTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(RemoveMissionMarkerTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ClearMissionMarkersTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(FitMissionTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ListViewsTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(GetCurrentViewTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(CreateViewTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(RenameViewTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(SwitchViewTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(DeleteViewTool.NAME, PlannerToolRisk.DESTRUCTIVE_WRITE),
        PlannerToolPermission(GetActiveMissionsTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(ClearMissionTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ListWormholesTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(CreateWormholeTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(CreateSavedMarkerTool.NAME, PlannerToolRisk.PERSISTENT_WRITE),
        PlannerToolPermission(ListEveNavigationTargetsTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(SendMissionNavigationToEveTool.NAME, PlannerToolRisk.EXTERNAL_ACTION),
    )

    init {
        check(registered.map(PlannerToolPermission::name).distinct().size == registered.size) {
            "Planner tool names must be unique"
        }
        check(registered.count { it.risk.requiresConfirmation } == 3) {
            "Every registered high-risk tool must have an explicit confirmation-gateway implementation"
        }
    }
}

internal object EmbeddedAiToolCatalog {
    fun names(plannerTools: PlannerToolSet): List<String> = plannerTools.names + WebSearchTool.NAME
}
