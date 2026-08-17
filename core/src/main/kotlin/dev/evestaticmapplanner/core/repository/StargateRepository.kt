package dev.evestaticmapplanner.core.repository

import dev.evestaticmapplanner.core.model.Stargate

interface StargateRepository {
    fun getByOriginSystem(systemId: Int): List<Stargate>

    fun countByOriginSystem(systemId: Int): Int
}
