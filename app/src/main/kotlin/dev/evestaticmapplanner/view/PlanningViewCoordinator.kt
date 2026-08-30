package dev.evestaticmapplanner.view

import dev.evestaticmapplanner.capital.CapitalRoutePlanningPort
import dev.evestaticmapplanner.capital.CapitalRoutePlanningSnapshot
import dev.evestaticmapplanner.route.NormalRoutePlanningPort
import dev.evestaticmapplanner.route.NormalRoutePlanningSnapshot
import dev.evestaticmapplanner.data.view.PlanningViewRecord
import dev.evestaticmapplanner.data.view.PlanningViewRepository
import dev.evestaticmapplanner.data.view.PlanningViewsRecord
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
    val selectedRouteActionTargets: Map<String, String> = emptyMap(),
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
    private val repository: PlanningViewRepository? = null,
    initialState: PlanningViewsState? = null,
) {
    private val mutableState = MutableStateFlow(
        initialState ?: repository?.load()?.toState() ?: DEFAULT_STATE,
    )
    val state: StateFlow<PlanningViewsState> = mutableState.asStateFlow()

    init {
        restore(mutableState.value.currentView)
    }

    @Synchronized
    fun createView(requestedLabel: String? = null): PlanningViewId {
        captureCurrent()
        val current = mutableState.value
        val label = if (requestedLabel == null) nextDefaultLabel(current.views) else {
            normalizeLabel(requestedLabel) ?: throw IllegalArgumentException("Invalid View label")
        }
        if (current.views.any { it.label.equals(label, ignoreCase = true) }) {
            throw IllegalArgumentException("View label already exists")
        }
        val view = PlanningView(newId(), label)
        mutableState.value = PlanningViewsState(current.views + view, view.id)
        restore(view)
        persist()
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
        persist()
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
        persist()
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
        persist()
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
        persist()
    }

    private fun restore(view: PlanningView) {
        normalRoute.restorePlanningSnapshot(view.normalRoute)
        capitalRoute.restorePlanningSnapshot(view.capitalRoute)
    }

    @Synchronized
    fun selectRouteActionTarget(selectorKey: String, targetId: String?) {
        require(selectorKey.isNotBlank())
        val current = mutableState.value
        mutableState.value = current.copy(
            views = current.views.map { view ->
                if (view.id != current.currentViewId) view else view.copy(
                    selectedRouteActionTargets = if (targetId == null) {
                        view.selectedRouteActionTargets - selectorKey
                    } else {
                        view.selectedRouteActionTargets + (selectorKey to targetId)
                    },
                )
            },
        )
        persist()
    }

    private fun nextDefaultLabel(views: List<PlanningView>): String {
        var index = 1
        while (views.any { it.label.equals("View $index", ignoreCase = true) }) index++
        return "View $index"
    }

    private fun normalizeLabel(value: String): String? = value.trim()
        .takeIf { it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl) }

    private fun persist() {
        repository?.save(mutableState.value.toRecord())
    }

    private fun PlanningViewsRecord.toState() = PlanningViewsState(
        views = views.map { record ->
            PlanningView(
                id = PlanningViewId(record.id),
                label = record.label,
                normalRoute = NormalRoutePlanningSnapshot(
                    record.normalFromSystemId,
                    record.normalToSystemId,
                    record.normalUseAnsiblex,
                    record.normalCalculated,
                ),
                capitalRoute = CapitalRoutePlanningSnapshot(
                    record.capitalFromSystemId,
                    record.capitalToSystemId,
                    record.capitalRangeText,
                    record.capitalCalculated,
                ),
                selectedRouteActionTargets = record.selectedRouteActionTargets,
            )
        },
        currentViewId = PlanningViewId(currentViewId),
    )

    private fun PlanningViewsState.toRecord() = PlanningViewsRecord(
        views = views.map { view ->
            PlanningViewRecord(
                id = view.id.value,
                label = view.label,
                normalFromSystemId = view.normalRoute.fromSystemId,
                normalToSystemId = view.normalRoute.toSystemId,
                normalUseAnsiblex = view.normalRoute.useAnsiblex,
                normalCalculated = view.normalRoute.calculated,
                capitalFromSystemId = view.capitalRoute.fromSystemId,
                capitalToSystemId = view.capitalRoute.toSystemId,
                capitalRangeText = view.capitalRoute.manualRangeText,
                capitalCalculated = view.capitalRoute.calculated,
                selectedRouteActionTargets = view.selectedRouteActionTargets,
            )
        },
        currentViewId = currentViewId.value,
    )

    private companion object {
        val DEFAULT_STATE = PlanningViewsState(
            listOf(PlanningView(PlanningViewId("view-1"), "View 1")),
            PlanningViewId("view-1"),
        )
    }
}
