package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.SolarSystem
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

data class Real3DWorldNode(
    val system: SolarSystem,
    val position: MapPoint3,
    val isStargateConnected: Boolean,
)

data class Real3DWorldEdge(
    val firstSystemId: Int,
    val secondSystemId: Int,
    val first: MapPoint3,
    val second: MapPoint3,
)

data class Real3DHierarchyAnchor(
    val id: Int,
    val name: String,
    val position: MapPoint3,
    val memberCount: Int,
)

class Real3DStaticGeometry private constructor(
    val nodes: List<Real3DWorldNode>,
    val edges: List<Real3DWorldEdge>,
    val regions: List<Real3DHierarchyAnchor>,
    val constellations: List<Real3DHierarchyAnchor>,
) {
    val nodesById: Map<Int, Real3DWorldNode> = nodes.associateBy { it.system.id }
    val fitPoints: List<MapPoint3> = nodes.asSequence()
        .filter(Real3DWorldNode::isStargateConnected)
        .map(Real3DWorldNode::position)
        .toList()
        .ifEmpty { nodes.map(Real3DWorldNode::position) }

    companion object {
        fun from(scene: ProjectedMapScene): Real3DStaticGeometry {
            val nodes = scene.nodes.map { node ->
                Real3DWorldNode(
                    system = node.system,
                    position = MapPoint3.fromUniverse(node.system.position),
                    isStargateConnected = node.isStargateConnected,
                )
            }
            val nodesById = nodes.associateBy { it.system.id }
            val edges = scene.edges.mapNotNull { edge ->
                val first = nodesById[edge.firstSystemId]?.position ?: return@mapNotNull null
                val second = nodesById[edge.secondSystemId]?.position ?: return@mapNotNull null
                Real3DWorldEdge(edge.firstSystemId, edge.secondSystemId, first, second)
            }
            val positionsByRegion = nodes.groupBy(
                keySelector = { it.system.regionId },
                valueTransform = Real3DWorldNode::position,
            )
            val positionsByConstellation = nodes.groupBy(
                keySelector = { it.system.constellationId },
                valueTransform = Real3DWorldNode::position,
            )
            val regions = scene.regions.mapNotNull { region ->
                positionsByRegion[region.id]?.takeIf(List<MapPoint3>::isNotEmpty)?.let { positions ->
                    Real3DHierarchyAnchor(region.id, region.name, GeometricMedian3D.calculate(positions), positions.size)
                }
            }
            val constellations = scene.constellations.mapNotNull { constellation ->
                positionsByConstellation[constellation.id]?.takeIf(List<MapPoint3>::isNotEmpty)?.let { positions ->
                    Real3DHierarchyAnchor(
                        constellation.id,
                        constellation.name,
                        GeometricMedian3D.calculate(positions),
                        positions.size,
                    )
                }
            }
            return Real3DStaticGeometry(nodes = nodes, edges = edges, regions = regions, constellations = constellations)
        }
    }
}

class Real3DProjectedNode internal constructor(
    val node: Real3DWorldNode,
    var screen: MapPoint,
    var depth: Double,
    var radiusPx: Float,
)

class Real3DProjectedEdge internal constructor(
    val edge: Real3DWorldEdge,
    var first: MapPoint,
    var second: MapPoint,
    var averageDepth: Double,
    var alpha: Float,
)

class Real3DProjectedHierarchyAnchor internal constructor(
    val anchor: Real3DHierarchyAnchor,
    var screen: MapPoint,
    var depth: Double,
)

data class Real3DFrame(
    /** Painter order is far-to-near so nearer systems remain visually dominant. */
    val nodesFarToNear: List<Real3DProjectedNode>,
    val edges: List<Real3DProjectedEdge>,
    val projectedBySystemId: Map<Int, Real3DProjectedNode>,
    val regions: List<Real3DProjectedHierarchyAnchor>,
    val constellations: List<Real3DProjectedHierarchyAnchor>,
)

object Real3DFrameProjector {
    fun project(
        geometry: Real3DStaticGeometry,
        camera: Real3DCamera,
        viewportSize: MapSize,
    ): Real3DFrame {
        return Real3DFrameProjectionWorkspace().project(geometry, camera, viewportSize)
    }

    internal fun nodeRadius(depth: Double, referenceDistance: Double): Float {
        val scale = sqrt((referenceDistance / depth).coerceAtLeast(0.01))
        return (BASE_NODE_RADIUS_PX * scale).coerceIn(MIN_NODE_RADIUS_PX, MAX_NODE_RADIUS_PX).toFloat()
    }

    internal fun edgeAlpha(depth: Double, referenceDistance: Double): Float {
        val relativeDepth = (depth / referenceDistance.coerceAtLeast(MIN_REAL_3D_CAMERA_DISTANCE)).coerceIn(0.25, 3.0)
        return (0.62 / sqrt(relativeDepth)).coerceIn(0.16, 0.68).toFloat()
    }
}

/**
 * Reuses the large frame collections while the camera moves. The returned frame is a live view of
 * this workspace and must not be retained after the next [project] call.
 */
class Real3DFrameProjectionWorkspace {
    private val nodesFarToNear = ArrayList<Real3DProjectedNode>()
    private val edges = ArrayList<Real3DProjectedEdge>()
    private val projectedBySystemId = HashMap<Int, Real3DProjectedNode>()
    private val regions = ArrayList<Real3DProjectedHierarchyAnchor>()
    private val constellations = ArrayList<Real3DProjectedHierarchyAnchor>()
    private val nodePool = HashMap<Int, Real3DProjectedNode>()
    private val edgePool = HashMap<Long, Real3DProjectedEdge>()
    private val regionPool = HashMap<Int, Real3DProjectedHierarchyAnchor>()
    private val constellationPool = HashMap<Int, Real3DProjectedHierarchyAnchor>()
    private val frame = Real3DFrame(nodesFarToNear, edges, projectedBySystemId, regions, constellations)

    fun project(
        geometry: Real3DStaticGeometry,
        camera: Real3DCamera,
        viewportSize: MapSize,
        edgeVisibility: ((Real3DWorldEdge) -> Boolean)? = null,
    ): Real3DFrame {
        nodesFarToNear.clear()
        edges.clear()
        projectedBySystemId.clear()
        regions.clear()
        constellations.clear()
        val projector = AllocationLightFrameProjector(camera, viewportSize)
        val projected = MutableProjectedPoint()
        geometry.nodes.forEach { node ->
            if (!projector.project(node.position, projected)) return@forEach
            val pooled = nodePool.getOrPut(node.system.id) {
                Real3DProjectedNode(node, MapPoint(projected.x, projected.y), projected.depth, 0f)
            }
            pooled.screen = MapPoint(projected.x, projected.y)
            pooled.depth = projected.depth
            pooled.radiusPx = Real3DFrameProjector.nodeRadius(projected.depth, camera.distance)
            nodesFarToNear += pooled
        }
        nodesFarToNear.sortWith(compareByDescending<Real3DProjectedNode> { it.depth }.thenBy { it.node.system.id })
        nodesFarToNear.forEach { projectedBySystemId[it.node.system.id] = it }
        val first = MutableProjectedPoint()
        val second = MutableProjectedPoint()
        geometry.edges.forEach { edge ->
            if (edgeVisibility != null && !edgeVisibility(edge)) return@forEach
            if (!projector.projectSegment(edge.first, edge.second, first, second)) return@forEach
            val averageDepth = (first.depth + second.depth) / 2.0
            val key = (edge.firstSystemId.toLong() shl 32) xor (edge.secondSystemId.toLong() and 0xFFFF_FFFFL)
            val pooled = edgePool.getOrPut(key) {
                Real3DProjectedEdge(edge, MapPoint(first.x, first.y), MapPoint(second.x, second.y), averageDepth, 0f)
            }
            pooled.first = MapPoint(first.x, first.y)
            pooled.second = MapPoint(second.x, second.y)
            pooled.averageDepth = averageDepth
            pooled.alpha = Real3DFrameProjector.edgeAlpha(averageDepth, camera.distance)
            edges += pooled
        }
        geometry.regions.forEach { anchor ->
            if (projector.project(anchor.position, projected)) {
                val pooled = regionPool.getOrPut(anchor.id) {
                    Real3DProjectedHierarchyAnchor(anchor, MapPoint(projected.x, projected.y), projected.depth)
                }
                pooled.screen = MapPoint(projected.x, projected.y)
                pooled.depth = projected.depth
                regions += pooled
            }
        }
        geometry.constellations.forEach { anchor ->
            if (projector.project(anchor.position, projected)) {
                val pooled = constellationPool.getOrPut(anchor.id) {
                    Real3DProjectedHierarchyAnchor(anchor, MapPoint(projected.x, projected.y), projected.depth)
                }
                pooled.screen = MapPoint(projected.x, projected.y)
                pooled.depth = projected.depth
                constellations += pooled
            }
        }
        return frame
    }
}

private class MutableProjectedPoint(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var depth: Double = 0.0,
)

/** Allocation-light scalar projector dedicated to the high-frequency base-map frame. */
private class AllocationLightFrameProjector(
    private val camera: Real3DCamera,
    private val viewportSize: MapSize,
) {
    private val basis = camera.basis()
    private val cameraPosition = camera.position()
    private val focalLength = viewportSize.height /
        (2.0 * tan(camera.verticalFieldOfViewDegrees * PI / 360.0))
    private val firstView = MutableProjectedPoint()
    private val secondView = MutableProjectedPoint()

    fun project(point: MapPoint3, output: MutableProjectedPoint): Boolean {
        worldToView(point, output)
        return projectView(output, output, cullToViewport = true)
    }

    fun projectSegment(
        first: MapPoint3,
        second: MapPoint3,
        firstOutput: MutableProjectedPoint,
        secondOutput: MutableProjectedPoint,
    ): Boolean {
        worldToView(first, firstView)
        worldToView(second, secondView)
        if (firstView.depth < camera.nearPlane && secondView.depth < camera.nearPlane) return false
        if (firstView.depth > camera.farPlane && secondView.depth > camera.farPlane) return false
        if (firstView.depth < camera.nearPlane) interpolateAtDepth(firstView, secondView, camera.nearPlane)
        if (secondView.depth < camera.nearPlane) interpolateAtDepth(secondView, firstView, camera.nearPlane)
        if (firstView.depth > camera.farPlane) interpolateAtDepth(firstView, secondView, camera.farPlane)
        if (secondView.depth > camera.farPlane) interpolateAtDepth(secondView, firstView, camera.farPlane)
        if (!projectView(firstView, firstOutput, cullToViewport = false)) return false
        if (!projectView(secondView, secondOutput, cullToViewport = false)) return false
        return maxOf(firstOutput.x, secondOutput.x) >= 0.0 &&
            minOf(firstOutput.x, secondOutput.x) <= viewportSize.width &&
            maxOf(firstOutput.y, secondOutput.y) >= 0.0 &&
            minOf(firstOutput.y, secondOutput.y) <= viewportSize.height
    }

    private fun worldToView(point: MapPoint3, output: MutableProjectedPoint) {
        val relativeX = point.x - cameraPosition.x
        val relativeY = point.y - cameraPosition.y
        val relativeZ = point.z - cameraPosition.z
        output.x = relativeX * basis.right.x + relativeY * basis.right.y + relativeZ * basis.right.z
        output.y = relativeX * basis.up.x + relativeY * basis.up.y + relativeZ * basis.up.z
        output.depth = relativeX * basis.forward.x + relativeY * basis.forward.y + relativeZ * basis.forward.z
    }

    private fun projectView(
        input: MutableProjectedPoint,
        output: MutableProjectedPoint,
        cullToViewport: Boolean,
    ): Boolean {
        if (input.depth < camera.nearPlane || input.depth > camera.farPlane) return false
        val screenX = viewportSize.width / 2.0 + input.x * focalLength / input.depth
        val screenY = viewportSize.height / 2.0 - input.y * focalLength / input.depth
        if (cullToViewport && (screenX < 0.0 || screenX > viewportSize.width || screenY < 0.0 || screenY > viewportSize.height)) {
            return false
        }
        output.x = screenX
        output.y = screenY
        output.depth = input.depth
        return true
    }

    private fun interpolateAtDepth(
        target: MutableProjectedPoint,
        other: MutableProjectedPoint,
        depth: Double,
    ) {
        val amount = (depth - target.depth) / (other.depth - target.depth)
        target.x += (other.x - target.x) * amount
        target.y += (other.y - target.y) * amount
        target.depth = depth
    }
}

object Real3DPicker {
    fun nearestSystem(
        geometry: Real3DStaticGeometry,
        camera: Real3DCamera,
        viewportSize: MapSize,
        screenPosition: MapPoint,
        radiusPx: Double,
    ): Int? {
        require(radiusPx.isFinite() && radiusPx >= 0.0)
        if (viewportSize.isEmpty) return null
        val projector = Real3DProjector(camera, viewportSize)
        val maximumDistanceSquared = radiusPx * radiusPx
        var nearestSystemId: Int? = null
        var nearestDepth = Double.POSITIVE_INFINITY
        geometry.nodes.forEach { node ->
            val projected = projector.project(node.position, cullToViewport = false) ?: return@forEach
            if (projected.screen.distanceSquaredTo(screenPosition) > maximumDistanceSquared) return@forEach
            if (projected.depth < nearestDepth ||
                (projected.depth == nearestDepth && node.system.id < (nearestSystemId ?: Int.MAX_VALUE))
            ) {
                nearestSystemId = node.system.id
                nearestDepth = projected.depth
            }
        }
        return nearestSystemId
    }
}

private const val BASE_NODE_RADIUS_PX = 2.4
private const val MIN_NODE_RADIUS_PX = 1.35
private const val MAX_NODE_RADIUS_PX = 5.25
