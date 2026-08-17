package dev.evestaticmapplanner.jump

import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlayCollection
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class JumpOverlayViewModel(
    private val staticMapRepository: StaticMapRepository,
    private val searchRepository: SystemSearchRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val calculationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val searchDebounceMillis: Long = 180,
) {
    private val mutableState = MutableStateFlow(JumpOverlayUiState())
    val state: StateFlow<JumpOverlayUiState> = mutableState.asStateFlow()
    private var systemsById: Map<Int, SolarSystem> = emptyMap()
    private var collection: JumpRangeOverlayCollection? = null
    private var searchJob: Job? = null
    private val nextId = AtomicLong(1)

    init {
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { staticMapRepository.load() }
            }.onSuccess { data ->
                systemsById = data.systems.associateBy(SolarSystem::id)
                collection = JumpRangeOverlayCollection(
                    CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(data.systems)),
                )
                mutableState.update { it.copy(isLoading = false) }
            }.onFailure { error ->
                mutableState.update { it.copy(isLoading = false, error = error.message ?: "Unable to load jump overlay data") }
            }
        }
    }

    fun updateOriginQuery(query: String) {
        mutableState.update { it.copy(originQuery = query, selectedOrigin = null, originResults = emptyList()) }
        searchJob?.cancel()
        searchJob = scheduleSearch(query)
    }

    fun selectOrigin(system: SolarSystem) {
        searchJob?.cancel()
        mutableState.update { it.copy(originQuery = system.name, selectedOrigin = system, originResults = emptyList()) }
    }

    fun updateManualRange(text: String) {
        mutableState.update { it.copy(manualRangeText = text, error = null) }
    }

    fun addSelectedOrigin() {
        val origin = mutableState.value.selectedOrigin ?: return
        addForSystem(origin.id)
    }

    fun addForSystem(systemId: Int) = calculateMutation {
        val system = systemsById[systemId] ?: error("Unknown solar system: $systemId")
        val profile = currentProfile("overlay-${nextId.get()}")
        add(
            id = "overlay-${nextId.getAndIncrement()}",
            originSystemId = systemId,
            profile = profile,
            label = "${system.name} · ${profile.maxRangeLy} LY",
        )
    }

    fun remove(id: String) = publishMutation { remove(id) }

    fun setEnabled(id: String, enabled: Boolean) {
        if (!enabled) {
            mutableState.update { it.copy(intersectionOverlayIds = it.intersectionOverlayIds - id) }
        }
        publishMutation { setEnabled(id, enabled) }
    }

    fun updateWithCurrentRange(id: String) = calculateMutation {
        val current = all().singleOrNull { it.id == id } ?: return@calculateMutation
        val profile = currentProfile("$id-profile")
        val originName = systemsById[current.originSystemId]?.name ?: current.originSystemId.toString()
        updateProfile(id, profile, "$originName · ${profile.maxRangeLy} LY")
    }

    fun clear() = publishMutation { clear() }

    fun toggleIntersectionSelection(id: String, selected: Boolean) {
        mutableState.update {
            val ids = if (selected) it.intersectionOverlayIds + id else it.intersectionOverlayIds - id
            it.copy(intersectionOverlayIds = ids)
        }
        publish()
    }

    fun close() = scope.cancel()

    private fun calculateMutation(block: JumpRangeOverlayCollection.() -> Unit) {
        val overlays = collection ?: return
        mutableState.update { it.copy(isCalculating = true, error = null) }
        scope.launch {
            runCatching { withContext(calculationDispatcher) { overlays.block() } }
                .onSuccess { publish(isCalculating = false) }
                .onFailure { error ->
                    mutableState.update { it.copy(isCalculating = false, error = error.message ?: "Jump overlay calculation failed") }
                }
        }
    }

    private fun publishMutation(block: JumpRangeOverlayCollection.() -> Unit) {
        val overlays = collection ?: return
        overlays.block()
        publish()
    }

    private fun publish(isCalculating: Boolean = mutableState.value.isCalculating) {
        val overlays = collection ?: return
        mutableState.update { current ->
            val validSelected = current.intersectionOverlayIds.intersect(overlays.all().mapTo(mutableSetOf()) { it.id })
            current.copy(
                isCalculating = isCalculating,
                overlays = overlays.all(),
                coverageCounts = overlays.coverageCounts(),
                intersectionOverlayIds = validSelected,
                intersectionSystemIds = if (validSelected.isEmpty()) emptySet() else overlays.intersection(validSelected),
            )
        }
    }

    private fun currentProfile(id: String): JumpProfile {
        val value = mutableState.value.manualRangeText.trim().toDoubleOrNull()
            ?: error("Manual maximum LY must be a number")
        return JumpProfile.manual(value, id)
    }

    private fun scheduleSearch(query: String): Job = scope.launch {
        if (query.isBlank()) return@launch
        delay(searchDebounceMillis)
        val results = withContext(ioDispatcher) { searchRepository.searchSystems(query, 20) }
        mutableState.update { it.copy(originResults = results) }
    }
}
