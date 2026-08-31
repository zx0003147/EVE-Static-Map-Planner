package dev.evestaticmapplanner

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.capital.CapitalRouteViewModel
import dev.evestaticmapplanner.control.AppMapControlCoordinator
import dev.evestaticmapplanner.control.AppWormholeControlAdapter
import dev.evestaticmapplanner.control.AiSavedMarkerControlAdapter
import dev.evestaticmapplanner.control.AiMapControlLifecycleController
import dev.evestaticmapplanner.control.AppAiControlSession
import dev.evestaticmapplanner.control.AppLocalControlAuditSink
import dev.evestaticmapplanner.control.ExistingPlanningPorts
import dev.evestaticmapplanner.control.MapViewportControlAdapter
import dev.evestaticmapplanner.control.MissionMapStateStore
import dev.evestaticmapplanner.control.RepositorySystemReadPort
import dev.evestaticmapplanner.control.transport.LocalControlServer
import dev.evestaticmapplanner.featurepack.FeaturePackRuntimeValidation
import dev.evestaticmapplanner.featurepack.FeaturePackRuntimeValidationArguments
import dev.evestaticmapplanner.featurepack.FeaturePackManagerViewModel
import dev.evestaticmapplanner.featurepack.ProductionFeaturePackRuntime
import dev.evestaticmapplanner.core.repository.CachingStaticMapRepository
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportService
import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import dev.evestaticmapplanner.data.repository.SqliteSavedMarkerRepository
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.map.MapViewModel
import dev.evestaticmapplanner.map.StaticMapScreen
import dev.evestaticmapplanner.marker.MarkerViewModel
import dev.evestaticmapplanner.marker.MarkerManagerWindow
import dev.evestaticmapplanner.marker.application.SavedMarkerService
import dev.evestaticmapplanner.marker.application.AiSavedMarkerApplicationService
import dev.evestaticmapplanner.marker.application.AiSavedMarkerPermissionPolicy
import dev.evestaticmapplanner.mcp.LocalhostMcpHost
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.preferences.PreferencesWindow
import dev.evestaticmapplanner.preferences.OverlayVisibilityFilter
import dev.evestaticmapplanner.preferences.PropertiesPreferencesStore
import dev.evestaticmapplanner.route.RoutePlannerViewModel
import dev.evestaticmapplanner.sde.update.JdkSdeHttpTransport
import dev.evestaticmapplanner.sde.update.LatestBuildCacheStore
import dev.evestaticmapplanner.sde.update.ManagedStaticDataPaths
import dev.evestaticmapplanner.sde.update.PendingUpdateActivator
import dev.evestaticmapplanner.sde.update.PendingUpdateStore
import dev.evestaticmapplanner.sde.update.SafeSdeArchiveExtractor
import dev.evestaticmapplanner.sde.update.SdeArchiveDownloader
import dev.evestaticmapplanner.sde.update.SdeCandidatePreparer
import dev.evestaticmapplanner.sde.update.SdeUpdateClient
import dev.evestaticmapplanner.sde.update.SdeUpdateService
import dev.evestaticmapplanner.staticdata.StaticDataBootstrapScreen
import dev.evestaticmapplanner.staticdata.StaticDataManagerDialog
import dev.evestaticmapplanner.staticdata.StaticDataManagerViewModel
import dev.evestaticmapplanner.view.PlanningViewCoordinator
import dev.evestaticmapplanner.wormhole.WormholeSessionStore
import dev.evestaticmapplanner.wormhole.WormholeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun main(arguments: Array<String>) {
    AppDiagnostics.initialize()
    FeaturePackRuntimeValidationArguments.parseOrNull(arguments)?.let { validation ->
        runCatching { FeaturePackRuntimeValidation.run(validation) }
            .onFailure { AppDiagnostics.fatal("Feature Pack runtime validation failed", it) }
            .getOrThrow()
        AppDiagnostics.close()
        return
    }
    McpDiscoveryStartup.maintain()
    val featurePackRuntime = ProductionFeaturePackRuntime.start()
    featurePackRuntime.startReport.failures.forEach { failure ->
        AppDiagnostics.warning("Feature Pack loading continued after ${failure.kind}: ${failure.message}", failure.cause)
    }
    val buildInfo = ApplicationBuildInfo.current
    AppDiagnostics.info(
        "Application starting: version=${buildInfo.appVersion}, commit=${buildInfo.gitCommit.take(12)}, " +
            "target=${buildInfo.targetOs}/${buildInfo.targetArch}",
    )
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        AppDiagnostics.fatal("Uncaught fatal exception on thread ${thread.name}", error)
    }
    Runtime.getRuntime().addShutdownHook(Thread({
        featurePackRuntime.closeSafely().failures.forEach { failure ->
            AppDiagnostics.warning("Feature Pack shutdown continued after ${failure.kind}: ${failure.message}", failure.cause)
        }
        AppDiagnostics.close()
    }, "application-runtime-shutdown"))
    val initial = runCatching {
        StartupCoordinator().resolve(AppArguments.parse(arguments))
    }.getOrElse {
        AppDiagnostics.fatal("Startup configuration resolution failed", it)
        StartupResolution.Fatal(it.message ?: "Unable to resolve startup configuration")
    }
    logStartupResolution(initial)

    application {
        val wormholeSessionStore = remember { WormholeSessionStore() }
        val windowState = rememberWindowState(width = 1280.dp, height = 780.dp)
        var startup by remember { mutableStateOf(initial) }
        var exitRequested by remember { mutableStateOf(false) }
        val windowIcon = painterResource("icons/app-icon.png")
        Window(
            onCloseRequest = {
                if (startup is StartupResolution.Ready) exitRequested = true else exitApplication()
            },
            title = "EVE Static Map Planner",
            state = windowState,
            icon = windowIcon,
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                when (val resolution = startup) {
                    is StartupResolution.Ready -> ReadyApplication(
                        resolution.configuration,
                        featurePackRuntime,
                        wormholeSessionStore,
                        exitRequested,
                        ::exitApplication,
                    )
                    is StartupResolution.Bootstrap -> BootstrapApplication(
                        resolution.configuration,
                        onInstalled = { startup = StartupResolution.Ready(resolution.configuration) },
                    )
                    is StartupResolution.ExternalPathError -> StartupError(
                        "External static database error\n\n${resolution.message}\n\n${resolution.path}",
                    )
                    is StartupResolution.Fatal -> StartupError("Fatal static-data error\n\n${resolution.message}")
                }
            }
        }
    }
}

@Composable
private fun BootstrapApplication(configuration: StartupConfiguration, onInstalled: () -> Unit) {
    val paths = checkNotNull(configuration.managedPaths)
    val scope = remember(configuration) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val service = remember(configuration) { createUpdateService(paths, scope) { onInstalled() } }
    val viewModel = remember(configuration) {
        StaticDataManagerViewModel(
            StaticDatabaseMode.MANAGED,
            configuration.database.path,
            null,
            service,
            scope,
            autoCheck = false,
        )
    }
    DisposableEffect(viewModel) { onDispose(viewModel::close) }
    val state by viewModel.state.collectAsState()
    StaticDataBootstrapScreen(state, viewModel)
}

@Composable
private fun FrameWindowScope.ReadyApplication(
    configuration: StartupConfiguration,
    featurePackRuntime: ProductionFeaturePackRuntime,
    wormholeSessionStore: WormholeSessionStore,
    exitRequested: Boolean,
    onExitApplication: () -> Unit,
) {
    configuration.notice?.let { AppDiagnostics.warning("Static data startup notice: $it") }
    val staticRepository = remember(configuration) {
        CachingStaticMapRepository(SqliteStaticMapRepository(configuration.database.path))
    }
    val searchRepository = remember(configuration) { SqliteSystemSearchRepository(configuration.database.path) }
    val universeRepository = remember(configuration) { SqliteUniverseRepository(configuration.database.path) }
    val preferencesStore = remember(configuration) {
        PropertiesPreferencesStore(
            ApplicationDirectories.root().resolve("settings.properties"),
            warningSink = AppDiagnostics::warning,
        )
    }
    val featurePackManagerViewModel = remember(featurePackRuntime) {
        FeaturePackManagerViewModel(featurePackRuntime.manager, featurePackRuntime.packControlHost)
    }
    val mapViewModel = remember(configuration) {
        MapViewModel(
            staticMapRepository = staticRepository,
            universeRepository = universeRepository,
            focusSystemName = configuration.focusSystemName,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            preferencesStore = preferencesStore,
        )
    }
    val userComponents = remember(configuration) {
        runCatching {
            val ansiblexRepository = SqliteAnsiblexRepository(configuration.userDatabase.path)
            val importService = AnsiblexImportService(
                userDatabasePath = configuration.userDatabase.path,
                universeRepository = universeRepository,
                searchRepository = searchRepository,
            )
            UserComponents(
                ansiblexRepository = ansiblexRepository,
                importService = importService,
                savedMarkerRepository = SqliteSavedMarkerRepository(
                    databasePath = configuration.userDatabase.path,
                    initializeDatabase = false,
                ),
            )
        }.also { result ->
            result.exceptionOrNull()?.let { AppDiagnostics.warning("User database initialization failed", it) }
        }
    }
    val routeViewModel = remember(configuration, wormholeSessionStore) {
        RoutePlannerViewModel(
            staticMapRepository = staticRepository,
            searchRepository = searchRepository,
            ansiblexRepository = userComponents.getOrNull()?.ansiblexRepository,
            importService = userComponents.getOrNull()?.importService,
            userDatabaseError = userComponents.exceptionOrNull()?.let {
                "Ansiblex disabled: ${it.message ?: it::class.simpleName}"
            },
            wormholeSessionStore = wormholeSessionStore,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    val wormholeViewModel = remember(configuration, wormholeSessionStore) {
        WormholeViewModel(
            store = wormholeSessionStore,
            staticMapRepository = staticRepository,
            searchRepository = searchRepository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    val markerServiceScope = remember(configuration) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    val savedMarkerService = remember(configuration) {
        SavedMarkerService(
            repository = userComponents.getOrNull()?.savedMarkerRepository,
            userDatabaseError = userComponents.exceptionOrNull()?.let {
                "Markers disabled: ${it.message ?: it::class.simpleName}"
            },
            scope = markerServiceScope,
        )
    }
    val aiSavedMarkerApplicationService = remember(savedMarkerService, mapViewModel, universeRepository) {
        AiSavedMarkerApplicationService(
            savedMarkerService = savedMarkerService,
            universeRepository = universeRepository,
            permissionPolicy = AiSavedMarkerPermissionPolicy {
                mapViewModel.state.value.appPreferences.aiControl.savedMarkerAccessEnabled
            },
        )
    }
    val markerViewModel = remember(savedMarkerService) {
        MarkerViewModel(
            savedMarkerService = savedMarkerService,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    val jumpViewModel = remember(configuration) {
        JumpOverlayViewModel(
            staticRepository,
            searchRepository,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    val capitalViewModel = remember(configuration) {
        CapitalRouteViewModel(
            staticRepository,
            searchRepository,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    val planningViewCoordinator = remember(routeViewModel, capitalViewModel) {
        PlanningViewCoordinator(routeViewModel, capitalViewModel)
    }
    val missionMapStateStore = remember(configuration) { MissionMapStateStore() }
    val controlLifecycle = remember(configuration, planningViewCoordinator) {
        val planningPorts = ExistingPlanningPorts(
            staticMapRepository = staticRepository,
            ansiblexRepository = userComponents.getOrNull()?.ansiblexRepository,
            wormholeSessionStore = wormholeSessionStore,
        )
        val systemReadPort = RepositorySystemReadPort(
            searchRepository,
            universeRepository,
        )
        AiMapControlLifecycleController(
            discoveryRoot = ApplicationDirectories.root().resolve("control"),
            sessionFactory = {
                val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val coordinator = AppMapControlCoordinator(
                    systemReadPort = systemReadPort,
                    routePlanningPort = planningPorts,
                    jumpPlanningPort = planningPorts,
                    viewportControlPort = MapViewportControlAdapter(mapViewModel),
                    missionRenderStatePort = missionMapStateStore,
                    savedMarkerControlPort = AiSavedMarkerControlAdapter(aiSavedMarkerApplicationService),
                    wormholeControlPort = AppWormholeControlAdapter(wormholeSessionStore),
                    wormholeConnectionIds = wormholeSessionStore.connections.map { connections ->
                        connections.mapTo(mutableSetOf()) { it.id }
                    },
                    planningViewControlPort = dev.evestaticmapplanner.view.PlanningViewControlAdapter(planningViewCoordinator),
                    scope = sessionScope,
                )
                AppAiControlSession(
                    server = LocalControlServer(
                        service = coordinator,
                        appVersion = ApplicationBuildInfo.current.appVersion,
                        auditSink = AppLocalControlAuditSink,
                    ),
                    clearMissionState = { missionMapStateStore.publish(emptyList()) },
                    closeControlSession = {
                        coordinator.close()
                        sessionScope.cancel()
                    },
                )
            },
        )
    }
    val localhostMcpHost = remember(configuration) {
        LocalhostMcpHost.create(AppLocalhostMcpDiagnostics)
    }

    val updaterScope = remember(configuration) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val updaterService = remember(configuration) {
        configuration.managedPaths?.let { createUpdateService(it, updaterScope) }
    }
    val currentBuild = remember(configuration) { StaticDatabaseMetadataReader.read(configuration.database.path).sdeBuild }
    val staticDataViewModel = remember(configuration) {
        StaticDataManagerViewModel(
            configuration.database.mode,
            configuration.database.path,
            currentBuild,
            updaterService,
            updaterScope,
            autoCheck = configuration.database.mode == StaticDatabaseMode.MANAGED,
        )
    }

    val shutdownCoordinator = remember(
        controlLifecycle,
        localhostMcpHost,
        mapViewModel,
        routeViewModel,
        wormholeViewModel,
        jumpViewModel,
        capitalViewModel,
        markerViewModel,
        savedMarkerService,
        staticDataViewModel,
    ) {
        ApplicationShutdownCoordinator(
            shutdownLocalhostMcp = localhostMcpHost::shutdown,
            shutdownAiControl = controlLifecycle::shutdown,
            resourceClosers = listOf(
                mapViewModel::close,
                routeViewModel::close,
                wormholeViewModel::close,
                jumpViewModel::close,
                capitalViewModel::close,
                markerViewModel::close,
                savedMarkerService::close,
                staticDataViewModel::close,
            ),
            closeDiagnostics = AppDiagnostics::close,
            exitApplication = onExitApplication,
            warningSink = AppDiagnostics::warning,
        )
    }

    DisposableEffect(shutdownCoordinator) {
        onDispose(shutdownCoordinator::closeOwnedResources)
    }
    LaunchedEffect(exitRequested, shutdownCoordinator) {
        if (exitRequested) {
            AppDiagnostics.info("Application shutdown requested from the main window")
            shutdownCoordinator.shutdown()
        }
    }

    val mapState by mapViewModel.state.collectAsState()
    val routeState by routeViewModel.state.collectAsState()
    val wormholeState by wormholeViewModel.state.collectAsState()
    val jumpState by jumpViewModel.state.collectAsState()
    val capitalState by capitalViewModel.state.collectAsState()
    val planningViewsState by planningViewCoordinator.state.collectAsState()
    LaunchedEffect(planningViewsState.currentViewId, missionMapStateStore) {
        missionMapStateStore.selectView(planningViewsState.currentViewId.value)
    }
    LaunchedEffect(routeState, capitalState, planningViewCoordinator) {
        planningViewCoordinator.captureCurrent()
    }
    val markerState by markerViewModel.state.collectAsState()
    val missionState by missionMapStateStore.state.collectAsState()
    val featureOverlayState by featurePackRuntime.overlayHost.state.collectAsState()
    val systemInfoState by featurePackRuntime.systemInfoHost.state.collectAsState()
    val routeActions by featurePackRuntime.routeActionHost.state.collectAsState()
    val normalRouteSnapshot = remember(routeState.activeRoute, featurePackRuntime) {
        featurePackRuntime.routeSnapshotAdapter.normal(routeState.activeRoute)
    }
    val capitalRouteSnapshot = remember(capitalState.activeRoute, featurePackRuntime) {
        featurePackRuntime.routeSnapshotAdapter.capital(capitalState.activeRoute)
    }
    LaunchedEffect(mapState.selectedSystemId, featurePackRuntime.systemInfoHost) {
        featurePackRuntime.systemInfoHost.request(mapState.selectedSystemId)
    }
    val visibleFeatureOverlayState = remember(featureOverlayState, mapState.appPreferences.overlayVisibility) {
        OverlayVisibilityFilter.visibleState(featureOverlayState, mapState.appPreferences.overlayVisibility)
    }
    val staticDataState by staticDataViewModel.state.collectAsState()
    val aiControlStatus by controlLifecycle.status.collectAsState()
    val uiScope = rememberCoroutineScope()
    var showStaticData by remember { mutableStateOf(false) }
    var showPreferences by remember { mutableStateOf(false) }
    var showMarkerManager by remember { mutableStateOf(false) }
    var confirmClearTemporaryMarkers by remember { mutableStateOf(false) }
    var aiPreferenceError by remember { mutableStateOf<String?>(null) }
    val aiControlReady = !mapState.isLoading && mapState.scene != null && !mapState.canvasSize.isEmpty
    LaunchedEffect(aiControlReady, mapState.appPreferences.aiControl.enabled, exitRequested, localhostMcpHost) {
        if (!exitRequested && aiControlReady) {
            controlLifecycle.setEnabled(mapState.appPreferences.aiControl.enabled)
            localhostMcpHost.start()
        }
    }
    val temporaryMarkerCount = markerState.markersBySystemId.values.count {
        it.persistence == MarkerPersistence.TEMPORARY
    }
    MenuBar {
        Menu("Marker") {
            Item(
                "Marker Manager…",
                enabled = !showMarkerManager,
                onClick = { showMarkerManager = true },
            )
            Separator()
            Item(
                "Clear All Temporary Markers…",
                enabled = temporaryMarkerCount > 0,
                onClick = { confirmClearTemporaryMarkers = true },
            )
        }
        Menu("Preferences") {
            Item("Preferences…", onClick = { showPreferences = true })
        }
        Menu("Static Data") {
            Item("Static Data…", enabled = !showStaticData, onClick = { showStaticData = true })
        }
    }
    StaticMapScreen(
        databasePath = configuration.database.path,
        userDatabasePath = configuration.userDatabase.path,
        state = mapState,
        routeState = routeState,
        wormholeState = wormholeState,
        jumpState = jumpState,
        capitalState = capitalState,
        planningViewsState = planningViewsState,
        markerState = markerState,
        missionState = missionState,
        featureOverlayState = visibleFeatureOverlayState,
        systemInfoState = systemInfoState,
        routeActions = routeActions,
        normalRouteSnapshot = normalRouteSnapshot,
        capitalRouteSnapshot = capitalRouteSnapshot,
        onInvokeRouteAction = featurePackRuntime.routeActionHost::invoke,
        viewModel = mapViewModel,
        routeViewModel = routeViewModel,
        wormholeViewModel = wormholeViewModel,
        jumpViewModel = jumpViewModel,
        capitalViewModel = capitalViewModel,
        planningViewCoordinator = planningViewCoordinator,
        markerViewModel = markerViewModel,
        suppressMarkerOperationErrorDialog = showMarkerManager,
    )
    if (showStaticData) {
        StaticDataManagerDialog(staticDataState, staticDataViewModel) { showStaticData = false }
    }
    if (showPreferences) {
        PreferencesWindow(
            currentZoom = mapState.viewport?.zoom,
            preferences = mapState.appPreferences,
            onMapDisplayChange = mapViewModel::updateMapDisplayPreferences,
            onMarkerChange = mapViewModel::updateMarkerPreferences,
            aiControlStatus = aiControlStatus,
            aiControlError = aiPreferenceError,
            featurePackManagerViewModel = featurePackManagerViewModel,
            overlayState = featureOverlayState,
            onOverlayVisibilityChange = mapViewModel::updateOverlayVisibilityPreferences,
            onAiControlChange = { enabled ->
                uiScope.launch {
                    aiPreferenceError = null
                    mapViewModel.updateAiControlPreferences(
                        mapState.appPreferences.aiControl.copy(enabled = enabled),
                    ).fold(
                        onSuccess = {
                            if (!enabled || aiControlReady) controlLifecycle.setEnabled(enabled)
                        },
                        onFailure = {
                            aiPreferenceError = "The setting could not be saved; AI Map Control was not changed."
                            AppDiagnostics.warning("AI Control preference save failed", it)
                        },
                    )
                }
            },
            onAiSavedMarkerAccessChange = { enabled ->
                uiScope.launch {
                    aiPreferenceError = null
                    mapViewModel.updateAiControlPreferences(
                        mapState.appPreferences.aiControl.copy(savedMarkerAccessEnabled = enabled),
                    ).onFailure {
                        aiPreferenceError = "The setting could not be saved; AI Saved Marker access was not changed."
                        AppDiagnostics.warning("AI Saved Marker access preference save failed", it)
                    }
                }
            },
            onResetMapDisplay = mapViewModel::resetMapDisplayPreferences,
            onResetMarker = mapViewModel::resetMarkerPreferences,
            onResetAiControl = {
                uiScope.launch {
                    aiPreferenceError = null
                    mapViewModel.updateAiControlPreferences(
                        dev.evestaticmapplanner.preferences.AiControlPreferences.Defaults,
                    ).fold(
                        onSuccess = { controlLifecycle.setEnabled(false) },
                        onFailure = {
                            aiPreferenceError = "The setting could not be reset; AI Map Control was not changed."
                            AppDiagnostics.warning("AI Control preference reset failed", it)
                        },
                    )
                }
            },
            onResetOverlayVisibility = mapViewModel::resetOverlayVisibilityPreferences,
            onResetAll = {
                uiScope.launch {
                    aiPreferenceError = null
                    mapViewModel.resetAllPreferences().fold(
                        onSuccess = { controlLifecycle.setEnabled(false) },
                        onFailure = {
                            aiPreferenceError = "Preferences could not be reset."
                            AppDiagnostics.warning("Preferences reset failed", it)
                        },
                    )
                }
            },
            onDismiss = { showPreferences = false },
        )
    }
    if (showMarkerManager) {
        MarkerManagerWindow(
            markerState = markerState,
            markerViewModel = markerViewModel,
            searchRepository = searchRepository,
            onShowOnMap = mapViewModel::selectAndFocusSystem,
            onDismiss = { showMarkerManager = false },
        )
    }
    if (confirmClearTemporaryMarkers) {
        AlertDialog(
            onDismissRequest = { confirmClearTemporaryMarkers = false },
            title = { Text("Clear temporary markers?") },
            text = { Text("Remove all $temporaryMarkerCount temporary markers? Saved markers will not be changed.") },
            confirmButton = {
                TextButton(onClick = {
                    markerViewModel.clearTemporaryMarkers()
                    confirmClearTemporaryMarkers = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearTemporaryMarkers = false }) { Text("Cancel") }
            },
        )
    }
}

private data class UserComponents(
    val ansiblexRepository: SqliteAnsiblexRepository,
    val importService: AnsiblexImportService,
    val savedMarkerRepository: SqliteSavedMarkerRepository,
)

private fun createUpdateService(
    paths: ManagedStaticDataPaths,
    scope: CoroutineScope,
    onFirstInstallActivated: (Long) -> Unit = {},
): SdeUpdateService {
    val transport = JdkSdeHttpTransport(
        userAgent = "EVE-Static-Map-Planner/${ApplicationBuildInfo.current.appVersion}",
    )
    val pendingStore = PendingUpdateStore(paths)
    val client = SdeUpdateClient(transport, LatestBuildCacheStore(paths))
    return SdeUpdateService(
        paths = paths,
        client = client,
        downloader = SdeArchiveDownloader(transport, paths),
        preparer = SdeCandidatePreparer(paths, SafeSdeArchiveExtractor()),
        activator = PendingUpdateActivator(paths),
        pendingStore = pendingStore,
        scope = scope,
        onFirstInstallActivated = onFirstInstallActivated,
    )
}

@Composable
private fun StartupError(message: String) {
    Text(message, modifier = Modifier.padding(24.dp))
}

private fun logStartupResolution(resolution: StartupResolution) {
    when (resolution) {
        is StartupResolution.Ready -> AppDiagnostics.info(
            "Startup ready: staticMode=${resolution.configuration.database.mode}, " +
                "staticSource=${resolution.configuration.database.source}, " +
                "userSource=${resolution.configuration.userDatabase.source}",
        )
        is StartupResolution.Bootstrap -> AppDiagnostics.info("Startup requires managed static-data bootstrap")
        is StartupResolution.ExternalPathError -> AppDiagnostics.warning("External static database validation failed: ${resolution.message}")
        is StartupResolution.Fatal -> AppDiagnostics.fatal("Fatal startup state: ${resolution.message}")
    }
}
