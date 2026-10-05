package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.Real3DCanonicalProjection
import dev.evestaticmapplanner.core.map.Real3DCameraFitter
import dev.evestaticmapplanner.core.map.Real3DFrameProjectionWorkspace
import dev.evestaticmapplanner.core.map.Real3DStaticGeometry
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.preferences.MapDisplayPreferences
import java.nio.file.Path

/** Read-only QA entry point for comparing projected nodes with ordinary label detail counts. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: real3DLabelDetailQa <static.db>" }
    val data = SqliteStaticMapRepository(Path.of(args.single())).load()
    val scene = MapSceneBuilder().build(data, Real3DCanonicalProjection)
    val geometry = Real3DStaticGeometry.from(scene)
    val viewport = MapSize(1_920.0, 1_080.0)
    val fitted = Real3DCameraFitter.fit(geometry.fitPoints, viewport)
    val workspace = Real3DFrameProjectionWorkspace()
    val preferences = MapDisplayPreferences.Defaults
    val detailScope = Real3DLabelDetailScopeResolver.resolve(
        selectedSystemId = null,
        selectedDetailRegionIds = null,
        center = fitted.target,
        constellationDisplayRangeLy = preferences.real3DConstellationDisplayRangeLy,
        systemDisplayRangeLy = preferences.real3DSystemDisplayRangeLy,
    )

    println(
        "REAL3D_LABEL_DETAIL_QA database=${Path.of(args.single()).toAbsolutePath()} " +
            "systems=${geometry.nodes.size} constellations=${geometry.constellations.size} " +
            "constellationRangeLy=${preferences.real3DConstellationDisplayRangeLy} " +
            "systemRangeLy=${preferences.real3DSystemDisplayRangeLy}",
    )
    listOf(1.0, 1.8, 4.5, 8.0, 15.0).forEach { scale ->
        val camera = fitted.copy(distance = fitted.distance / scale)
        val frame = workspace.project(geometry, camera, viewport)
        val semanticMode = SemanticZoomPolicy.initialReal3DMode(scale, preferences)
        val constellationBefore = if (semanticMode == SemanticLabelMode.CONSTELLATION) {
            frame.constellations.size
        } else {
            0
        }
        val constellationAfter = if (semanticMode == SemanticLabelMode.CONSTELLATION) {
            frame.constellations.count { projected ->
                val regionId = scene.constellationsById[projected.anchor.id]?.regionId ?: return@count false
                detailScope.isOrdinaryLabelVisible(semanticMode, regionId, projected.anchor.position)
            }
        } else {
            0
        }
        val systemBefore = if (semanticMode == SemanticLabelMode.SYSTEM) frame.nodesFarToNear.size else 0
        val systemAfter = if (semanticMode == SemanticLabelMode.SYSTEM) {
            frame.nodesFarToNear.count { projected ->
                detailScope.isOrdinaryLabelVisible(
                    semanticMode,
                    projected.node.system.regionId,
                    projected.node.position,
                )
            }
        } else {
            0
        }
        println(
            "REAL3D_LABEL_DETAIL_QA scale=$scale mode=$semanticMode " +
                "projectedSystems=${frame.nodesFarToNear.size} " +
                "constellationLabelsBefore=$constellationBefore constellationLabelsAfter=$constellationAfter " +
                "systemLabelsBefore=$systemBefore systemLabelsAfter=$systemAfter",
        )
    }
}
