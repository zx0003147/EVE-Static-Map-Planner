package dev.evestaticmapplanner.view

import dev.evestaticmapplanner.capital.CapitalRoutePlanningPort
import dev.evestaticmapplanner.capital.CapitalRoutePlanningSnapshot
import dev.evestaticmapplanner.route.NormalRoutePlanningPort
import dev.evestaticmapplanner.route.NormalRoutePlanningSnapshot
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@JvmInline
value class PlanningViewId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class PlanningView(
    val id: PlanningViewId,
    val label: String,
    val normalRoute: NormalRoutePlanningSnapshot = NormalRoutePlanningSnapshot(),
    val capitalRoute: CapitalRoutePlanningSnapshot = CapitalRoutePlanningSnapshot(),
)

data class PlanningViewsState(
    val views: List<PlanningView>,
    val currentViewId: PlanningViewId,
) {
    init {
        require(views.isNotEmpty())
        require(views.any { it.id == currentViewId })
        require(views.map { it.id }.distinct().size == views.size)
        require(views.map { it.label.lowercase() }.distinct().size == views.size)
    }

    val currentView: PlanningView get() = views.single { it.id == currentViewId }
}

class PlanningViewCoordinator(
    private val normalRoute: NormalRoutePlanningPort,
    private val capitalRoute: CapitalRoutePlanningPort,
    private val newId: () -> PlanningViewId = { PlanningViewId(UUID.randomUUID().toString()) },
    initialViews: List<PlanningView> = listOf(PlanningView(PlanningViewId("view-1"), "View 1")),
    initialCurrentViewId: PlanningViewId = initialViews.first().id,
) {
    private val mutableState = MutableStateFlow(PlanningViewsState(initialViews, initialCurrentViewId))
    val state: StateFlow<PlanningViewsState> = mutableState.asStateFlow()

    init {
        restore(mutableState.value.currentView)
    }

    @Synchronized
    fun createView(): PlanningViewId {
        captureCurrent()
        val current = mutableState.value
        val view = PlanningView(newId(), nextDefaultLabel(current.views))
        mutableState.value = PlanningViewsState(current.views + view, view.id)
        restore(view)
        return view.id
    }

    @Synchronized
    fun switchView(id: PlanningViewId): Boolean {
        if (id == mutableState.value.currentViewId) return true
        if (mutableState.value.views.none { it.id == id }) return false
        captureCurrent()
        val current = mutableState.value
        val target = current.views.single { it.id == id }
        mutableState.value = current.copy(currentViewId = id)
        restore(target)
        return true
    }

    @Synchronized
    fun renameView(id: PlanningViewId, requestedLabel: String): Boolean {
        val label = normalizeLabel(requestedLabel) ?: return false
        val current = mutableState.value
        if (current.views.any { it.id != id && it.label.equals(label, ignoreCase = true) }) return false
        if (current.views.none { it.id == id }) return false
        mutableState.value = current.copy(
            views = current.views.map { if (it.id == id) it.copy(label = label) else it },
        )
        return true
    }

    @Synchronized
    fun deleteView(id: PlanningViewId): Boolean {
        val before = mutableState.value
        if (before.views.size == 1 || before.views.none { it.id == id }) return false
        captureCurrent()
        val current = mutableState.value
        val index = current.views.indexOfFirst { it.id == id }
        val remaining = current.views.filterNot { it.id == id }
        val nextId = if (id == current.currentViewId) {
            remaining[index.coerceAtMost(remaining.lastIndex)].id
        } else {
            current.currentViewId
        }
        mutableState.value = PlanningViewsState(remaining, nextId)
        if (id == current.currentViewId) restore(mutableState.value.currentView)
        return true
    }

    @Synchronized
    fun captureCurrent() {
        val current = mutableState.value
        mutableState.value = current.copy(
            views = current.views.map { view ->
                if (view.id == current.currentViewId) {
                    view.copy(
                        normalRoute = normalRoute.planningSnapshot(),
                        capitalRoute = capitalRoute.planningSnapshot(),
                    )
                } else {
                    view
                }
            },
        )
    }

    private fun restore(view: PlanningView) {
        normalRoute.restorePlanningSnapshot(view.normalRoute)
        capitalRoute.restorePlanningSnapshot(view.capitalRoute)
    }

    private fun nextDefaultLabel(views: List<PlanningView>): String {
        var index = 1
        while (views.any { it.label.equals("View $index", ignoreCase = true) }) index++
        return "View $index"
    }

    private fun normalizeLabel(value: String): String? = value.trim()
        .takeIf { it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl) }
}
