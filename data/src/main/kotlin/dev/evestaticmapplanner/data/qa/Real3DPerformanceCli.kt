package dev.evestaticmapplanner.data.qa

import com.sun.management.ThreadMXBean
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.Real3DCanonicalProjection
import dev.evestaticmapplanner.core.map.Real3DCamera
import dev.evestaticmapplanner.core.map.Real3DCameraFitter
import dev.evestaticmapplanner.core.map.Real3DFrame
import dev.evestaticmapplanner.core.map.Real3DFrameProjectionWorkspace
import dev.evestaticmapplanner.core.map.Real3DJumpSphereBuilder
import dev.evestaticmapplanner.core.map.Real3DJumpSphereProjector
import dev.evestaticmapplanner.core.map.Real3DProjector
import dev.evestaticmapplanner.core.map.Real3DRouteProjector
import dev.evestaticmapplanner.core.map.Real3DStaticGeometry
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sin

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: real3DPerformanceProfile -Preal3DProfileDatabase=<static.db>" }
    val data = SqliteStaticMapRepository(Path.of(args.single())).load()
    val scene = MapSceneBuilder().build(data, Real3DCanonicalProjection)
    val geometry = Real3DStaticGeometry.from(scene)
    val viewport = MapSize(1_920.0, 1_080.0)
    val fitted = Real3DCameraFitter.fit(geometry.fitPoints, viewport)
    val route = data.connections.firstOrNull()?.let { connection ->
        val id = "profile:${connection.firstSystemId}:${connection.secondSystemId}"
        RouteResult(
            startSystemId = connection.firstSystemId,
            destinationSystemId = connection.secondSystemId,
            systems = listOf(connection.firstSystemId, connection.secondSystemId),
            edges = listOf(
                RouteEdge(
                    RouteEdgeId(id),
                    RouteConnectionId(id),
                    connection.firstSystemId,
                    connection.secondSystemId,
                    RouteEdgeType.STARGATE,
                ),
            ),
        )
    }
    val sphereOverlays = geometry.nodes.asSequence().filter { it.isStargateConnected }.take(4).mapIndexed { index, node ->
        JumpRangeOverlay(
            id = "profile-$index",
            originSystemId = node.system.id,
            profile = JumpProfile.manual(5.0 + index, "profile-$index"),
            reachableSystemIds = emptySet(),
        )
    }.toList()
    val spheres = Real3DJumpSphereBuilder.build(sphereOverlays, geometry)
    val overlayAnchors = geometry.nodes.asSequence().filter { it.isStargateConnected }.map { it.position }.take(800).toList()

    val scenarios = listOf(
        Scenario("full-map", fitted, LabelCount.REGIONS),
        Scenario("typical-zoom", fitted.dolly(0.35), LabelCount.CONSTELLATIONS),
        Scenario("rotated-view", fitted.rotated(55.0, 25.0), LabelCount.CONSTELLATIONS),
        Scenario("inside-map", fitted.dolly(0.08).rotated(92.0, 18.0), LabelCount.SYSTEMS),
        Scenario("route-visible", fitted.dolly(0.35).rotated(28.0, 12.0), LabelCount.CONSTELLATIONS) { camera, _ ->
            route?.let { Real3DRouteProjector.project(it, geometry, camera, viewport).legs.size } ?: 0
        },
        Scenario("several-jump-spheres", fitted.dolly(0.35).rotated(42.0, 20.0), LabelCount.CONSTELLATIONS) { camera, _ ->
            Real3DJumpSphereProjector.project(spheres, camera, viewport).sumOf { sphere ->
                sphere.shellSegments.size + sphere.fillTrianglesFarToNear.size
            }
        },
        Scenario("common-overlays", fitted.dolly(0.24).rotated(64.0, 16.0), LabelCount.SYSTEMS) { camera, _ ->
            val projector = Real3DProjector(camera, viewport)
            overlayAnchors.count { projector.project(it) != null }
        },
    )
    println("REAL3D_PROFILE database=${Path.of(args.single()).toAbsolutePath()} systems=${data.systems.size} edges=${data.connections.size}")
    scenarios.forEach { scenario ->
        val result = profileScenario(scenario, geometry, viewport)
        println(result.format())
    }
    println("REAL3D_PROFILE allocationHotspots=screen-coordinate values; route/sphere projection records; depth sorting")
}

private data class Scenario(
    val name: String,
    val camera: Real3DCamera,
    val labelCount: LabelCount,
    val extraWork: (Real3DCamera, Real3DFrame) -> Int = { _, _ -> 0 },
)

private enum class LabelCount { REGIONS, CONSTELLATIONS, SYSTEMS }

private data class ProfileResult(
    val name: String,
    val systemsDrawn: Int,
    val edgesDrawn: Int,
    val labelsDrawn: Int,
    val extraPrimitives: Int,
    val averageMillis: Double,
    val p95Millis: Double,
    val approximateFps: Double,
    val averageAllocatedKib: Double?,
) {
    fun format(): String = "REAL3D_PROFILE scenario=$name systems=$systemsDrawn edges=$edgesDrawn labels=$labelsDrawn " +
        "extra=$extraPrimitives avgMs=${decimal(averageMillis)} p95Ms=${decimal(p95Millis)} " +
        "approxProjectionFps=${decimal(approximateFps)} " +
        "avgAllocatedKiB=${averageAllocatedKib?.let(::decimal) ?: "unavailable"}"
}

private fun profileScenario(
    scenario: Scenario,
    geometry: Real3DStaticGeometry,
    viewport: MapSize,
): ProfileResult {
    val workspace = Real3DFrameProjectionWorkspace()
    repeat(WARMUP_ITERATIONS) { index ->
        val camera = animatedCamera(scenario.camera, index)
        scenario.extraWork(camera, workspace.project(geometry, camera, viewport))
    }
    val allocationBean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf(ThreadMXBean::isThreadAllocatedMemorySupported)
        ?.also { if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true }
    val threadId = Thread.currentThread().threadId()
    val timings = LongArray(MEASURED_ITERATIONS)
    var frame: Real3DFrame = workspace.project(geometry, scenario.camera, viewport)
    var extraPrimitives = 0
    val allocatedBefore = allocationBean?.getThreadAllocatedBytes(threadId)
    repeat(MEASURED_ITERATIONS) { index ->
        val camera = animatedCamera(scenario.camera, index)
        val started = System.nanoTime()
        frame = workspace.project(geometry, camera, viewport)
        extraPrimitives = scenario.extraWork(camera, frame)
        timings[index] = System.nanoTime() - started
    }
    val allocatedAfter = allocationBean?.getThreadAllocatedBytes(threadId)
    val sorted = timings.sortedArray()
    val averageMillis = timings.average() / NANOS_PER_MILLI
    val p95Index = (ceil(MEASURED_ITERATIONS * 0.95).toInt() - 1).coerceIn(sorted.indices)
    val labels = when (scenario.labelCount) {
        LabelCount.REGIONS -> frame.regions.size
        LabelCount.CONSTELLATIONS -> frame.regions.size + frame.constellations.size
        LabelCount.SYSTEMS -> frame.regions.size + frame.nodesFarToNear.size
    }
    return ProfileResult(
        name = scenario.name,
        systemsDrawn = frame.nodesFarToNear.size,
        edgesDrawn = frame.edges.size,
        labelsDrawn = labels,
        extraPrimitives = extraPrimitives,
        averageMillis = averageMillis,
        p95Millis = sorted[p95Index] / NANOS_PER_MILLI,
        approximateFps = 1_000.0 / averageMillis,
        averageAllocatedKib = allocatedBefore?.let { before ->
            allocatedAfter?.let { after -> (after - before).toDouble() / MEASURED_ITERATIONS / 1_024.0 }
        },
    )
}

private fun animatedCamera(camera: Real3DCamera, index: Int): Real3DCamera = camera.rotated(
    deltaYawDegrees = index * 0.035,
    deltaPitchDegrees = sin(index * 0.11) * 0.18,
)

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

private const val WARMUP_ITERATIONS = 40
private const val MEASURED_ITERATIONS = 240
private const val NANOS_PER_MILLI = 1_000_000.0
