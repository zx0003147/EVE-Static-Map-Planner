package dev.evestaticmapplanner.core.jump

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition

internal fun jumpTestSystem(
    id: Int,
    xLy: Double = 0.0,
    yLy: Double = 0.0,
    zLy: Double = 0.0,
    security: Double = 0.0,
    effectiveClassId: Int? = null,
): SolarSystem = SolarSystem(
    id = id,
    constellationId = 20_000_001,
    regionId = 10_000_001,
    name = "Jump System $id",
    securityStatus = security,
    securityClass = null,
    position = UniversePosition(
        xLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
        yLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
        zLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
    ),
    schematicPosition = null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
    effectiveWormholeClassId = effectiveClassId,
)
