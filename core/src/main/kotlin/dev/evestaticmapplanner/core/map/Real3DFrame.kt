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
    val nameEn: String,
    val position: MapPoint3,
    val memberCount: Int,
    val nameZh: String? = null,
) {
    val name: String get() = nameEn
}

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
                    Real3DHierarchyAnchor(
                        region.id,
                        region.nameEn,
                        GeometricMedian3D.calculate(positions),
                        positions.size,
                        region.nameZh,
                    )
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
    var screenX: Double,
    var screenY: Double,
    var depth: Double,
    var radiusPx: Float,
) {
    internal val mapEntry: Map.Entry<Int, Real3DProjectedNode> = Real3DProjectedNodeMapEntry(this)

    fun snapshotScreenPoint(): MapPoint = MapPoint(screenX, screenY)
}

class Real3DProjectedEdge internal constructor(
    val edge: Real3DWorldEdge,
    var firstScreenX: Double,
    var firstScreenY: Double,
    var secondScreenX: Double,
    var secondScreenY: Double,
    var averageDepth: Double,
    var alpha: Float,
) {
    fun snapshotFirstScreenPoint(): MapPoint = MapPoint(firstScreenX, firstScreenY)

    fun snapshotSecondScreenPoint(): MapPoint = MapPoint(secondScreenX, secondScreenY)
}

class Real3DProjectedHierarchyAnchor internal constructor(
    val anchor: Real3DHierarchyAnchor,
    var screenX: Double,
    var screenY: Double,
    var depth: Double,
) {
    fun snapshotScreenPoint(): MapPoint = MapPoint(screenX, screenY)
}

private class Real3DProjectedNodeMapEntry(
    private val projected: Real3DProjectedNode,
) : Map.Entry<Int, Real3DProjectedNode> {
    override val key: Int get() = projected.node.system.id
    override val value: Real3DProjectedNode get() = projected

    override fun equals(other: Any?): Boolean =
        other is Map.Entry<*, *> && key == other.key && value == other.value

    override fun hashCode(): Int = key.hashCode() xor value.hashCode()

    override fun toString(): String = "$key=$value"
}

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
    private val regions = ArrayList<Real3DProjectedHierarchyAnchor>()
    private val constellations = ArrayList<Real3DProjectedHierarchyAnchor>()
    private var boundGeometry: Real3DStaticGeometry? = null
    private var nodeSlots = arrayOfNulls<Real3DProjectedNode>(0)
    private var edgeSlots = arrayOfNulls<Real3DProjectedEdge>(0)
    private var regionSlots = arrayOfNulls<Real3DProjectedHierarchyAnchor>(0)
    private var constellationSlots = arrayOfNulls<Real3DProjectedHierarchyAnchor>(0)
    private var nodeIndexBySystemId: Map<Int, Int> = emptyMap()
    private var nodeVisibilityGeneration = IntArray(0)
    private var currentGeneration = 0
    private val projectedBySystemId = ProjectedNodeMapView()
    private val frame = Real3DFrame(nodesFarToNear, edges, projectedBySystemId, regions, constellations)
    private val projector = AllocationLightFrameProjector()
    private val projected = MutableProjectedPoint()
    private val first = MutableProjectedPoint()
    private val second = MutableProjectedPoint()

    fun project(
        geometry: Real3DStaticGeometry,
        camera: Real3DCamera,
        viewportSize: MapSize,
        edgeVisibility: ((Real3DWorldEdge) -> Boolean)? = null,
    ): Real3DFrame {
        bindGeometry(geometry)
        nodesFarToNear.clear()
        edges.clear()
        regions.clear()
        constellations.clear()
        val generation = nextGeneration()
        projector.update(camera, viewportSize)
        for (index in geometry.nodes.indices) {
            val node = geometry.nodes[index]
            if (!projector.project(node.position, projected)) continue
            val pooled = nodeSlots[index] ?: Real3DProjectedNode(
                node = node,
                screenX = projected.x,
                screenY = projected.y,
                depth = projected.depth,
                radiusPx = 0f,
            ).also { created ->
                nodeSlots[index] = created
            }
            pooled.screenX = projected.x
            pooled.screenY = projected.y
            pooled.depth = projected.depth
            pooled.radiusPx = Real3DFrameProjector.nodeRadius(projected.depth, camera.distance)
            nodeVisibilityGeneration[index] = generation
            nodesFarToNear.add(pooled)
        }
        nodesFarToNear.sortWith(REAL_3D_PROJECTED_NODE_COMPARATOR)
        for (index in geometry.edges.indices) {
            val edge = geometry.edges[index]
            if (edgeVisibility != null && !edgeVisibility(edge)) continue
            if (!projector.projectSegment(edge.first, edge.second, first, second)) continue
            val averageDepth = (first.depth + second.depth) / 2.0
            val pooled = edgeSlots[index] ?: Real3DProjectedEdge(
                edge = edge,
                firstScreenX = first.x,
                firstScreenY = first.y,
                secondScreenX = second.x,
                secondScreenY = second.y,
                averageDepth = averageDepth,
                alpha = 0f,
            ).also { created ->
                edgeSlots[index] = created
            }
            pooled.firstScreenX = first.x
            pooled.firstScreenY = first.y
            pooled.secondScreenX = second.x
            pooled.secondScreenY = second.y
            pooled.averageDepth = averageDepth
            pooled.alpha = Real3DFrameProjector.edgeAlpha(averageDepth, camera.distance)
            edges.add(pooled)
        }
        for (index in geometry.regions.indices) {
            val anchor = geometry.regions[index]
            if (projector.project(anchor.position, projected)) {
                val pooled = regionSlots[index] ?: Real3DProjectedHierarchyAnchor(
                    anchor = anchor,
                    screenX = projected.x,
                    screenY = projected.y,
                    depth = projected.depth,
                ).also { created ->
                    regionSlots[index] = created
                }
                pooled.screenX = projected.x
                pooled.screenY = projected.y
                pooled.depth = projected.depth
                regions.add(pooled)
            }
        }
        for (index in geometry.constellations.indices) {
            val anchor = geometry.constellations[index]
            if (projector.project(anchor.position, projected)) {
                val pooled = constellationSlots[index] ?: Real3DProjectedHierarchyAnchor(
                    anchor = anchor,
                    screenX = projected.x,
                    screenY = projected.y,
                    depth = projected.depth,
                ).also { created ->
                    constellationSlots[index] = created
                }
                pooled.screenX = projected.x
                pooled.screenY = projected.y
                pooled.depth = projected.depth
                constellations.add(pooled)
            }
        }
        return frame
    }

    private fun bindGeometry(geometry: Real3DStaticGeometry) {
        val existing = boundGeometry
        require(existing == null || existing === geometry) {
            "A Real3DFrameProjectionWorkspace cannot be shared by different static geometries"
        }
        if (existing != null) return
        boundGeometry = geometry
        nodeSlots = arrayOfNulls(geometry.nodes.size)
        edgeSlots = arrayOfNulls(geometry.edges.size)
        regionSlots = arrayOfNulls(geometry.regions.size)
        constellationSlots = arrayOfNulls(geometry.constellations.size)
        nodeVisibilityGeneration = IntArray(geometry.nodes.size)
        nodeIndexBySystemId = HashMap<Int, Int>(geometry.nodes.size * 4 / 3 + 1).apply {
            geometry.nodes.forEachIndexed { index, node -> put(node.system.id, index) }
        }
        nodesFarToNear.ensureCapacity(geometry.nodes.size)
        edges.ensureCapacity(geometry.edges.size)
        regions.ensureCapacity(geometry.regions.size)
        constellations.ensureCapacity(geometry.constellations.size)
    }

    private fun nextGeneration(): Int {
        if (currentGeneration == Int.MAX_VALUE) {
            nodeVisibilityGeneration.fill(0)
            currentGeneration = 1
        } else {
            currentGeneration += 1
        }
        return currentGeneration
    }

    private inner class ProjectedNodeMapView : Map<Int, Real3DProjectedNode> {
        override val size: Int get() = nodesFarToNear.size
        override fun isEmpty(): Boolean = nodesFarToNear.isEmpty()

        override fun get(key: Int): Real3DProjectedNode? {
            val index = nodeIndexBySystemId[key] ?: return null
            if (nodeVisibilityGeneration[index] != currentGeneration) return null
            return nodeSlots[index]
        }

        override fun containsKey(key: Int): Boolean = get(key) != null

        override fun containsValue(value: Real3DProjectedNode): Boolean =
            nodesFarToNear.any { projected -> projected === value }

        override val keys: Set<Int> = object : AbstractSet<Int>() {
            override val size: Int get() = nodesFarToNear.size
            override fun contains(element: Int): Boolean = containsKey(element)
            override fun iterator(): Iterator<Int> = object : Iterator<Int> {
                private var index = 0
                override fun hasNext(): Boolean = index < nodesFarToNear.size
                override fun next(): Int = nodesFarToNear[index++].node.system.id
            }
        }

        override val values: Collection<Real3DProjectedNode> = object : AbstractCollection<Real3DProjectedNode>() {
            override val size: Int get() = nodesFarToNear.size
            override fun contains(element: Real3DProjectedNode): Boolean = containsValue(element)
            override fun iterator(): Iterator<Real3DProjectedNode> = object : Iterator<Real3DProjectedNode> {
                private var index = 0
                override fun hasNext(): Boolean = index < nodesFarToNear.size
                override fun next(): Real3DProjectedNode = nodesFarToNear[index++]
            }
        }

        override val entries: Set<Map.Entry<Int, Real3DProjectedNode>> =
            object : AbstractSet<Map.Entry<Int, Real3DProjectedNode>>() {
                override val size: Int get() = nodesFarToNear.size
                override fun contains(element: Map.Entry<Int, Real3DProjectedNode>): Boolean =
                    get(element.key) === element.value

                override fun iterator(): Iterator<Map.Entry<Int, Real3DProjectedNode>> =
                    object : Iterator<Map.Entry<Int, Real3DProjectedNode>> {
                        private var index = 0
                        override fun hasNext(): Boolean = index < nodesFarToNear.size
                        override fun next(): Map.Entry<Int, Real3DProjectedNode> = nodesFarToNear[index++].mapEntry
                    }
            }

        override fun equals(other: Any?): Boolean =
            other === this || other is Map<*, *> && size == other.size && entries.all { entry ->
                other[entry.key] == entry.value
            }

        override fun hashCode(): Int = entries.sumOf(Map.Entry<Int, Real3DProjectedNode>::hashCode)

        override fun toString(): String = entries.joinToString(prefix = "{", postfix = "}")
    }
}

private class MutableProjectedPoint(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var depth: Double = 0.0,
)

/** Allocation-light scalar projector dedicated to the high-frequency base-map frame. */
private class AllocationLightFrameProjector {
    private var rightX = 0.0
    private var rightY = 0.0
    private var rightZ = 0.0
    private var upX = 0.0
    private var upY = 0.0
    private var upZ = 0.0
    private var forwardX = 0.0
    private var forwardY = 0.0
    private var forwardZ = 0.0
    private var cameraX = 0.0
    private var cameraY = 0.0
    private var cameraZ = 0.0
    private var focalLength = 0.0
    private var nearPlane = 0.0
    private var farPlane = 0.0
    private var viewportWidth = 0.0
    private var viewportHeight = 0.0
    private val firstView = MutableProjectedPoint()
    private val secondView = MutableProjectedPoint()

    fun update(camera: Real3DCamera, viewportSize: MapSize) {
        val basis = camera.basis()
        rightX = basis.right.x
        rightY = basis.right.y
        rightZ = basis.right.z
        upX = basis.up.x
        upY = basis.up.y
        upZ = basis.up.z
        forwardX = basis.forward.x
        forwardY = basis.forward.y
        forwardZ = basis.forward.z
        cameraX = camera.target.x - forwardX * camera.distance
        cameraY = camera.target.y - forwardY * camera.distance
        cameraZ = camera.target.z - forwardZ * camera.distance
        viewportWidth = viewportSize.width
        viewportHeight = viewportSize.height
        focalLength = viewportHeight / (2.0 * tan(camera.verticalFieldOfViewDegrees * PI / 360.0))
        nearPlane = camera.nearPlane
        farPlane = camera.farPlane
    }

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
        if (firstView.depth < nearPlane && secondView.depth < nearPlane) return false
        if (firstView.depth > farPlane && secondView.depth > farPlane) return false
        if (firstView.depth < nearPlane) interpolateAtDepth(firstView, secondView, nearPlane)
        if (secondView.depth < nearPlane) interpolateAtDepth(secondView, firstView, nearPlane)
        if (firstView.depth > farPlane) interpolateAtDepth(firstView, secondView, farPlane)
        if (secondView.depth > farPlane) interpolateAtDepth(secondView, firstView, farPlane)
        if (!projectView(firstView, firstOutput, cullToViewport = false)) return false
        if (!projectView(secondView, secondOutput, cullToViewport = false)) return false
        return maxOf(firstOutput.x, secondOutput.x) >= 0.0 &&
            minOf(firstOutput.x, secondOutput.x) <= viewportWidth &&
            maxOf(firstOutput.y, secondOutput.y) >= 0.0 &&
            minOf(firstOutput.y, secondOutput.y) <= viewportHeight
    }

    private fun worldToView(point: MapPoint3, output: MutableProjectedPoint) {
        val relativeX = point.x - cameraX
        val relativeY = point.y - cameraY
        val relativeZ = point.z - cameraZ
        output.x = relativeX * rightX + relativeY * rightY + relativeZ * rightZ
        output.y = relativeX * upX + relativeY * upY + relativeZ * upZ
        output.depth = relativeX * forwardX + relativeY * forwardY + relativeZ * forwardZ
    }

    private fun projectView(
        input: MutableProjectedPoint,
        output: MutableProjectedPoint,
        cullToViewport: Boolean,
    ): Boolean {
        if (input.depth < nearPlane || input.depth > farPlane) return false
        val screenX = viewportWidth / 2.0 + input.x * focalLength / input.depth
        val screenY = viewportHeight / 2.0 - input.y * focalLength / input.depth
        if (cullToViewport && (screenX < 0.0 || screenX > viewportWidth || screenY < 0.0 || screenY > viewportHeight)) {
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

private val REAL_3D_PROJECTED_NODE_COMPARATOR = Comparator<Real3DProjectedNode> { first, second ->
    val depthOrder = second.depth.compareTo(first.depth)
    if (depthOrder != 0) depthOrder else first.node.system.id.compareTo(second.node.system.id)
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
