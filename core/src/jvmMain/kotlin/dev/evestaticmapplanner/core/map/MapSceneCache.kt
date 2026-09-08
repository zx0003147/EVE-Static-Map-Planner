package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.StaticMapData

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
