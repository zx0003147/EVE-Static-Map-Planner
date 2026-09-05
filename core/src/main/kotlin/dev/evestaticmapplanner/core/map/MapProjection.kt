package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.SolarSystem

enum class MapProjectionId(val displayName: String) {
    OFFICIAL_2D("Official 2D"),
    REAL_3D("Real 3D"),
    ;

    companion object {
        /** Keeps source and persisted-setting compatibility with the replaced Real X-Z mode. */
        @Deprecated("Real X-Z was replaced by Real 3D", ReplaceWith("REAL_3D"))
        val REAL_XZ: MapProjectionId = REAL_3D

        fun fromPersistedValue(value: String?): MapProjectionId = when (value) {
            "REAL_XZ", "REAL_3D" -> REAL_3D
            "OFFICIAL_2D" -> OFFICIAL_2D
            else -> OFFICIAL_2D
        }
    }
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

/** Canonical X/Z layout used for non-rendering presentation geometry in Real 3D mode. */
object Real3DCanonicalProjection : MapProjection {
    override val id = MapProjectionId.REAL_3D

    override fun project(system: SolarSystem): MapPoint = MapPoint(
        x = system.position.x / MAP_COORDINATE_UNIT,
        y = -system.position.z / MAP_COORDINATE_UNIT,
    )
}

@Deprecated("Real X-Z was replaced by Real 3D", ReplaceWith("Real3DCanonicalProjection"))
val RealXzProjection: MapProjection = Real3DCanonicalProjection

fun projectionFor(id: MapProjectionId): MapProjection = when (id) {
    MapProjectionId.OFFICIAL_2D -> OfficialPosition2DProjection
    MapProjectionId.REAL_3D -> Real3DCanonicalProjection
}

private const val MAP_COORDINATE_UNIT = 1_000_000_000_000_000.0
