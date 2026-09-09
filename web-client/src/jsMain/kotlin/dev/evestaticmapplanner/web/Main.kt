package dev.evestaticmapplanner.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.await
import dev.evestaticmapplanner.shared.protocol.SharedMarkerDto
import dev.evestaticmapplanner.shared.protocol.RouteHandoffDto
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

fun main() {
    window.asDynamic().startEveWebClient = { rawDocument: dynamic, loaderTimings: dynamic ->
        var startupStage = "initialization"
        try {
            setBootStatus("Building map", null)
            val kotlinStartedAt = window.performance.now()
            var stageStartedAt = kotlinStartedAt
            startupStage = "Web Pack DTO parsing"
            val documentDto = parseWebPackDocument(rawDocument)
            val dtoParseMillis = elapsedSince(stageStartedAt)
            stageStartedAt = window.performance.now()
            startupStage = "Web Pack domain conversion"
            val domain = WebUniverseDataAdapter.convert(documentDto)
            val domainConversionMillis = elapsedSince(stageStartedAt)
            stageStartedAt = window.performance.now()
            startupStage = "map scene and index construction"
            val universe = WebUniverseDataAdapter.build(domain)
            val sceneAndIndexesMillis = elapsedSince(stageStartedAt)
            lateinit var app: WebApplication
            val scope = MainScope()
            startupStage = "browser planner state restoration"
            val planner = WebPlannerController(
                universe = universe,
                onStateChanged = { state -> app.renderPlanner(state) },
            )
            val sharedMarkers = WebSharedMarkerController(
                client = BrowserSharedMarkerTransport(),
                scope = scope,
                onStateChanged = { state -> app.renderSharedMarkers(state) },
            )
            startupStage = "Web UI construction"
            app = WebApplication(
                planner = planner,
                sharedMarkers = sharedMarkers,
                scope = scope,
                loadedFromOfflineCache = loaderTimings?.offlineFallback as? Boolean ?: false,
            )
            stageStartedAt = window.performance.now()
            startupStage = "Web UI binding and first render"
            app.start()
            val uiInitializationMillis = elapsedSince(stageStartedAt)
            val diagnostics = loaderTimings ?: js("({})")
            diagnostics.dtoParseMs = dtoParseMillis
            diagnostics.dtoToDomainMs = domainConversionMillis
            diagnostics.sceneAndIndexesMs = sceneAndIndexesMillis
            diagnostics.uiInitializationMs = uiInitializationMillis
            diagnostics.kotlinTotalMs = elapsedSince(kotlinStartedAt)
            diagnostics.readyMs = roundMillis(window.performance.now())
            window.asDynamic().eveWebPerformance = diagnostics
            window.asDynamic().eveWebClientDiagnostics = { app.diagnostics() }
            setBootStatus("Ready", null)
            document.documentElement?.classList?.add("app-ready")
        } catch (failure: Throwable) {
            setBootStatus("Web client failed", "$startupStage: ${failure.message ?: failure.toString()}")
        }
    }
}

private fun elapsedSince(startedAt: Double): Double = roundMillis(window.performance.now() - startedAt)

private fun roundMillis(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

private fun setBootStatus(status: String, error: String?) {
    document.getElementById("boot-status")?.textContent = status
    document.getElementById("fatal-error")?.apply {
        textContent = error.orEmpty()
        if (error == null) classList.add("hidden") else classList.remove("hidden")
    }
}

private class WebApplication(
    private val planner: WebPlannerController,
    private val sharedMarkers: WebSharedMarkerController,
    private val scope: CoroutineScope,
    loadedFromOfflineCache: Boolean,
) {
    private val universe = planner.universe
    private val banner = element<HTMLDivElement>("user-message")
    private val notifications = WebTransientNotificationController(scope, ::renderBanner)
    private val mapView = WebMapView(
        canvas = element<HTMLCanvasElement>("map-canvas"),
        scene = universe.scene,
        onSelect = { systemId ->
            planner.selectSystem(systemId)
            sharedMarkers.selectMarkerAtSystem(systemId)
        },
        onHover = planner::hoverSystem,
        onContextAction = ::openSystemActions,
        ansiblexLinks = planner::enabledAnsiblexLinks,
    )
    private val search = SystemPicker("global-search", "global-results", planner::search) { system ->
        planner.selectSystem(system.id)
        sharedMarkers.selectMarkerAtSystem(system.id)
        if (!mapView.centerOn(system.id)) showTransientError("${system.name} has no Official 2D position.")
    }
    private val routeFrom = SystemPicker("route-from", "route-from-results", planner::search) { planner.setNormalStart(it.id) }
    private val routeTo = SystemPicker("route-to", "route-to-results", planner::search) { planner.setNormalDestination(it.id) }
    private val waypoint = SystemPicker("route-waypoint", "route-waypoint-results", planner::search) { system ->
        planner.addNormalWaypoint(system.id)
    }
    private val capitalFrom = SystemPicker("capital-from", "capital-from-results", planner::search) { planner.setCapitalStart(it.id) }
    private val capitalTo = SystemPicker("capital-to", "capital-to-results", planner::search) { planner.setCapitalDestination(it.id) }
    private val capitalWaypoint = SystemPicker("capital-waypoint", "capital-waypoint-results", planner::search) { system ->
        planner.addCapitalWaypoint(system.id)
    }
    private val jumpSource = SystemPicker("jump-source", "jump-source-results", planner::search) { _ -> }
    private var editingMarkerId: String? = null
    private var editingSystemId: Int? = null
    private var contextSystemId: Int? = null
    private var online = browserOnline() && !loadedFromOfflineCache
    private var activeTool = "search"

    fun start() {
        element<HTMLElement>("pack-meta").textContent =
            "SDE ${universe.metadata.sdeBuild} · Pack ${universe.metadata.packVersion} · Desktop ${universe.metadata.desktopAppVersion}"
        element<HTMLElement>("map-stats").textContent =
            "${universe.staticData.systems.size} systems · ${universe.staticData.connections.size} Stargates · " +
                "${universe.ansiblex.size} Ansiblex · ${universe.scene.omittedSystemIds.size} unpositioned"
        bindControls()
        bindResponsiveUi()
        updateVisualViewport()
        renderConnectivity()
        restoreSharedServerUrl()
        renderPlanner(planner.state)
        renderSharedMarkers(sharedMarkers.state)
        mapView.fit()
    }

    fun diagnostics(): dynamic {
        val value = mapView.diagnostics()
        value.online = online
        value.activeTool = activeTool
        value.toolsOpen = document.documentElement?.classList?.contains("tools-open") == true
        value.infoOpen = document.documentElement?.classList?.contains("info-open") == true
        value.contextMenuOpen = !element<HTMLElement>("system-actions").classList.contains("hidden")
        value.sharedMarkerEditorOpen = !element<HTMLElement>("shared-marker-editor").classList.contains("hidden")
        return value
    }

    fun renderPlanner(state: WebPlannerState) {
        mapView.update(state, sharedMarkers.state)
        notifications.accept(state)
        renderWaypoints(state)
        renderRouteSummary(state)
        renderCapitalSummary(state)
        renderCapitalWaypoints(state)
        renderOverlays(state)
        renderMapPreferences(state)
        renderPersonalAnsiblex(state)
        renderSystemInfo(state)
        syncPlannerFields(state)
        element<HTMLInputElementCompat>("use-ansiblex").checked = state.useAnsiblex
    }

    fun renderSharedMarkers(state: WebSharedMarkerState) {
        mapView.update(planner.state, state)
        renderSharedMarkerPanel(state)
        renderRouteHandoffs(state)
        renderSystemInfo(planner.state)
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
        click("capital-waypoint-selected") { planner.state.selectedSystemId?.let(planner::addCapitalWaypoint) }
        element<HTMLInputElementCompat>("capital-range").addEventListener("change", {
            val value = element<HTMLInputElementCompat>("capital-range").value.toDoubleOrNull()
            if (value != null) planner.setCapitalRange(value) else showTransientError("Jump range must be a number.")
        })
        click("calculate-capital") { syncRange(); planner.calculateCapitalRoute() }
        click("clear-capital") { planner.clearCapitalRoute() }
        click("jump-source-selected") { planner.state.selectedSystemId?.let(jumpSource::selectId) }
        element<HTMLInputElementCompat>("coverage-range").addEventListener("change", {
            syncCoverageRange()
        })
        click("add-jump-range") {
            if (syncCoverageRange()) planner.addJumpRange(jumpSource.selectedSystemId)
        }
        click("clear-jump-ranges") { planner.clearJumpRanges() }
        click("save-map-lod") {
            val constellation = element<HTMLInputElementCompat>("constellation-threshold").value.toDoubleOrNull()
            val system = element<HTMLInputElementCompat>("system-threshold").value.toDoubleOrNull()
            if (constellation == null || system == null) showTransientError("LOD thresholds must be numbers.")
            else planner.setMapThresholds(constellation, system)
        }
        click("reset-map-lod") { planner.resetMapThresholds() }
        element<HTMLInputElementCompat>("personal-ansiblex-file").addEventListener("change", {
            val input = element<HTMLInputElementCompat>("personal-ansiblex-file")
            val file = input.files?.item(0) ?: return@addEventListener
            scope.launch {
                val content = try {
                    (file.asDynamic().text() as Promise<String>).await()
                } catch (_: Throwable) {
                    showTransientError("Unable to read Personal Ansiblex file.")
                    return@launch
                }
                planner.previewPersonalAnsiblex(file.name, content)
            }
        })
        click("apply-personal-ansiblex") { planner.applyPersonalAnsiblexPreview() }
        click("cancel-personal-ansiblex") { planner.cancelPersonalAnsiblexPreview() }
        click("clear-personal-ansiblex") {
            if (window.confirm("Clear all browser-local Personal Ansiblex? Web Pack links are preserved.")) {
                planner.clearPersonalAnsiblex()
            }
        }
        click("shared-connect") {
            val serverUrl = element<HTMLInputElementCompat>("shared-server-url").value
            val invite = element<HTMLInputElementCompat>("shared-invite-code").value
            val deviceName = element<HTMLInputElementCompat>("shared-device-name").value
            element<HTMLInputElementCompat>("shared-invite-code").value = ""
            scope.launch {
                sharedMarkers.connect(serverUrl, invite, deviceName)
                sharedMarkers.state.serverOrigin?.takeIf { sharedMarkers.state.status == WebSharedMarkerStatus.CONNECTED }
                    ?.let(::saveSharedServerUrl)
            }
        }
        click("shared-disconnect") { sharedMarkers.disconnect() }
        click("shared-refresh-routes") { scope.launch { sharedMarkers.refreshNow() } }
        click("shared-create-selected") {
            val systemId = planner.state.selectedSystemId
            if (systemId == null) showTransientError("Select a system before creating a Shared Marker.")
            else openMarkerEditor(systemId, null)
        }
        click("shared-editor-cancel") { closeMarkerEditor() }
        click("shared-editor-save") { saveMarkerEditor() }
        click("shared-editor-delete") { deleteMarkerEditor() }
        click("system-actions-close") { closeSystemActions() }
        click("action-route-from") { useContextSystem("route") { routeFrom.selectId(it) } }
        click("action-route-to") { useContextSystem("route") { routeTo.selectId(it) } }
        click("action-waypoint") { useContextSystem("route", planner::addNormalWaypoint) }
        click("action-jump-range") { useContextSystem("coverage") { systemId ->
            jumpSource.selectId(systemId)
            syncCoverageRange()
            planner.addJumpRange(systemId)
        } }
        click("action-shared-marker") { useContextSystem("shared") { systemId -> openMarkerEditor(systemId, null) } }
        click("action-keepstar-marker") { useContextSystem("search", planner::toggleKeepstarSavedMarker) }
        document.addEventListener("visibilitychange", {
            if (document.asDynamic().visibilityState == "visible") sharedMarkers.onPageVisible()
        })
        document.addEventListener("keydown", { raw ->
            if (raw.asDynamic().key == "Escape") {
                closeSystemActions()
                closePanels()
                if (!element<HTMLElement>("shared-marker-editor").classList.contains("hidden")) closeMarkerEditor()
            }
        })
        element<HTMLElement>("shared-marker-editor").addEventListener("click", { raw ->
            if (raw.target == element<HTMLElement>("shared-marker-editor")) closeMarkerEditor()
        })
        window.addEventListener("pagehide", {
            notifications.close()
            sharedMarkers.close()
        })
    }

    private fun bindResponsiveUi() {
        click("tools-toggle") { togglePanel("tools") }
        click("info-toggle") { togglePanel("info") }
        click("tools-close") { closePanels() }
        click("info-close") { closePanels() }
        click("panel-scrim") { closePanels() }
        val tabs = document.querySelectorAll("[data-tool-tab]")
        for (index in 0 until tabs.length) {
            val tab = tabs.item(index) as? HTMLElement ?: continue
            tab.addEventListener("click", { selectTool(tab.getAttribute("data-tool-tab") ?: "search") })
        }
        selectTool(activeTool)

        window.addEventListener("online", {
            online = true
            renderConnectivity()
            sharedMarkers.onPageVisible()
        })
        window.addEventListener("offline", {
            online = false
            renderConnectivity()
        })
        window.addEventListener("orientationchange", {
            updateVisualViewport()
            window.setTimeout({ mapView.resize() }, PANEL_TRANSITION_MILLIS)
        })
        val visualViewport = window.asDynamic().visualViewport
        if (visualViewport != null) {
            val viewport = visualViewport
            viewport.addEventListener("resize", { updateVisualViewport() })
            viewport.addEventListener("scroll", { updateVisualViewport() })
        }
        document.addEventListener("focusin", { raw ->
            val target = raw.target as? HTMLElement ?: return@addEventListener
            if (target.asDynamic().matches("input, select, textarea") == true) {
                window.setTimeout({
                    target.asDynamic().scrollIntoView(js("({ block: 'center', inline: 'nearest', behavior: 'smooth' })"))
                }, KEYBOARD_SCROLL_DELAY_MILLIS)
            }
        })
    }

    private fun togglePanel(panel: String) {
        val root = document.documentElement ?: return
        val className = "${panel}-open"
        if (root.classList.contains(className)) closePanels() else {
            root.classList.toggle("tools-open", panel == "tools")
            root.classList.toggle("info-open", panel == "info")
            element<HTMLElement>("panel-scrim").classList.remove("hidden")
            updatePanelAria()
        }
        window.setTimeout({ mapView.resize() }, PANEL_TRANSITION_MILLIS)
    }

    private fun closePanels() {
        document.documentElement?.classList?.remove("tools-open", "info-open")
        element<HTMLElement>("panel-scrim").classList.add("hidden")
        updatePanelAria()
    }

    private fun updatePanelAria() {
        val root = document.documentElement
        element<HTMLElement>("tools-toggle").setAttribute("aria-expanded", (root?.classList?.contains("tools-open") == true).toString())
        element<HTMLElement>("info-toggle").setAttribute("aria-expanded", (root?.classList?.contains("info-open") == true).toString())
    }

    private fun selectTool(tool: String) {
        activeTool = tool
        val panels = document.querySelectorAll("[data-tool-panel]")
        for (index in 0 until panels.length) {
            val panel = panels.item(index) as? HTMLElement ?: continue
            panel.classList.toggle("tool-section-active", panel.getAttribute("data-tool-panel") == tool)
        }
        val tabs = document.querySelectorAll("[data-tool-tab]")
        for (index in 0 until tabs.length) {
            val tab = tabs.item(index) as? HTMLElement ?: continue
            val selected = tab.getAttribute("data-tool-tab") == tool
            tab.setAttribute("aria-pressed", selected.toString())
            tab.classList.toggle("secondary", !selected)
        }
    }

    private fun updateVisualViewport() {
        val visualViewport = window.asDynamic().visualViewport
        val height = ((visualViewport?.height ?: window.innerHeight) as Number).toDouble().coerceAtLeast(1.0)
        (document.documentElement as? HTMLElement)?.style?.setProperty("--visual-viewport-height", "${height}px")
        mapView.resize()
    }

    private fun renderConnectivity() {
        element<HTMLElement>("network-status").apply {
            textContent = if (online) "Online" else "Offline"
            className = "network-status ${if (online) "online" else "offline"}"
        }
        renderSharedMarkerPanel(sharedMarkers.state)
        renderRouteHandoffs(sharedMarkers.state)
    }

    private fun syncRange() {
        element<HTMLInputElementCompat>("capital-range").value.toDoubleOrNull()?.let(planner::setCapitalRange)
    }

    private fun syncCoverageRange(): Boolean {
        val value = element<HTMLInputElementCompat>("coverage-range").value.toDoubleOrNull()
        if (value == null) {
            showTransientError("Coverage range must be a number.")
            return false
        }
        return planner.setCoverageRange(value)
    }

    private fun renderBanner(view: WebTransientNotificationView) {
        banner.textContent = view.text.orEmpty()
        banner.className = when (view.phase) {
            WebTransientNotificationPhase.HIDDEN -> "user-message hidden"
            WebTransientNotificationPhase.VISIBLE -> when (view.kind) {
                WebTransientNotificationKind.ERROR -> "user-message error"
                WebTransientNotificationKind.INFORMATION -> "user-message success"
            }
            WebTransientNotificationPhase.FADING -> when (view.kind) {
                WebTransientNotificationKind.ERROR -> "user-message error fading"
                WebTransientNotificationKind.INFORMATION -> "user-message success fading"
            }
        }
    }

    private fun showTransientError(message: String) {
        notifications.showError(message)
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
            row.appendChild(actionButton("↑", index > 0, "Move waypoint up") { planner.moveNormalWaypoint(index, -1) })
            row.appendChild(actionButton("↓", index < state.normalWaypointSystemIds.lastIndex, "Move waypoint down") { planner.moveNormalWaypoint(index, 1) })
            row.appendChild(actionButton("×", true, "Remove waypoint") { planner.removeNormalWaypoint(index) })
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

    private fun renderCapitalWaypoints(state: WebPlannerState) {
        val container = element<HTMLDivElement>("capital-waypoint-list")
        container.clearChildren()
        if (state.capitalWaypointSystemIds.isEmpty()) {
            container.appendText("No Capital waypoints")
            container.className = "item-list empty"
            return
        }
        container.className = "item-list"
        state.capitalWaypointSystemIds.forEachIndexed { index, systemId ->
            val row = document.createElement("div") as HTMLDivElement
            row.className = "list-row"
            val label = document.createElement("span")
            label.textContent = "${index + 1}. ${planner.systemName(systemId)}"
            row.appendChild(label)
            row.appendChild(actionButton("↑", index > 0, "Move Capital waypoint up") { planner.moveCapitalWaypoint(index, -1) })
            row.appendChild(actionButton("↓", index < state.capitalWaypointSystemIds.lastIndex, "Move Capital waypoint down") { planner.moveCapitalWaypoint(index, 1) })
            row.appendChild(actionButton("×", true, "Remove Capital waypoint") { planner.removeCapitalWaypoint(index) })
            container.appendChild(row)
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
            row.appendChild(actionButton("×", true, "Remove ${overlay.label}") { planner.removeJumpRange(overlay.id) })
            container.appendChild(row)
        }
        val overlap = state.coverageCounts.count { it.value > 1 }
        element<HTMLElement>("coverage-summary").textContent =
            "Coverage: ${state.coverageCounts.size} systems · $overlap overlapping"
    }

    private fun renderMapPreferences(state: WebPlannerState) {
        syncInputUnlessEditing("constellation-threshold", state.mapPreferences.constellationZoomThreshold.toString())
        syncInputUnlessEditing("system-threshold", state.mapPreferences.systemZoomThreshold.toString())
    }

    private fun syncPlannerFields(state: WebPlannerState) {
        routeFrom.displayId(state.normalStartSystemId)
        routeTo.displayId(state.normalDestinationSystemId)
        capitalFrom.displayId(state.capitalStartSystemId)
        capitalTo.displayId(state.capitalDestinationSystemId)
        syncInputUnlessEditing("capital-range", state.capitalRangeLy.toString())
        syncInputUnlessEditing("coverage-range", state.coverageRangeLy.toString())
    }

    private fun syncInputUnlessEditing(inputId: String, value: String) {
        val input = element<HTMLInputElementCompat>(inputId)
        if (document.activeElement != input && input.value != value) input.value = value
    }

    private fun renderPersonalAnsiblex(state: WebPlannerState) {
        val preview = state.personalAnsiblexPreview
        element<HTMLElement>("personal-ansiblex-preview").apply {
            textContent = preview?.let {
                "Preview · ${it.valid.size} valid · ${it.duplicateRows.size} duplicate · ${it.errors.size} invalid" +
                    it.errors.take(5).joinToString(separator = "", prefix = "\n") { error -> "Row ${error.row}: ${error.message}" }
            }.orEmpty()
            className = if (preview == null) "summary hidden" else "summary"
        }
        element<HTMLElement>("apply-personal-ansiblex").asDynamic().disabled = preview?.canApply != true
        element<HTMLElement>("cancel-personal-ansiblex").asDynamic().disabled = preview == null
        element<HTMLElement>("clear-personal-ansiblex").asDynamic().disabled = state.personalAnsiblex.isEmpty()
        val container = element<HTMLDivElement>("personal-ansiblex-list")
        container.clearChildren()
        if (state.personalAnsiblex.isEmpty()) {
            container.appendText("No Personal Ansiblex")
            container.className = "item-list empty"
        } else {
            container.className = "item-list"
            state.personalAnsiblex.forEach { connection ->
                val row = document.createElement("div") as HTMLDivElement
                row.className = "list-row"
                val logicalFrom = if (connection.direction == WebAnsiblexDirection.SECOND_TO_FIRST) {
                    connection.secondSystemId
                } else connection.firstSystemId
                val logicalTo = if (connection.direction == WebAnsiblexDirection.SECOND_TO_FIRST) {
                    connection.firstSystemId
                } else connection.secondSystemId
                val label = document.createElement("span") as HTMLElement
                val arrow = if (connection.direction == WebAnsiblexDirection.BIDIRECTIONAL) "↔" else "→"
                label.textContent = "${planner.systemName(logicalFrom)} $arrow ${planner.systemName(logicalTo)}"
                row.appendChild(label)
                row.appendChild(actionButton(if (connection.enabled) "On" else "Off", true) {
                    planner.setPersonalAnsiblexEnabled(connection.id, !connection.enabled)
                })
                row.appendChild(actionButton("×", true, "Remove Personal Ansiblex") {
                    planner.removePersonalAnsiblex(connection.id)
                })
                container.appendChild(row)
            }
        }
        element<HTMLElement>("map-stats").textContent =
            "${universe.staticData.systems.size} systems · ${universe.staticData.connections.size} Stargates · " +
                "${universe.ansiblex.count { it.enabled }} Pack Ansiblex · " +
                "${state.personalAnsiblex.count { it.enabled }} Personal Ansiblex · ${universe.scene.omittedSystemIds.size} unpositioned"
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
        val ansiblexCount = planner.enabledAnsiblexLinks().count {
            it.firstSystemId == system.id || it.secondSystemId == system.id
        }
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
        target.appendInfo("Saved Marker type", if (system.id in state.keepstarSystemIds) "keepstar" else "None")
        target.appendInfo("Universe XYZ", "${scientific(system.position.x)}, ${scientific(system.position.y)}, ${scientific(system.position.z)}")
        val official = universe.scene.nodesById[system.id]?.position
        target.appendInfo("Official 2D", official?.let { "${formatDouble(it.x, 2)}, ${formatDouble(it.y, 2)}" } ?: "Unavailable")
        sharedMarkers.state.markersBySystemId[system.id]?.let { marker ->
            val heading = document.createElement("h3")
            heading.textContent = "Shared Marker"
            target.appendChild(heading)
            target.appendInfo("Marker", marker.name)
            target.appendInfo("Color", marker.color.lowercase().replaceFirstChar(Char::uppercase))
            target.appendInfo("Tags", marker.tags.joinToString().ifEmpty { "None" })
            target.appendInfo("Notes", marker.notes ?: "None")
            target.appendInfo("Updated by", marker.updatedBy.displayName)
            target.appendInfo("Version", marker.version.toString())
        }
        if (official == null) {
            val warning = document.createElement("p")
            warning.className = "inline-warning"
            warning.textContent = "This system remains available to Search and route logic but has no Official 2D map position."
            target.appendChild(warning)
        }
    }

    private fun renderSharedMarkerPanel(state: WebSharedMarkerState) {
        element<HTMLElement>("shared-status").apply {
            textContent = if (!online) "Offline · Shared Marker unavailable" else when (state.status) {
                WebSharedMarkerStatus.DISCONNECTED -> "Disconnected"
                WebSharedMarkerStatus.CONNECTING -> "Connecting"
                WebSharedMarkerStatus.CONNECTED -> "Connected · ${state.workspace?.name ?: "Workspace"} · ${state.workspace?.role}"
                WebSharedMarkerStatus.RECONNECTING -> "Reconnecting · attempt ${state.reconnectAttempt}"
                WebSharedMarkerStatus.AUTH_FAILED -> "Auth failed"
                WebSharedMarkerStatus.FORBIDDEN -> "Access removed"
                WebSharedMarkerStatus.FAILED -> "Connection failed"
            }
            className = "shared-status ${if (online) state.status.name.lowercase() else "offline"}"
        }
        element<HTMLElement>("shared-error").apply {
            val detail = state.requestId?.let { " · Request $it" }.orEmpty()
            textContent = state.error?.plus(detail).orEmpty()
            className = if (state.error == null) "inline-error hidden" else "inline-error"
        }
        element<HTMLElement>("shared-connect").asDynamic().disabled =
            !online || state.status == WebSharedMarkerStatus.CONNECTING || state.busy
        element<HTMLElement>("shared-disconnect").asDynamic().disabled =
            state.status == WebSharedMarkerStatus.DISCONNECTED
        element<HTMLElement>("shared-create-selected").asDynamic().disabled =
            !online || !state.canWrite || planner.state.selectedSystemId == null
        element<HTMLElement>("shared-editor-save").asDynamic().disabled = !online || state.busy
        element<HTMLElement>("shared-editor-delete").asDynamic().disabled = !online || state.busy
        element<HTMLElement>("action-shared-marker").asDynamic().disabled = !online || !state.canWrite
        element<HTMLElement>("shared-refresh-routes").asDynamic().disabled =
            !online || state.status != WebSharedMarkerStatus.CONNECTED || state.busy

        val container = element<HTMLDivElement>("shared-marker-list")
        container.clearChildren()
        val rows = state.markers.values.sortedWith(
            compareBy<SharedMarkerDto>({ planner.systemName(it.systemId).lowercase() }, SharedMarkerDto::markerId),
        )
        if (rows.isEmpty()) {
            container.appendText(if (state.status == WebSharedMarkerStatus.CONNECTED) "No Shared Markers" else "Connect to load markers")
            container.className = "item-list empty"
            return
        }
        container.className = "item-list"
        rows.forEach { marker ->
            val row = document.createElement("div") as HTMLDivElement
            row.className = "list-row shared-marker-row" + if (marker.markerId == state.selectedMarkerId) " selected" else ""
            val swatch = document.createElement("i") as HTMLElement
            swatch.className = "marker-swatch marker-${marker.color.lowercase()}"
            row.appendChild(swatch)
            val label = document.createElement("button") as HTMLElement
            label.className = "list-link"
            val knownSystem = universe.systemsById[marker.systemId]
            label.textContent = "${marker.name} · ${knownSystem?.name ?: "Unknown System (${marker.systemId})"}"
            label.addEventListener("click", { locateMarker(marker) })
            row.appendChild(label)
            row.appendChild(actionButton("Edit", state.canWrite) { openMarkerEditor(marker.systemId, marker) })
            container.appendChild(row)
        }
    }

    private fun renderRouteHandoffs(state: WebSharedMarkerState) {
        val status = element<HTMLElement>("desktop-route-status")
        val container = element<HTMLDivElement>("desktop-route-list")
        container.clearChildren()
        when {
            state.status != WebSharedMarkerStatus.CONNECTED -> {
                status.textContent = "Connect to check for Desktop routes."
                container.appendText("No Desktop routes loaded")
                container.className = "item-list empty"
                return
            }
            !state.supportsRouteHandoffs -> {
                status.textContent = "This server does not support Desktop Route Handoff."
                container.appendText("Upgrade the Shared Map Server to load Desktop routes.")
                container.className = "item-list empty"
                return
            }
            state.routeHandoffs.isEmpty() -> {
                status.textContent = "No Desktop Route Available. Publish one from the Desktop planner."
                container.appendText("No recent Desktop routes")
                container.className = "item-list empty"
                return
            }
            else -> status.textContent = "Desktop Route Available · choose Load to replace the matching Web route."
        }
        container.className = "item-list"
        state.routeHandoffs.forEach { handoff ->
            val row = document.createElement("div") as HTMLDivElement
            row.className = "list-row route-handoff-row"
            val label = document.createElement("span") as HTMLElement
            label.textContent = routeHandoffSummary(handoff)
            row.appendChild(label)
            row.appendChild(actionButton("Load", true, "Load Desktop ${handoff.type.lowercase()} route") {
                planner.loadRouteHandoff(handoff)
                selectTool(if (handoff.type == "CAPITAL") "capital" else "route")
            })
            container.appendChild(row)
        }
    }

    private fun routeHandoffSummary(handoff: RouteHandoffDto): String {
        val type = handoff.type.lowercase().replaceFirstChar(Char::uppercase)
        val from = planner.systemName(handoff.originSystemId)
        val to = planner.systemName(handoff.destinationSystemId)
        val waypointText = if (handoff.waypointSystemIds.isEmpty()) "direct intent" else "${handoff.waypointSystemIds.size} waypoint(s)"
        return "$type · $from → $to · $waypointText\n${handoff.publisher.displayName} · ${handoff.createdAt}"
    }

    private fun locateMarker(marker: SharedMarkerDto) {
        sharedMarkers.selectMarker(marker.markerId)
        when (universe.sharedMarkerLocationAvailability(marker.systemId)) {
            SharedMarkerLocationAvailability.UNKNOWN_SYSTEM -> {
                showTransientError("${marker.name} references a system not present in this Web Pack.")
                return
            }
            SharedMarkerLocationAvailability.UNPOSITIONED -> {
                planner.selectSystem(marker.systemId)
                showTransientError("${planner.systemName(marker.systemId)} has no Official 2D position; the marker remains in the list.")
                return
            }
            SharedMarkerLocationAvailability.POSITIONED -> Unit
        }
        planner.selectSystem(marker.systemId)
        check(mapView.centerOn(marker.systemId)) { "Positioned system ${marker.systemId} is missing from the scene" }
    }

    private fun openSystemActions(systemId: Int, point: dev.evestaticmapplanner.core.map.MapPoint, pointerType: String) {
        contextSystemId = systemId
        planner.selectSystem(systemId)
        sharedMarkers.selectMarkerAtSystem(systemId)
        element<HTMLElement>("system-actions-name").textContent = planner.systemName(systemId)
        element<HTMLElement>("action-keepstar-marker").textContent =
            if (systemId in planner.state.keepstarSystemIds) "Remove Keepstar Saved Marker" else "Add Keepstar Saved Marker"
        element<HTMLElement>("action-shared-marker").asDynamic().disabled = !online || !sharedMarkers.state.canWrite
        val canvasBounds = element<HTMLCanvasElement>("map-canvas").getBoundingClientRect()
        val viewportX = canvasBounds.left + point.x
        val viewportY = canvasBounds.top + point.y
        element<HTMLElement>("system-actions").apply {
            className = "context-sheet ${if (pointerType == "mouse") "mouse" else "touch"}"
            style.left = "${viewportX.coerceIn(8.0, (window.innerWidth.toDouble() - CONTEXT_SHEET_WIDTH_PX).coerceAtLeast(8.0))}px"
            style.top = "${viewportY.coerceIn(8.0, (window.innerHeight.toDouble() - CONTEXT_SHEET_HEIGHT_PX).coerceAtLeast(8.0))}px"
        }
    }

    private fun closeSystemActions() {
        contextSystemId = null
        element<HTMLElement>("system-actions").classList.add("hidden")
    }

    private fun useContextSystem(tool: String, action: (Int) -> Unit) {
        val systemId = contextSystemId ?: return
        closeSystemActions()
        selectTool(tool)
        action(systemId)
        if (tool != "shared" && usesOverlayPanels() && document.documentElement?.classList?.contains("tools-open") != true) {
            togglePanel("tools")
        }
    }

    private fun openMarkerEditor(systemId: Int, marker: SharedMarkerDto?) {
        if (!online || !sharedMarkers.state.canWrite) {
            showTransientError("Shared Marker editing requires an online EDITOR or ADMIN connection.")
            return
        }
        editingMarkerId = marker?.markerId
        editingSystemId = systemId
        element<HTMLElement>("shared-editor-title").textContent =
            if (marker == null) "Create Shared Marker" else "Edit Shared Marker"
        element<HTMLElement>("shared-editor-system").textContent =
            universe.systemsById[systemId]?.let { "${it.name} · $systemId" } ?: "Unknown System · $systemId"
        element<HTMLInputElementCompat>("shared-marker-name").value = marker?.name.orEmpty()
        element<org.w3c.dom.HTMLSelectElement>("shared-marker-color").value = marker?.color ?: "BLUE"
        element<HTMLInputElementCompat>("shared-marker-tags").value = marker?.tags?.joinToString(", ").orEmpty()
        element<org.w3c.dom.HTMLTextAreaElement>("shared-marker-notes").value = marker?.notes.orEmpty()
        element<HTMLElement>("shared-editor-delete").classList.toggle("hidden", marker == null)
        element<HTMLElement>("shared-marker-editor").classList.remove("hidden")
        window.setTimeout({ element<HTMLInputElementCompat>("shared-marker-name").focus() }, EDITOR_FOCUS_DELAY_MILLIS)
    }

    private fun saveMarkerEditor() {
        val systemId = editingSystemId ?: return
        val markerId = editingMarkerId
        val draft = WebSharedMarkerDraft(
            name = element<HTMLInputElementCompat>("shared-marker-name").value,
            color = element<org.w3c.dom.HTMLSelectElement>("shared-marker-color").value,
            tags = element<HTMLInputElementCompat>("shared-marker-tags").value.split(',').map(String::trim).filter(String::isNotEmpty),
            notes = element<org.w3c.dom.HTMLTextAreaElement>("shared-marker-notes").value,
        )
        scope.launch {
            if (markerId == null) {
                sharedMarkers.createMarker(systemId, draft)
            } else {
                val current = sharedMarkers.state.markers[markerId]
                if (current == null) {
                    showTransientError("This Shared Marker was removed by another client.")
                    closeMarkerEditor()
                    return@launch
                }
                sharedMarkers.updateMarker(markerId, current.version, draft)
            }
            if (sharedMarkers.state.error == null) closeMarkerEditor()
        }
    }

    private fun deleteMarkerEditor() {
        val markerId = editingMarkerId ?: return
        val marker = sharedMarkers.state.markers[markerId] ?: return
        if (!window.confirm("Delete Shared Marker '${marker.name}'? This affects every connected client.")) return
        scope.launch {
            sharedMarkers.deleteMarker(marker.markerId, marker.version)
            if (sharedMarkers.state.error == null) closeMarkerEditor()
        }
    }

    private fun closeMarkerEditor() {
        editingMarkerId = null
        editingSystemId = null
        element<HTMLElement>("shared-marker-editor").classList.add("hidden")
    }

    private fun restoreSharedServerUrl() {
        val saved = runCatching { window.localStorage.getItem(SHARED_SERVER_STORAGE_KEY) }.getOrNull()
        if (!saved.isNullOrBlank()) element<HTMLInputElementCompat>("shared-server-url").value = saved
    }

    private fun saveSharedServerUrl(origin: String) {
        runCatching { window.localStorage.setItem(SHARED_SERVER_STORAGE_KEY, origin) }
    }

    private fun summarizeSystems(ids: List<Int>): String {
        val names = ids.take(18).joinToString(" → ", transform = planner::systemName)
        return if (ids.size > 18) "$names → … (${ids.size} systems)" else names
    }

    private fun click(id: String, action: () -> Unit) {
        element<HTMLElement>(id).addEventListener("click", { action() })
    }

    private fun actionButton(label: String, enabled: Boolean, ariaLabel: String = label, action: () -> Unit): HTMLElement {
        val button = document.createElement("button") as HTMLElement
        button.className = "icon-button"
        button.textContent = label
        button.setAttribute("aria-label", ariaLabel)
        button.asDynamic().disabled = !enabled
        button.addEventListener("click", { action() })
        return button
    }
}

private const val SHARED_SERVER_STORAGE_KEY = "eve-static-map-planner.shared-marker.server-origin"
private const val PANEL_TRANSITION_MILLIS = 220
private const val KEYBOARD_SCROLL_DELAY_MILLIS = 180
private const val EDITOR_FOCUS_DELAY_MILLIS = 80
private const val CONTEXT_SHEET_WIDTH_PX = 270.0
private const val CONTEXT_SHEET_HEIGHT_PX = 300.0

private fun browserOnline(): Boolean = window.navigator.asDynamic().onLine as? Boolean ?: true

private fun usesOverlayPanels(): Boolean = window.matchMedia("(max-width: 1280px), (pointer: coarse)").matches

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

    fun displayId(systemId: Int?) {
        if (systemId == selectedSystemId) return
        if (systemId == null) {
            clear()
            return
        }
        val system = search(systemId.toString(), 1).singleOrNull() ?: return
        selectedSystemId = system.id
        input.value = system.name
        results.clearChildren()
        results.classList.add("hidden")
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
