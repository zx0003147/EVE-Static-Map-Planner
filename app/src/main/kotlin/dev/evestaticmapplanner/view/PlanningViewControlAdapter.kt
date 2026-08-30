package dev.evestaticmapplanner.view

import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlPortFailure
import dev.evestaticmapplanner.control.PlanningViewControlPort
import dev.evestaticmapplanner.control.PlanningViewDto

class PlanningViewControlAdapter(
    private val coordinator: PlanningViewCoordinator,
) : PlanningViewControlPort {
    override suspend fun listViews(): List<PlanningViewDto> = coordinator.state.value.toDtos()

    override suspend fun currentView(): PlanningViewDto = coordinator.state.value.currentView.toDto(true)

    override suspend fun createView(label: String?): PlanningViewDto = try {
        val id = coordinator.createView(label)
        coordinator.state.value.views.single { it.id == id }.toDto(true)
    } catch (_: IllegalArgumentException) {
        invalid("View label is invalid or already exists")
    }

    override suspend fun renameView(viewId: String, label: String): PlanningViewDto {
        val id = PlanningViewId(viewId)
        if (!coordinator.renameView(id, label)) invalid("View label is invalid, duplicated, or the View was not found")
        return coordinator.state.value.views.single { it.id == id }.toDto(coordinator.state.value.currentViewId == id)
    }

    override suspend fun switchView(viewId: String): PlanningViewDto {
        val id = PlanningViewId(viewId)
        if (!coordinator.switchView(id)) missing()
        return coordinator.state.value.currentView.toDto(true)
    }

    override suspend fun deleteView(viewId: String): PlanningViewDto {
        val id = PlanningViewId(viewId)
        if (!coordinator.deleteView(id)) invalid("View was not found or is the last remaining View")
        return coordinator.state.value.currentView.toDto(true)
    }

    private fun PlanningViewsState.toDtos() = views.map { it.toDto(it.id == currentViewId) }
    private fun PlanningView.toDto(current: Boolean) = PlanningViewDto(id.value, label, current)
    private fun invalid(message: String): Nothing = throw ControlPortFailure(ControlErrorCode.INVALID_ARGUMENT, message)
    private fun missing(): Nothing = throw ControlPortFailure(ControlErrorCode.NOT_FOUND, "View was not found")
}
