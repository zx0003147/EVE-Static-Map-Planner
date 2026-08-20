package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition

internal fun testSystem(
    id: Int,
    x: Double = id.toDouble(),
    y: Double = 0.0,
    z: Double = id.toDouble(),
    x2d: Double? = id.toDouble(),
    y2d: Double? = id.toDouble(),
    constellationId: Int = 10,
    regionId: Int = 1,
): SolarSystem = SolarSystem(
    id = id,
    constellationId = constellationId,
    regionId = regionId,
    name = "System $id",
    securityStatus = 0.25,
    securityClass = null,
    position = UniversePosition(x, y, z),
    schematicPosition = if (x2d != null && y2d != null) SchematicPosition(x2d, y2d) else null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)
