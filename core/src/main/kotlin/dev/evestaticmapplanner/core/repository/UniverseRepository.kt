package dev.evestaticmapplanner.core.repository

import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails

interface UniverseRepository {
    fun getRegion(id: Int): Region?

    fun getConstellation(id: Int): Constellation?

    fun getSystem(id: Int): SolarSystem?

    fun findSystemByName(name: String): SolarSystem?

    fun getSystemDetails(id: Int): SolarSystemDetails?
}
