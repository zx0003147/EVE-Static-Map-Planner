package dev.evestaticmapplanner.embeddedai

enum class PlannerToolRisk {
    READ_ONLY,
    TEMPORARY_UI,
    PERSISTENT_WRITE,
    EXTERNAL_ACTION,
}

data class PlannerToolPermission(
    val name: String,
    val risk: PlannerToolRisk,
)

internal object PlannerToolPermissions {
    val allowedRisks = setOf(
        PlannerToolRisk.READ_ONLY,
        PlannerToolRisk.TEMPORARY_UI,
    )

    val registered = listOf(
        PlannerToolPermission(GetSystemInfoTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(SearchSystemTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(CalculateNormalRouteTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(CalculateCapitalRouteTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(OptimizeMultiPointRouteTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(FocusSystemTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(BeginMissionTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(GetMissionTool.NAME, PlannerToolRisk.READ_ONLY),
        PlannerToolPermission(ShowNormalRouteTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ShowCapitalRouteTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(ShowJumpRangeTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(AddMissionMarkerTool.NAME, PlannerToolRisk.TEMPORARY_UI),
        PlannerToolPermission(FitMissionTool.NAME, PlannerToolRisk.TEMPORARY_UI),
    )

    init {
        check(registered.map(PlannerToolPermission::name).distinct().size == registered.size) {
            "Planner tool names must be unique"
        }
        check(registered.all { it.risk in allowedRisks }) {
            "Embedded AI registered a tool outside the Phase 4 permission boundary"
        }
    }
}
