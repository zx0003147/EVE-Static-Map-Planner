package dev.evestaticmapplanner.localization

sealed interface UiMessage {
    fun resolve(strings: AppStrings): String
}

data class RouteFoundUiMessage(val jumps: Int) : UiMessage {
    init {
        require(jumps >= 0) { "Jump count must not be negative" }
    }

    override fun resolve(strings: AppStrings): String = strings.route.routeFound(jumps)
}
