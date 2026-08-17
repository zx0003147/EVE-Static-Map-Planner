package dev.evestaticmapplanner.core.repository

import dev.evestaticmapplanner.core.model.SolarSystem

interface SystemSearchRepository {
    fun searchSystems(query: String, limit: Int = 20): List<SolarSystem>
}
