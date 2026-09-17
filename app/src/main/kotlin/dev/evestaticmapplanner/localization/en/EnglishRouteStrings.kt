package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.localization.RouteStrings

internal object EnglishRouteStrings : RouteStrings {
    override fun routeFound(jumps: Int): String = "Route found: $jumps jumps"
}
