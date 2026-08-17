package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.SolarSystem

enum class MapProjectionId(val displayName: String) {
    OFFICIAL_2D("Official 2D"),
    REAL_XZ("Real X-Z"),
}

interface MapProjection {
    val id: MapProjectionId

    fun project(system: SolarSystem): MapPoint?
}

object OfficialPosition2DProjection : MapProjection {
    override val id = MapProjectionId.OFFICIAL_2D

    override fun project(system: SolarSystem): MapPoint? = system.schematicPosition?.let {
        MapPoint(it.x / MAP_COORDINATE_UNIT, -it.y / MAP_COORDINATE_UNIT)
    }
}

object RealXzProjection : MapProjection {
    override val id = MapProjectionId.REAL_XZ

    override fun project(system: SolarSystem): MapPoint = MapPoint(
        x = system.position.x / MAP_COORDINATE_UNIT,
        y = -system.position.z / MAP_COORDINATE_UNIT,
    )
}

fun projectionFor(id: MapProjectionId): MapProjection = when (id) {
    MapProjectionId.OFFICIAL_2D -> OfficialPosition2DProjection
    MapProjectionId.REAL_XZ -> RealXzProjection
}

private const val MAP_COORDINATE_UNIT = 1_000_000_000_000_000.0
