package dev.evestaticmapplanner.core.jump

enum class JumpRangePreset(val defaultRangeLy: Double?) {
    DREADNOUGHT_CARRIER_FAX(7.0),
    COMMAND_CARRIER(7.5),
    LANCER_DREAD(8.0),
    TITAN_SUPERCARRIER(6.0),
    BLACK_OPS(8.0),
    RORQUAL(10.0),
    CUSTOM(null),
}

enum class JumpRangeColor(val argb: Long, val userSelectable: Boolean = true) {
    RED(0xFFFF5D73L),
    ORANGE(0xFFFF9F43L),
    YELLOW(0xFFFFD166L),
    GREEN(0xFF57E389L),
    BLUE(0xFF42BFF5L),
    PURPLE(0xFFA98BFFL),
    WHITE(0xFFF1F5F8L),
    MISSION_GOLD(0xFFF4E06DL, false),
    MISSION_PINK(0xFFFFA9E7L, false),
    MISSION_TEAL(0xFF7AE7C7L, false),
    MISSION_LIGHT_BLUE(0xFF9CCBFFL, false),
}
