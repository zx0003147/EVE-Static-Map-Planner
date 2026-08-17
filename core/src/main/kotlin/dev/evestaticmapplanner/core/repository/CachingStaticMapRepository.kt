package dev.evestaticmapplanner.core.repository

import dev.evestaticmapplanner.core.model.StaticMapData

class CachingStaticMapRepository(
    private val delegate: StaticMapRepository,
) : StaticMapRepository {
    @Volatile
    private var cached: StaticMapData? = null

    override fun load(): StaticMapData = cached ?: synchronized(this) {
        cached ?: delegate.load().also { cached = it }
    }
}
