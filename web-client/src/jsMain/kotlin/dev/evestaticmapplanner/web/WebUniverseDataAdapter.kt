package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.route.CapitalRouteEngine
import dev.evestaticmapplanner.core.route.NormalNavigationPlanner
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteGraph
import dev.evestaticmapplanner.core.route.RouteGraphBuilder
import dev.evestaticmapplanner.core.route.RouteLink
import dev.evestaticmapplanner.core.route.RouteLinkDirection

data class WebUniverse(
    val metadata: WebPackMetadata,
    val staticData: StaticMapData,
    val ansiblex: List<WebPackAnsiblexDto>,
    val staticRepository: WebStaticMapRepository,
    val searchRepository: WebSystemSearchRepository,
    val routeGraph: RouteGraph,
    val normalPlanner: NormalNavigationPlanner,
    val jumpCandidates: CapitalJumpCandidateProvider,
    val capitalEngine: CapitalRouteEngine,
    val scene: ProjectedMapScene,
) {
    val systemsById: Map<Int, SolarSystem> = staticData.systems.associateBy(SolarSystem::id)
    val regionsById: Map<Int, Region> = staticData.regions.associateBy(Region::id)
    val constellationsById: Map<Int, Constellation> = staticData.constellations.associateBy(Constellation::id)
    val stargateCountBySystemId: Map<Int, Int> = buildMap {
        staticData.connections.forEach { link ->
            put(link.firstSystemId, (get(link.firstSystemId) ?: 0) + 1)
            put(link.secondSystemId, (get(link.secondSystemId) ?: 0) + 1)
        }
    }
}

internal enum class SharedMarkerLocationAvailability {
    POSITIONED,
    UNPOSITIONED,
    UNKNOWN_SYSTEM,
}

internal fun WebUniverse.sharedMarkerLocationAvailability(systemId: Int): SharedMarkerLocationAvailability = when {
    systemId !in systemsById -> SharedMarkerLocationAvailability.UNKNOWN_SYSTEM
    systemId !in scene.nodesById -> SharedMarkerLocationAvailability.UNPOSITIONED
    else -> SharedMarkerLocationAvailability.POSITIONED
}

data class WebPackMetadata(val packVersion: String, val desktopAppVersion: String, val sdeBuild: Long)

class WebStaticMapRepository(private val data: StaticMapData) : StaticMapRepository {
    override fun load(): StaticMapData = data
}

class WebSystemSearchRepository(systems: Collection<SolarSystem>) : SystemSearchRepository {
    private val systems = systems.sortedWith(compareBy<SolarSystem>({ it.name.lowercase() }, SolarSystem::id))
    private val systemsById = systems.associateBy(SolarSystem::id)

    override fun searchSystems(query: String, limit: Int): List<SolarSystem> {
        require(limit in 1..100) { "Search limit must be between 1 and 100" }
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        normalized.toIntOrNull()?.takeIf { it > 0 }?.let { return listOfNotNull(systemsById[it]) }
        return systems.asSequence()
            .filter { it.name.contains(normalized, ignoreCase = true) }
            .sortedWith(
                compareBy<SolarSystem>(
                    { !it.name.equals(normalized, ignoreCase = true) },
                    { !it.name.startsWith(normalized, ignoreCase = true) },
                    { it.name.lowercase() },
                    SolarSystem::id,
                ),
            )
            .take(limit)
            .toList()
    }
}

object WebUniverseDataAdapter {
    fun adapt(document: WebPackDocumentDto): WebUniverse {
        require(document.schemaVersion == 1) { "Unsupported Web Pack schema ${document.schemaVersion}; expected 1" }
        val systems = document.systems.map { dto ->
            SolarSystem(
                id = dto.id,
                constellationId = dto.constellationId,
                regionId = dto.regionId,
                name = dto.name,
                securityStatus = dto.securityStatus,
                securityClass = null,
                position = UniversePosition(dto.x, dto.y, dto.z),
                schematicPosition = if (dto.officialX != null && dto.officialY != null) {
                    SchematicPosition(dto.officialX, dto.officialY)
                } else {
                    require(dto.officialX == null && dto.officialY == null) { "Official position must contain both x and y" }
                    null
                },
                radius = 0.0,
                factionId = null,
                wormholeClassId = dto.effectiveWormholeClassId,
                effectiveWormholeClassId = dto.effectiveWormholeClassId,
            )
        }
        val systemsByRegion = systems.groupBy(SolarSystem::regionId)
        val systemsByConstellation = systems.groupBy(SolarSystem::constellationId)
        val regions = document.regions.map { dto ->
            Region(dto.id, dto.name, centroid(systemsByRegion[dto.id].orEmpty()), null)
        }
        val constellations = document.constellations.map { dto ->
            Constellation(dto.id, dto.regionId, dto.name, centroid(systemsByConstellation[dto.id].orEmpty()), null)
        }
        val staticData = StaticMapData(
            systems = systems,
            connections = document.stargates.map { StargateConnection(it.firstSystemId, it.secondSystemId) },
            regions = regions,
            constellations = constellations,
        )
        val routeLinks = document.ansiblex.map { link ->
            RouteLink(
                connectionId = RouteConnectionId("ansiblex:${link.id}"),
                firstSystemId = link.firstSystemId,
                secondSystemId = link.secondSystemId,
                direction = when (link.direction) {
                    WebPackAnsiblexDirectionDto.BIDIRECTIONAL -> RouteLinkDirection.BIDIRECTIONAL
                    WebPackAnsiblexDirectionDto.FIRST_TO_SECOND -> RouteLinkDirection.FIRST_TO_SECOND
                    WebPackAnsiblexDirectionDto.SECOND_TO_FIRST -> RouteLinkDirection.SECOND_TO_FIRST
                },
                type = RouteEdgeType.ANSIBLEX,
                enabled = link.enabled,
            )
        }
        val repository = WebStaticMapRepository(staticData)
        val search = WebSystemSearchRepository(systems)
        val candidates = CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(systems))
        return WebUniverse(
            metadata = WebPackMetadata(document.packVersion, document.desktopAppVersion, document.sdeBuild),
            staticData = staticData,
            ansiblex = document.ansiblex,
            staticRepository = repository,
            searchRepository = search,
            routeGraph = RouteGraphBuilder.build(staticData, routeLinks),
            normalPlanner = NormalNavigationPlanner(),
            jumpCandidates = candidates,
            capitalEngine = CapitalRouteEngine(candidates),
            scene = MapSceneBuilder().build(staticData, OfficialPosition2DProjection),
        )
    }

    private fun centroid(systems: List<SolarSystem>): UniversePosition {
        require(systems.isNotEmpty()) { "Every published Region and Constellation must contain a solar system" }
        return UniversePosition(
            systems.sumOf { it.position.x } / systems.size,
            systems.sumOf { it.position.y } / systems.size,
            systems.sumOf { it.position.z } / systems.size,
        )
    }
}
