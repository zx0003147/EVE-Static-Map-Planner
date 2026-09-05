package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.UniversePosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Finite Cartesian coordinate in the render-friendly map unit used by the 3D presentation layer. */
data class MapPoint3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "3D map coordinates must be finite" }
    }

    operator fun plus(other: MapPoint3): MapPoint3 = MapPoint3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: MapPoint3): MapPoint3 = MapPoint3(x - other.x, y - other.y, z - other.z)

    operator fun times(factor: Double): MapPoint3 = MapPoint3(x * factor, y * factor, z * factor)

    operator fun div(divisor: Double): MapPoint3 = MapPoint3(x / divisor, y / divisor, z / divisor)

    fun dot(other: MapPoint3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: MapPoint3): MapPoint3 = MapPoint3(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x,
    )

    fun length(): Double = sqrt(dot(this))

    fun normalized(): MapPoint3 {
        val length = length()
        require(length > MIN_VECTOR_LENGTH) { "Cannot normalize a zero-length vector" }
        return this / length
    }

    companion object {
        val Zero = MapPoint3(0.0, 0.0, 0.0)

        fun fromUniverse(position: UniversePosition): MapPoint3 = MapPoint3(
            x = position.x / REAL_MAP_COORDINATE_UNIT_METERS,
            y = position.y / REAL_MAP_COORDINATE_UNIT_METERS,
            z = position.z / REAL_MAP_COORDINATE_UNIT_METERS,
        )
    }
}

data class Real3DCameraBasis(
    val right: MapPoint3,
    val up: MapPoint3,
    val forward: MapPoint3,
)

data class Real3DCamera(
    val target: MapPoint3,
    val distance: Double,
    val yawDegrees: Double = DEFAULT_REAL_3D_YAW_DEGREES,
    val pitchDegrees: Double = DEFAULT_REAL_3D_PITCH_DEGREES,
    val verticalFieldOfViewDegrees: Double = DEFAULT_REAL_3D_VERTICAL_FOV_DEGREES,
    val nearPlane: Double = DEFAULT_REAL_3D_NEAR_PLANE,
    val farPlane: Double = DEFAULT_REAL_3D_FAR_PLANE,
) {
    init {
        require(distance.isFinite() && distance > 0.0) { "Camera distance must be finite and positive" }
        require(yawDegrees.isFinite()) { "Camera yaw must be finite" }
        require(pitchDegrees.isFinite() && pitchDegrees in -MAX_REAL_3D_PITCH_DEGREES..MAX_REAL_3D_PITCH_DEGREES) {
            "Camera pitch must be between -$MAX_REAL_3D_PITCH_DEGREES and $MAX_REAL_3D_PITCH_DEGREES degrees"
        }
        require(verticalFieldOfViewDegrees.isFinite() && verticalFieldOfViewDegrees in 1.0..175.0) {
            "Camera field of view must be between 1 and 175 degrees"
        }
        require(nearPlane.isFinite() && nearPlane > 0.0) { "Near plane must be finite and positive" }
        require(farPlane.isFinite() && farPlane > nearPlane) { "Far plane must be beyond the near plane" }
    }

    val normalizedYawDegrees: Double get() = normalizeYawDegrees(yawDegrees)

    fun basis(): Real3DCameraBasis {
        val yaw = normalizedYawDegrees.toRadians()
        val pitch = pitchDegrees.toRadians()
        val forward = MapPoint3(
            x = sin(yaw) * cos(pitch),
            y = cos(yaw) * cos(pitch),
            z = sin(pitch),
        ).normalized()
        val right = forward.cross(WORLD_UP).normalized()
        val up = right.cross(forward).normalized()
        return Real3DCameraBasis(right = right, up = up, forward = forward)
    }

    fun position(): MapPoint3 = target - basis().forward * distance

    fun rotated(deltaYawDegrees: Double, deltaPitchDegrees: Double): Real3DCamera = copy(
        yawDegrees = normalizeYawDegrees(yawDegrees + deltaYawDegrees),
        pitchDegrees = (pitchDegrees + deltaPitchDegrees).coerceIn(
            -MAX_REAL_3D_PITCH_DEGREES,
            MAX_REAL_3D_PITCH_DEGREES,
        ),
    )

    fun dolly(factor: Double): Real3DCamera {
        require(factor.isFinite() && factor > 0.0) { "Dolly factor must be finite and positive" }
        return copy(distance = (distance * factor).coerceIn(MIN_REAL_3D_CAMERA_DISTANCE, MAX_REAL_3D_CAMERA_DISTANCE))
    }

    fun panned(screenDelta: MapPoint, viewportSize: MapSize): Real3DCamera {
        if (viewportSize.isEmpty) return this
        val basis = basis()
        val unitsPerPixel = distance / focalLengthPixels(viewportSize)
        val worldDelta = basis.right * (-screenDelta.x * unitsPerPixel) +
            basis.up * (screenDelta.y * unitsPerPixel)
        return copy(target = target + worldDelta)
    }
}

data class Real3DViewPoint(
    val x: Double,
    val y: Double,
    val depth: Double,
)

data class Real3DScreenPoint(
    val screen: MapPoint,
    val depth: Double,
    val perspectiveScale: Double,
)

data class Real3DProjectedSegment(
    val first: Real3DScreenPoint,
    val second: Real3DScreenPoint,
)

class Real3DProjector(
    val camera: Real3DCamera,
    val viewportSize: MapSize,
) {
    init {
        require(!viewportSize.isEmpty) { "3D projection requires a non-empty viewport" }
    }

    private val basis = camera.basis()
    private val cameraPosition = camera.position()
    private val focalLength = camera.focalLengthPixels(viewportSize)

    fun worldToView(point: MapPoint3): Real3DViewPoint {
        val relative = point - cameraPosition
        return Real3DViewPoint(
            x = relative.dot(basis.right),
            y = relative.dot(basis.up),
            depth = relative.dot(basis.forward),
        )
    }

    fun project(point: MapPoint3, cullToViewport: Boolean = true): Real3DScreenPoint? =
        projectView(worldToView(point), cullToViewport)

    fun projectView(point: Real3DViewPoint, cullToViewport: Boolean = true): Real3DScreenPoint? {
        if (point.depth < camera.nearPlane || point.depth > camera.farPlane) return null
        val perspectiveScale = focalLength / point.depth
        val screen = MapPoint(
            x = viewportSize.width / 2.0 + point.x * perspectiveScale,
            y = viewportSize.height / 2.0 - point.y * perspectiveScale,
        )
        if (cullToViewport && (screen.x < 0.0 || screen.x > viewportSize.width || screen.y < 0.0 || screen.y > viewportSize.height)) {
            return null
        }
        return Real3DScreenPoint(screen = screen, depth = point.depth, perspectiveScale = perspectiveScale)
    }

    /** Clips a world-space segment against the near/far planes before perspective projection. */
    fun projectSegment(first: MapPoint3, second: MapPoint3): Real3DProjectedSegment? {
        var start = worldToView(first)
        var end = worldToView(second)
        if (start.depth < camera.nearPlane && end.depth < camera.nearPlane) return null
        if (start.depth > camera.farPlane && end.depth > camera.farPlane) return null
        if (start.depth < camera.nearPlane) start = interpolateAtDepth(start, end, camera.nearPlane)
        if (end.depth < camera.nearPlane) end = interpolateAtDepth(end, start, camera.nearPlane)
        if (start.depth > camera.farPlane) start = interpolateAtDepth(start, end, camera.farPlane)
        if (end.depth > camera.farPlane) end = interpolateAtDepth(end, start, camera.farPlane)
        val projectedStart = projectView(start, cullToViewport = false) ?: return null
        val projectedEnd = projectView(end, cullToViewport = false) ?: return null
        val segmentBounds = MapBounds.between(projectedStart.screen, projectedEnd.screen)
        val viewportBounds = MapBounds(0.0, 0.0, viewportSize.width, viewportSize.height)
        return if (segmentBounds.intersects(viewportBounds)) {
            Real3DProjectedSegment(projectedStart, projectedEnd)
        } else {
            null
        }
    }

    private fun interpolateAtDepth(
        first: Real3DViewPoint,
        second: Real3DViewPoint,
        targetDepth: Double,
    ): Real3DViewPoint {
        val amount = (targetDepth - first.depth) / (second.depth - first.depth)
        return Real3DViewPoint(
            x = first.x + (second.x - first.x) * amount,
            y = first.y + (second.y - first.y) * amount,
            depth = targetDepth,
        )
    }
}

object Real3DCameraFitter {
    fun fit(
        points: Collection<MapPoint3>,
        viewportSize: MapSize,
        yawDegrees: Double = DEFAULT_REAL_3D_YAW_DEGREES,
        pitchDegrees: Double = DEFAULT_REAL_3D_PITCH_DEGREES,
        verticalFieldOfViewDegrees: Double = DEFAULT_REAL_3D_VERTICAL_FOV_DEGREES,
        paddingFraction: Double = DEFAULT_REAL_3D_FIT_PADDING_FRACTION,
    ): Real3DCamera {
        require(points.isNotEmpty()) { "Cannot fit an empty 3D scene" }
        require(!viewportSize.isEmpty) { "Cannot fit an empty viewport" }
        require(paddingFraction.isFinite() && paddingFraction in 0.0..0.45) { "Fit padding must be between 0 and 0.45" }
        val target = boundsCenter(points)
        val template = Real3DCamera(
            target = target,
            distance = 1.0,
            yawDegrees = yawDegrees,
            pitchDegrees = pitchDegrees,
            verticalFieldOfViewDegrees = verticalFieldOfViewDegrees,
        )
        val basis = template.basis()
        val aspect = viewportSize.width / viewportSize.height
        val usableRatio = 1.0 - paddingFraction * 2.0
        val verticalTangent = tan((verticalFieldOfViewDegrees / 2.0).toRadians()) * usableRatio
        val horizontalTangent = verticalTangent * aspect
        var distance = MIN_REAL_3D_CAMERA_DISTANCE
        points.forEach { point ->
            val relative = point - target
            val viewX = relative.dot(basis.right)
            val viewY = relative.dot(basis.up)
            val viewDepth = relative.dot(basis.forward)
            distance = max(distance, abs(viewX) / horizontalTangent - viewDepth)
            distance = max(distance, abs(viewY) / verticalTangent - viewDepth)
            distance = max(distance, template.nearPlane - viewDepth)
        }
        return template.copy(distance = (distance * FIT_DISTANCE_SAFETY_MULTIPLIER).coerceIn(
            MIN_REAL_3D_CAMERA_DISTANCE,
            MAX_REAL_3D_CAMERA_DISTANCE,
        ))
    }

    private fun boundsCenter(points: Collection<MapPoint3>): MapPoint3 {
        val first = points.first()
        var minX = first.x
        var minY = first.y
        var minZ = first.z
        var maxX = first.x
        var maxY = first.y
        var maxZ = first.z
        points.forEach { point ->
            minX = kotlin.math.min(minX, point.x)
            minY = kotlin.math.min(minY, point.y)
            minZ = kotlin.math.min(minZ, point.z)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
            maxZ = max(maxZ, point.z)
        }
        return MapPoint3((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0)
    }
}

fun normalizeYawDegrees(value: Double): Double {
    require(value.isFinite()) { "Yaw must be finite" }
    val normalized = value % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

private fun Real3DCamera.focalLengthPixels(viewportSize: MapSize): Double =
    viewportSize.height / (2.0 * tan((verticalFieldOfViewDegrees / 2.0).toRadians()))

private fun Double.toRadians(): Double = this * PI / 180.0

private val WORLD_UP = MapPoint3(0.0, 0.0, 1.0)

const val REAL_MAP_COORDINATE_UNIT_METERS: Double = 1_000_000_000_000_000.0
const val DEFAULT_REAL_3D_YAW_DEGREES: Double = 0.0
const val DEFAULT_REAL_3D_PITCH_DEGREES: Double = 0.0
const val DEFAULT_REAL_3D_VERTICAL_FOV_DEGREES: Double = 48.0
const val MAX_REAL_3D_PITCH_DEGREES: Double = 85.0
const val DEFAULT_REAL_3D_NEAR_PLANE: Double = 0.01
const val DEFAULT_REAL_3D_FAR_PLANE: Double = 100_000.0
const val MIN_REAL_3D_CAMERA_DISTANCE: Double = 0.02
const val MAX_REAL_3D_CAMERA_DISTANCE: Double = 50_000.0
const val DEFAULT_REAL_3D_FIT_PADDING_FRACTION: Double = 0.08

private const val MIN_VECTOR_LENGTH = 1e-12
private const val FIT_DISTANCE_SAFETY_MULTIPLIER = 1.02
