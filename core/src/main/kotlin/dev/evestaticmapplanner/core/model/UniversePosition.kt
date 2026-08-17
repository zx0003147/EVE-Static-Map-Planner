package dev.evestaticmapplanner.core.model

data class UniversePosition(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "Universe coordinates must be finite"
        }
    }
}

data class SchematicPosition(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) {
            "Schematic coordinates must be finite"
        }
    }
}
