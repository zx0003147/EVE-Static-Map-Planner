package dev.evestaticmapplanner.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement

fun main() {
    window.asDynamic().startEveWebClient = { rawDocument: dynamic ->
        try {
            setBootStatus("Building map", null)
            val documentDto = parseWebPackDocument(rawDocument)
            val universe = WebUniverseDataAdapter.adapt(documentDto)
            lateinit var app: WebApplication
            val planner = WebPlannerController(universe) { state -> app.render(state) }
            app = WebApplication(planner)
            app.start()
            setBootStatus("Ready", null)
            document.documentElement?.classList?.add("app-ready")
        } catch (failure: Throwable) {
            setBootStatus("Web client failed", failure.message ?: failure.toString())
        }
    }
}

private fun setBootStatus(status: String, error: String?) {
    document.getElementById("boot-status")?.textContent = status
    document.getElementById("fatal-error")?.apply {
        textContent = error.orEmpty()
        if (error == null) classList.add("hidden") else classList.remove("hidden")
    }
}

private class WebApplication(private val planner: WebPlannerController) {
    private val universe = planner.universe
    private val banner = element<HTMLDivElement>("user-message")
    private val mapView = WebMapView(
        canvas = element<HTMLCanvasElement>("map-canvas"),
        scene = universe.scene,
        onSelect = planner::selectSystem,
        onHover = planner::hoverSystem,
    )
    private val search = SystemPicker("global-search", "global-results", planner::search) { system ->
        planner.selectSystem(system.id)
        if (!mapView.centerOn(system.id)) showTransientError("${system.name} has no Official 2D position.")
    }
    private val routeFrom = SystemPicker("route-from", "route-from-results", planner::search) { planner.setNormalStart(it.id) }
    private val routeTo = SystemPicker("route-to", "route-to-results", planner::search) { planner.setNormalDestination(it.id) }
    private val waypoint = SystemPicker("route-waypoint", "route-waypoint-results", planner::search) { system ->
        planner.addNormalWaypoint(system.id)
    }
    private val capitalFrom = SystemPicker("capital-from", "capital-from-results", planner::search) { planner.setCapitalStart(it.id) }
    private val capitalTo = SystemPicker("capital-to", "capital-to-results", planner::search) { planner.setCapitalDestination(it.id) }
    private val jumpSource = SystemPicker("jump-source", "jump-source-results", planner::search) { _ -> }

    fun start() {
        element<HTMLElement>("pack-meta").textContent =
            "SDE ${universe.metadata.sdeBuild} · Pack ${universe.metadata.packVersion} · Desktop ${universe.metadata.desktopAppVersion}"
        element<HTMLElement>("map-stats").textContent =
            "${universe.staticData.systems.size} systems · ${universe.staticData.connections.size} Stargates · " +
                "${universe.ansiblex.size} Ansiblex · ${universe.scene.omittedSystemIds.size} unpositioned"
        bindControls()
        render(planner.state)
        mapView.fit()
    }

    fun render(state: WebPlannerState) {
        mapView.update(state)
        renderBanner(state)
        renderWaypoints(state)
        renderRouteSummary(state)
        renderCapitalSummary(state)
        renderOverlays(state)
        renderSystemInfo(state)
        element<HTMLInputElementCompat>("use-ansiblex").checked = state.useAnsiblex
    }

    private fun bindControls() {
        click("fit-map") { mapView.fit() }
        click("route-from-selected") { planner.state.selectedSystemId?.let(routeFrom::selectId) }
        click("route-to-selected") { planner.state.selectedSystemId?.let(routeTo::selectId) }
        click("waypoint-selected") { planner.state.selectedSystemId?.let(planner::addNormalWaypoint) }
        element<HTMLInputElementCompat>("use-ansiblex").addEventListener("change", {
            planner.setUseAnsiblex(element<HTMLInputElementCompat>("use-ansiblex").checked)
        })
        click("calculate-route") { planner.calculateNormalRoute() }
        click("clear-route") { planner.clearNormalRoute() }
        click("capital-from-selected") { planner.state.selectedSystemId?.let(capitalFrom::selectId) }
        click("capital-to-selected") { planner.state.selectedSystemId?.let(capitalTo::selectId) }
        element<HTMLInputElementCompat>("capital-range").addEventListener("change", {
            val value = element<HTMLInputElementCompat>("capital-range").value.toDoubleOrNull()
            if (value != null) planner.setCapitalRange(value) else showTransientError("Jump range must be a number.")
        })
        click("calculate-capital") { syncRange(); planner.calculateCapitalRoute() }
        click("clear-capital") { planner.clearCapitalRoute() }
        click("jump-source-selected") { planner.state.selectedSystemId?.let(jumpSource::selectId) }
        click("add-jump-range") { syncRange(); planner.addJumpRange(jumpSource.selectedSystemId) }
        click("clear-jump-ranges") { planner.clearJumpRanges() }
    }

    private fun syncRange() {
        element<HTMLInputElementCompat>("capital-range").value.toDoubleOrNull()?.let(planner::setCapitalRange)
    }

    private fun renderBanner(state: WebPlannerState) {
        val text = state.error ?: state.message
        banner.textContent = text.orEmpty()
        banner.className = when {
            state.error != null -> "user-message error"
            state.message != null -> "user-message success"
            else -> "user-message hidden"
        }
    }

    private fun showTransientError(message: String) {
        banner.textContent = message
        banner.className = "user-message error"
    }

    private fun renderWaypoints(state: WebPlannerState) {
        val container = element<HTMLDivElement>("waypoint-list")
        container.clearChildren()
        if (state.normalWaypointSystemIds.isEmpty()) {
            container.appendText("No waypoints")
            container.className = "item-list empty"
            return
        }
        container.className = "item-list"
        state.normalWaypointSystemIds.forEachIndexed { index, systemId ->
            val row = document.createElement("div") as HTMLDivElement
            row.className = "list-row"
            val label = document.createElement("span")
            label.textContent = "${index + 1}. ${planner.systemName(systemId)}"
            row.appendChild(label)
            row.appendChild(actionButton("↑", index > 0) { planner.moveNormalWaypoint(index, -1) })
            row.appendChild(actionButton("↓", index < state.normalWaypointSystemIds.lastIndex) { planner.moveNormalWaypoint(index, 1) })
            row.appendChild(actionButton("×", true) { planner.removeNormalWaypoint(index) })
            container.appendChild(row)
        }
    }

    private fun renderRouteSummary(state: WebPlannerState) {
        val route = state.normalRoute
        element<HTMLElement>("route-summary").textContent = if (route == null) {
            "No active route"
        } else {
            "${route.totalJumps} jumps · ${route.stargateJumps} Stargate · ${route.ansiblexJumps} Ansiblex\n" +
                summarizeSystems(route.systems)
        }
    }

    private fun renderCapitalSummary(state: WebPlannerState) {
        val route = state.capitalRoute
        element<HTMLElement>("capital-summary").textContent = if (route == null) {
            "No active Capital route"
        } else {
            "${route.totalJumps} jumps · ${formatDouble(route.totalDistanceLy, 2)} LY\n" + summarizeSystems(route.systems)
        }
    }

    private fun renderOverlays(state: WebPlannerState) {
        val container = element<HTMLDivElement>("overlay-list")
        container.clearChildren()
        if (state.jumpOverlays.isEmpty()) {
            container.appendText("No Jump Range overlays")
            container.className = "item-list empty"
            element<HTMLElement>("coverage-summary").textContent = "Coverage: none"
            return
        }
        container.className = "item-list"
        state.jumpOverlays.forEach { overlay ->
            val row = document.createElement("div") as HTMLDivElement
            row.className = "list-row overlay-row"
            val label = document.createElement("span")
            label.textContent = "${overlay.label} · ${overlay.reachableSystemIds.size} systems"
            row.appendChild(label)
            row.appendChild(actionButton("×", true) { planner.removeJumpRange(overlay.id) })
            container.appendChild(row)
        }
        val overlap = state.coverageCounts.count { it.value > 1 }
        element<HTMLElement>("coverage-summary").textContent =
            "Coverage: ${state.coverageCounts.size} systems · $overlap overlapping"
    }

    private fun renderSystemInfo(state: WebPlannerState) {
        val target = element<HTMLDivElement>("system-info")
        target.clearChildren()
        val systemId = state.selectedSystemId
        val system = systemId?.let(universe.systemsById::get)
        if (system == null) {
            target.appendText("Select a system on the map or from Search.")
            target.className = "system-info empty"
            return
        }
        target.className = "system-info"
        val region = universe.regionsById[system.regionId]
        val constellation = universe.constellationsById[system.constellationId]
        val ansiblexCount = universe.ansiblex.count { it.firstSystemId == system.id || it.secondSystemId == system.id }
        val coverage = state.coverageCounts[system.id] ?: 0
        val title = document.createElement("h2")
        title.textContent = system.name
        target.appendChild(title)
        target.appendInfo("System ID", system.id.toString())
        target.appendInfo("Region", region?.name ?: system.regionId.toString())
        target.appendInfo("Constellation", constellation?.name ?: system.constellationId.toString())
        target.appendInfo("Security", formatDouble(system.securityStatus, 6))
        system.effectiveWormholeClassId?.let { target.appendInfo("Wormhole class", it.toString()) }
        target.appendInfo("Stargates", (universe.stargateCountBySystemId[system.id] ?: 0).toString())
        target.appendInfo("Ansiblex", ansiblexCount.toString())
        target.appendInfo("Jump coverage", coverage.toString())
        target.appendInfo("Universe XYZ", "${scientific(system.position.x)}, ${scientific(system.position.y)}, ${scientific(system.position.z)}")
        val official = universe.scene.nodesById[system.id]?.position
        target.appendInfo("Official 2D", official?.let { "${formatDouble(it.x, 2)}, ${formatDouble(it.y, 2)}" } ?: "Unavailable")
        if (official == null) {
            val warning = document.createElement("p")
            warning.className = "inline-warning"
            warning.textContent = "This system remains available to Search and route logic but has no Official 2D map position."
            target.appendChild(warning)
        }
    }

    private fun summarizeSystems(ids: List<Int>): String {
        val names = ids.take(18).joinToString(" → ", transform = planner::systemName)
        return if (ids.size > 18) "$names → … (${ids.size} systems)" else names
    }

    private fun click(id: String, action: () -> Unit) {
        element<HTMLElement>(id).addEventListener("click", { action() })
    }

    private fun actionButton(label: String, enabled: Boolean, action: () -> Unit): HTMLElement {
        val button = document.createElement("button") as HTMLElement
        button.className = "icon-button"
        button.textContent = label
        button.asDynamic().disabled = !enabled
        button.addEventListener("click", { action() })
        return button
    }
}

private class SystemPicker(
    inputId: String,
    resultsId: String,
    private val search: (String, Int) -> List<dev.evestaticmapplanner.core.model.SolarSystem>,
    private val onSelected: (dev.evestaticmapplanner.core.model.SolarSystem) -> Unit,
) {
    private val input = element<HTMLInputElementCompat>(inputId)
    private val results = element<HTMLDivElement>(resultsId)
    var selectedSystemId: Int? = null
        private set

    init {
        input.addEventListener("input", {
            selectedSystemId = null
            renderResults(input.value)
        })
        input.addEventListener("focus", { renderResults(input.value) })
        input.addEventListener("keydown", { raw ->
            if (raw.asDynamic().key == "Escape") results.classList.add("hidden")
        })
    }

    fun selectId(systemId: Int) {
        search(systemId.toString(), 1).singleOrNull()?.let(::select)
    }

    fun clear() {
        input.value = ""
        selectedSystemId = null
        results.clearChildren()
        results.classList.add("hidden")
    }

    private fun renderResults(query: String) {
        results.clearChildren()
        val matches = if (query.isBlank()) emptyList() else search(query, 12)
        matches.forEach { system ->
            val button = document.createElement("button") as HTMLElement
            button.className = "search-result"
            button.textContent = "${system.name}  ·  ${system.id}"
            button.addEventListener("click", { select(system) })
            results.appendChild(button)
        }
        if (matches.isEmpty()) results.classList.add("hidden") else results.classList.remove("hidden")
    }

    private fun select(system: dev.evestaticmapplanner.core.model.SolarSystem) {
        selectedSystemId = system.id
        input.value = system.name
        results.clearChildren()
        results.classList.add("hidden")
        onSelected(system)
    }
}

private fun HTMLDivElement.appendInfo(label: String, value: String) {
    val row = document.createElement("div") as HTMLDivElement
    row.className = "info-row"
    val key = document.createElement("span")
    key.className = "info-key"
    key.textContent = label
    val content = document.createElement("span")
    content.className = "info-value"
    content.textContent = value
    row.appendChild(key)
    row.appendChild(content)
    appendChild(row)
}

private fun HTMLDivElement.appendText(text: String) {
    appendChild(document.createTextNode(text))
}

private fun HTMLElement.clearChildren() {
    textContent = ""
}

private fun scientific(value: Double): String = value.asDynamic().toExponential(3) as String

@Suppress("UNCHECKED_CAST")
private fun <T : HTMLElement> element(id: String): T =
    requireNotNull(document.getElementById(id)) { "Missing Web UI element #$id" } as T

private typealias HTMLInputElementCompat = org.w3c.dom.HTMLInputElement
