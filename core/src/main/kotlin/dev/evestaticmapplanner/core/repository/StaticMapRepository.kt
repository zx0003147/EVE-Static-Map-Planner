package dev.evestaticmapplanner.core.repository

import dev.evestaticmapplanner.core.model.StaticMapData

fun interface StaticMapRepository {
    fun load(): StaticMapData
}
