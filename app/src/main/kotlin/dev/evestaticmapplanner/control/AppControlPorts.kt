package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.core.route.CapitalRouteEngine
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.NormalRouteEngine
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteGraphBuilder
import dev.evestaticmapplanner.core.route.RouteOptions
import dev.evestaticmapplanner.map.MapViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RepositorySystemReadPort(
    private val searchRepository: SystemSearchRepository,
    private val universeRepository: UniverseRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SystemReadPort {
    override suspend fun searchSystems(query: String, limit: Int): List<SystemSummaryDto> = withContext(ioDispatcher) {
        searchRepository.searchSystems(query, limit).map(SolarSystem::toSummary)
    }

    override suspend fun getSystemInfo(systemId: Int): SystemInfoDto? = withContext(ioDispatcher) {
        universeRepository.getSystemDetails(systemId)?.let { details ->
            SystemInfoDto(
                system = details.system.toSummary(),
                regionName = details.region.name,
                constellationName = details.constellation.name,
                x = details.system.position.x,
                y = details.system.position.y,
                z = details.system.position.z,
                stargateCount = details.stargateCount,
            )
        }
    }
}

class ExistingPlanningPorts(
    private val staticMapRepository: StaticMapRepository,
    private val ansiblexRepository: AnsiblexRepository?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val calculationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RoutePlanningPort, JumpPlanningPort {
    private val initialization = Mutex()
    @Volatile private var candidateProvider: CapitalJumpCandidateProvider? = null

    override suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): RouteCalculationOutcome {
        val (data, enabledSnapshot) = withContext(ioDispatcher) {
            staticMapRepository.load() to if (useAnsiblex) {
                ansiblexRepository?.getAll()?.filter { it.enabled }.orEmpty()
            } else {
                emptyList()
            }
        }
        return withContext(calculationDispatcher) {
            NormalRouteEngine().calculate(
                RouteGraphBuilder.build(data, enabledSnapshot),
                startSystemId,
                destinationSystemId,
                RouteOptions(useAnsiblex),
            )
        }
    }

    override suspend fun calculateCapitalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): CapitalRouteOutcome = withContext(calculationDispatcher) {
        CapitalRouteEngine(provider()).calculate(
            startSystemId,
            destinationSystemId,
            JumpProfile.manual(effectiveRangeLy, "control-capital"),
        )
    }

    override suspend fun calculateJumpRange(originSystemId: Int, effectiveRangeLy: Double) =
        withContext(calculationDispatcher) {
            provider().reachableFrom(
                originSystemId,
                JumpProfile.manual(effectiveRangeLy, "control-jump-range"),
            )
        }

    private suspend fun provider(): CapitalJumpCandidateProvider {
        candidateProvider?.let { return it }
        return initialization.withLock {
            candidateProvider ?: withContext(ioDispatcher) {
                CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(staticMapRepository.load().systems))
            }.also { candidateProvider = it }
        }
    }
}

class MapViewportControlAdapter(
    private val mapViewModel: MapViewModel,
    private val commitDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewportControlPort {
    override suspend fun focusSystem(systemId: Int): ViewportOperationOutcome = withContext(commitDispatcher) {
        when {
            mapViewModel.state.value.isLoading || mapViewModel.state.value.scene == null ||
                mapViewModel.state.value.canvasSize.isEmpty -> ViewportOperationOutcome.APP_NOT_READY
            mapViewModel.focusSystemForControl(systemId) -> ViewportOperationOutcome.COMPLETED
            else -> ViewportOperationOutcome.NOT_FOUND
        }
    }

    override suspend fun fitSystems(systemIds: Set<Int>): ViewportOperationOutcome = withContext(commitDispatcher) {
        when {
            mapViewModel.state.value.isLoading || mapViewModel.state.value.scene == null ||
                mapViewModel.state.value.canvasSize.isEmpty -> ViewportOperationOutcome.APP_NOT_READY
            mapViewModel.fitSystemsForControl(systemIds) -> ViewportOperationOutcome.COMPLETED
            else -> ViewportOperationOutcome.NOT_FOUND
        }
    }
}

private fun SolarSystem.toSummary() = SystemSummaryDto(
    systemId = id,
    name = name,
    regionId = regionId,
    constellationId = constellationId,
    securityStatus = securityStatus,
)
