package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData

data class ProjectedSystemNode(
    val system: SolarSystem,
    val position: MapPoint,
    val isStargateConnected: Boolean,
)

data class ProjectedRegion(
    val id: Int,
    val name: String,
    val canonicalAnchor: MapPoint,
    val bounds: MapBounds,
    val projectedMemberCount: Int,
)

data class ProjectedConstellation(
    val id: Int,
    val regionId: Int,
    val name: String,
    val canonicalAnchor: MapPoint,
    val bounds: MapBounds,
    val projectedMemberCount: Int,
)

data class ProjectedStargateEdge(
    val firstSystemId: Int,
    val secondSystemId: Int,
    val first: MapPoint,
    val second: MapPoint,
) {
    val bounds: MapBounds = MapBounds.between(first, second)
}

class ProjectedMapScene internal constructor(
    val projectionId: MapProjectionId,
    val nodes: List<ProjectedSystemNode>,
    val edges: List<ProjectedStargateEdge>,
    val sceneBounds: MapBounds,
    val defaultFitBounds: MapBounds,
    val omittedSystemIds: Set<Int>,
    val spatialIndex: SystemSpatialIndex,
    val regions: List<ProjectedRegion>,
    val constellations: List<ProjectedConstellation>,
) {
    val nodesById: Map<Int, ProjectedSystemNode> = nodes.associateBy { it.system.id }
    val regionsById: Map<Int, ProjectedRegion> = regions.associateBy(ProjectedRegion::id)
    val constellationsById: Map<Int, ProjectedConstellation> = constellations.associateBy(ProjectedConstellation::id)
}

class MapSceneBuilder {
    fun build(data: StaticMapData, projection: MapProjection): ProjectedMapScene {
        val connectedSystemIds = data.connections.flatMapTo(mutableSetOf()) {
            listOf(it.firstSystemId, it.secondSystemId)
        }
        val projectedById = LinkedHashMap<Int, MapPoint>(data.systems.size)
        val omitted = mutableSetOf<Int>()
        val nodes = data.systems.sortedBy { it.id }.mapNotNull { system ->
            val point = projection.project(system)
            if (point == null) {
                omitted += system.id
                null
            } else {
                projectedById[system.id] = point
                ProjectedSystemNode(system, point, system.id in connectedSystemIds)
            }
        }
        require(nodes.isNotEmpty()) { "Projection ${projection.id} produced no systems" }
        val edges = data.connections.mapNotNull { connection ->
            val first = projectedById[connection.firstSystemId] ?: return@mapNotNull null
            val second = projectedById[connection.secondSystemId] ?: return@mapNotNull null
            ProjectedStargateEdge(
                firstSystemId = connection.firstSystemId,
                secondSystemId = connection.secondSystemId,
                first = first,
                second = second,
            )
        }
        val sceneBounds = MapBounds.fromPoints(projectedById.values)
        val connectedPoints = nodes.asSequence()
            .filter(ProjectedSystemNode::isStargateConnected)
            .map(ProjectedSystemNode::position)
            .toList()
        val defaultFitBounds = if (connectedPoints.isNotEmpty()) {
            MapBounds.fromPoints(connectedPoints)
        } else {
            sceneBounds
        }
        val positionsByRegionId = nodes.groupBy(
            keySelector = { it.system.regionId },
            valueTransform = ProjectedSystemNode::position,
        )
        val positionsByConstellationId = nodes.groupBy(
            keySelector = { it.system.constellationId },
            valueTransform = ProjectedSystemNode::position,
        )
        val regions = data.regions.sortedBy { it.id }.mapNotNull { region ->
            val positions = positionsByRegionId[region.id].orEmpty()
            positions.takeIf(List<MapPoint>::isNotEmpty)?.let {
                ProjectedRegion(
                    id = region.id,
                    name = region.name,
                    canonicalAnchor = GeometricMedian.calculate(it),
                    bounds = MapBounds.fromPoints(it),
                    projectedMemberCount = it.size,
                )
            }
        }
        val constellations = data.constellations.sortedBy { it.id }.mapNotNull { constellation ->
            val positions = positionsByConstellationId[constellation.id].orEmpty()
            positions.takeIf(List<MapPoint>::isNotEmpty)?.let {
                ProjectedConstellation(
                    id = constellation.id,
                    regionId = constellation.regionId,
                    name = constellation.name,
                    canonicalAnchor = GeometricMedian.calculate(it),
                    bounds = MapBounds.fromPoints(it),
                    projectedMemberCount = it.size,
                )
            }
        }
        return ProjectedMapScene(
            projectionId = projection.id,
            nodes = nodes,
            edges = edges,
            sceneBounds = sceneBounds,
            defaultFitBounds = defaultFitBounds,
            omittedSystemIds = omitted,
            spatialIndex = SystemSpatialIndex.build(projectedById),
            regions = regions,
            constellations = constellations,
        )
    }
}

class MapSceneCache(
    private val data: StaticMapData,
    private val builder: MapSceneBuilder = MapSceneBuilder(),
) {
    private val scenes = mutableMapOf<MapProjectionId, ProjectedMapScene>()

    @Synchronized
    fun get(projectionId: MapProjectionId): ProjectedMapScene =
        scenes.getOrPut(projectionId) { builder.build(data, projectionFor(projectionId)) }

    @Synchronized
    fun cachedProjectionIds(): Set<MapProjectionId> = scenes.keys.toSet()
}
