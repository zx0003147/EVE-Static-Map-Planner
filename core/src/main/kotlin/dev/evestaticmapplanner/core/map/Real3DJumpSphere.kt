package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class Real3DWorldSegment(val first: MapPoint3, val second: MapPoint3)

data class Real3DWorldTriangle(val first: MapPoint3, val second: MapPoint3, val third: MapPoint3)

data class Real3DJumpSphere(
    val overlay: JumpRangeOverlay,
    val center: MapPoint3,
    val radius: Double,
    val shellSegments: List<Real3DWorldSegment>,
    val fillTriangles: List<Real3DWorldTriangle>,
)

data class Real3DProjectedTriangle(
    val first: MapPoint,
    val second: MapPoint,
    val third: MapPoint,
    val averageDepth: Double,
)

data class Real3DProjectedJumpSphere(
    val sphere: Real3DJumpSphere,
    val shellSegments: List<Real3DProjectedSegment>,
    val fillTrianglesFarToNear: List<Real3DProjectedTriangle>,
)

object Real3DJumpSphereBuilder {
    private val unitShellSegments = buildUnitShellSegments()
    private val unitFillTriangles = buildUnitFillTriangles()

    fun build(
        overlays: List<JumpRangeOverlay>,
        geometry: Real3DStaticGeometry,
    ): List<Real3DJumpSphere> = overlays.mapNotNull { overlay ->
        val center = geometry.nodesById[overlay.originSystemId]?.position ?: return@mapNotNull null
        val radius = overlay.profile.maxRangeLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR /
            REAL_MAP_COORDINATE_UNIT_METERS
        Real3DJumpSphere(
            overlay = overlay,
            center = center,
            radius = radius,
            shellSegments = unitShellSegments.map { segment ->
                Real3DWorldSegment(
                    center + segment.first * radius,
                    center + segment.second * radius,
                )
            },
            fillTriangles = unitFillTriangles.map { triangle ->
                Real3DWorldTriangle(
                    center + triangle.first * radius,
                    center + triangle.second * radius,
                    center + triangle.third * radius,
                )
            },
        )
    }

    private fun buildUnitShellSegments(): List<Real3DWorldSegment> = buildList {
        SHELL_LATITUDES_DEGREES.forEach { latitudeDegrees ->
            val latitude = latitudeDegrees * PI / 180.0
            for (longitudeIndex in 0 until SHELL_LONGITUDE_STEPS) {
                val firstLongitude = 2.0 * PI * longitudeIndex / SHELL_LONGITUDE_STEPS
                val secondLongitude = 2.0 * PI * (longitudeIndex + 1) / SHELL_LONGITUDE_STEPS
                add(Real3DWorldSegment(unitPoint(latitude, firstLongitude), unitPoint(latitude, secondLongitude)))
            }
        }
        for (meridianIndex in 0 until SHELL_MERIDIAN_COUNT) {
            val longitude = 2.0 * PI * meridianIndex / SHELL_MERIDIAN_COUNT
            for (latitudeIndex in 0 until SHELL_LATITUDE_STEPS) {
                val firstLatitude = -PI / 2.0 + PI * latitudeIndex / SHELL_LATITUDE_STEPS
                val secondLatitude = -PI / 2.0 + PI * (latitudeIndex + 1) / SHELL_LATITUDE_STEPS
                add(Real3DWorldSegment(unitPoint(firstLatitude, longitude), unitPoint(secondLatitude, longitude)))
            }
        }
    }

    private fun buildUnitFillTriangles(): List<Real3DWorldTriangle> = buildList {
        for (latitudeIndex in 0 until FILL_LATITUDE_STEPS) {
            val lowerLatitude = -PI / 2.0 + PI * latitudeIndex / FILL_LATITUDE_STEPS
            val upperLatitude = -PI / 2.0 + PI * (latitudeIndex + 1) / FILL_LATITUDE_STEPS
            for (longitudeIndex in 0 until FILL_LONGITUDE_STEPS) {
                val firstLongitude = 2.0 * PI * longitudeIndex / FILL_LONGITUDE_STEPS
                val secondLongitude = 2.0 * PI * (longitudeIndex + 1) / FILL_LONGITUDE_STEPS
                val lowerFirst = unitPoint(lowerLatitude, firstLongitude)
                val lowerSecond = unitPoint(lowerLatitude, secondLongitude)
                val upperFirst = unitPoint(upperLatitude, firstLongitude)
                val upperSecond = unitPoint(upperLatitude, secondLongitude)
                add(Real3DWorldTriangle(lowerFirst, lowerSecond, upperSecond))
                add(Real3DWorldTriangle(lowerFirst, upperSecond, upperFirst))
            }
        }
    }

    private fun unitPoint(latitude: Double, longitude: Double): MapPoint3 {
        val latitudeRadius = cos(latitude)
        return MapPoint3(
            x = latitudeRadius * cos(longitude),
            y = latitudeRadius * sin(longitude),
            z = sin(latitude),
        )
    }
}

object Real3DJumpSphereProjector {
    fun project(
        spheres: List<Real3DJumpSphere>,
        camera: Real3DCamera,
        viewportSize: MapSize,
    ): List<Real3DProjectedJumpSphere> {
        val projector = Real3DProjector(camera, viewportSize)
        return spheres.map { sphere ->
            val shell = sphere.shellSegments.mapNotNull { projector.projectSegment(it.first, it.second) }
            val fill = sphere.fillTriangles.mapNotNull { triangle ->
                val first = projector.project(triangle.first, cullToViewport = false) ?: return@mapNotNull null
                val second = projector.project(triangle.second, cullToViewport = false) ?: return@mapNotNull null
                val third = projector.project(triangle.third, cullToViewport = false) ?: return@mapNotNull null
                val bounds = MapBounds(
                    minOf(first.screen.x, second.screen.x, third.screen.x),
                    minOf(first.screen.y, second.screen.y, third.screen.y),
                    maxOf(first.screen.x, second.screen.x, third.screen.x),
                    maxOf(first.screen.y, second.screen.y, third.screen.y),
                )
                if (!bounds.intersects(MapBounds(0.0, 0.0, viewportSize.width, viewportSize.height))) {
                    return@mapNotNull null
                }
                Real3DProjectedTriangle(
                    first = first.screen,
                    second = second.screen,
                    third = third.screen,
                    averageDepth = (first.depth + second.depth + third.depth) / 3.0,
                )
            }.sortedByDescending(Real3DProjectedTriangle::averageDepth)
            Real3DProjectedJumpSphere(sphere, shell, fill)
        }
    }
}

private val SHELL_LATITUDES_DEGREES = doubleArrayOf(-60.0, -30.0, 0.0, 30.0, 60.0)
private const val SHELL_LONGITUDE_STEPS = 24
private const val SHELL_MERIDIAN_COUNT = 8
private const val SHELL_LATITUDE_STEPS = 24
private const val FILL_LATITUDE_STEPS = 10
private const val FILL_LONGITUDE_STEPS = 20
