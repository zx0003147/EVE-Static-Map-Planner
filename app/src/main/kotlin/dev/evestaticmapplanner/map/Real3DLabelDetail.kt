package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.map.MapPoint3
import dev.evestaticmapplanner.core.map.REAL_MAP_COORDINATE_UNIT_METERS

internal data class Real3DLabelDetailScope(
    val detailRegionIds: Set<Int>?,
    val center: MapPoint3,
    private val constellationRadiusSquared: Double,
    private val systemRadiusSquared: Double,
) {
    fun isOrdinaryLabelVisible(
        semanticMode: SemanticLabelMode,
        regionId: Int,
        position: MapPoint3,
    ): Boolean {
        if (semanticMode == SemanticLabelMode.REGION_ONLY) return true
        if (detailRegionIds != null && regionId !in detailRegionIds) return false
        val radiusSquared = when (semanticMode) {
            SemanticLabelMode.REGION_ONLY -> return true
            SemanticLabelMode.CONSTELLATION -> constellationRadiusSquared
            SemanticLabelMode.SYSTEM -> systemRadiusSquared
        }
        return distanceSquared(center, position) <= radiusSquared
    }
}

internal object Real3DLabelDetailScopeResolver {
    fun resolve(
        selectedSystemId: Int?,
        selectedDetailRegionIds: Set<Int>?,
        center: MapPoint3,
        constellationDisplayRangeLy: Double,
        systemDisplayRangeLy: Double,
    ): Real3DLabelDetailScope = Real3DLabelDetailScope(
        detailRegionIds = selectedDetailRegionIds.takeIf { selectedSystemId != null },
        center = center,
        constellationRadiusSquared = radiusSquaredMapUnits(constellationDisplayRangeLy),
        systemRadiusSquared = radiusSquaredMapUnits(systemDisplayRangeLy),
    )
}

private fun radiusSquaredMapUnits(radiusLy: Double): Double {
    require(radiusLy.isFinite() && radiusLy > 0.0) { "Real 3D label display range must be finite and positive" }
    val radiusMapUnits = radiusLy * EVE_LIGHT_YEAR_MAP_UNITS
    return radiusMapUnits * radiusMapUnits
}

private fun distanceSquared(first: MapPoint3, second: MapPoint3): Double {
    val dx = first.x - second.x
    val dy = first.y - second.y
    val dz = first.z - second.z
    return dx * dx + dy * dy + dz * dz
}

private const val EVE_LIGHT_YEAR_MAP_UNITS =
    UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR / REAL_MAP_COORDINATE_UNIT_METERS
