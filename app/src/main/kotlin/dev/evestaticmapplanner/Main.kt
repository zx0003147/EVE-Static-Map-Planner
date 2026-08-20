package dev.evestaticmapplanner

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.evestaticmapplanner.core.repository.CachingStaticMapRepository
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportService
import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.map.MapViewModel
import dev.evestaticmapplanner.map.StaticMapScreen
import dev.evestaticmapplanner.preferences.PreferencesWindow
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main(arguments: Array<String>) {
    AppDiagnostics.initialize()
    val buildInfo = ApplicationBuildInfo.current
    AppDiagnostics.info(
        "Application starting: version=${buildInfo.appVersion}, commit=${buildInfo.gitCommit.take(12)}, " +
            "target=${buildInfo.targetOs}/${buildInfo.targetArch}",
    )
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        AppDiagnostics.fatal("Uncaught fatal exception on thread ${thread.name}", error)
    }
    Runtime.getRuntime().addShutdownHook(Thread(AppDiagnostics::close, "application-log-shutdown"))
    val initial = runCatching {
        StartupCoordinator().resolve(AppArguments.parse(arguments))
    }.getOrElse {
        AppDiagnostics.fatal("Startup configuration resolution failed", it)
        StartupResolution.Fatal(it.message ?: "Unable to resolve startup configuration")
    }
    logStartupResolution(initial)

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 780.dp)
        var startup by remember { mutableStateOf(initial) }
        val windowIcon = painterResource("icons/app-icon.png")
        Window(
            onCloseRequest = ::exitApplication,
            title = "EVE Static Map Planner",
            state = windowState,
            icon = windowIcon,
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                when (val resolution = startup) {
                    is StartupResolution.Ready -> ReadyApplication(resolution.configuration)
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
private fun FrameWindowScope.ReadyApplication(configuration: StartupConfiguration) {
    configuration.notice?.let { AppDiagnostics.warning("Static data startup notice: $it") }
    val staticRepository = remember(configuration) {
        CachingStaticMapRepository(SqliteStaticMapRepository(configuration.database.path))
    }
    val searchRepository = remember(configuration) { SqliteSystemSearchRepository(configuration.database.path) }
    val mapViewModel = remember(configuration) {
        MapViewModel(
            staticMapRepository = staticRepository,
            universeRepository = SqliteUniverseRepository(configuration.database.path),
            focusSystemName = configuration.focusSystemName,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            preferencesStore = PropertiesPreferencesStore(
                ApplicationDirectories.root().resolve("settings.properties"),
            ),
        )
    }
    val routeViewModel = remember(configuration) {
        val universeRepository = SqliteUniverseRepository(configuration.database.path)
        val userComponents = runCatching {
            val ansiblexRepository = SqliteAnsiblexRepository(configuration.userDatabase.path)
            val importService = AnsiblexImportService(
                userDatabasePath = configuration.userDatabase.path,
                universeRepository = universeRepository,
                searchRepository = searchRepository,
            )
            ansiblexRepository to importService
        }
        userComponents.exceptionOrNull()?.let { AppDiagnostics.warning("User database initialization failed", it) }
        RoutePlannerViewModel(
            staticMapRepository = staticRepository,
            searchRepository = searchRepository,
            ansiblexRepository = userComponents.getOrNull()?.first,
            importService = userComponents.getOrNull()?.second,
            userDatabaseError = userComponents.exceptionOrNull()?.let {
                "Ansiblex disabled: ${it.message ?: it::class.simpleName}"
            },
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

    DisposableEffect(mapViewModel, routeViewModel, jumpViewModel, capitalViewModel, staticDataViewModel) {
        onDispose {
            mapViewModel.close()
            routeViewModel.close()
            jumpViewModel.close()
            capitalViewModel.close()
            staticDataViewModel.close()
        }
    }

    val mapState by mapViewModel.state.collectAsState()
    val routeState by routeViewModel.state.collectAsState()
    val jumpState by jumpViewModel.state.collectAsState()
    val capitalState by capitalViewModel.state.collectAsState()
    val staticDataState by staticDataViewModel.state.collectAsState()
    var showStaticData by remember { mutableStateOf(false) }
    var showPreferences by remember { mutableStateOf(false) }
    MenuBar {
        Menu("Preferences") {
            Item("Map Display…", onClick = { showPreferences = true })
        }
    }
    StaticMapScreen(
        databasePath = configuration.database.path,
        userDatabasePath = configuration.userDatabase.path,
        state = mapState,
        routeState = routeState,
        jumpState = jumpState,
        capitalState = capitalState,
        viewModel = mapViewModel,
        routeViewModel = routeViewModel,
        jumpViewModel = jumpViewModel,
        capitalViewModel = capitalViewModel,
        onOpenStaticDataManager = { showStaticData = true },
    )
    if (showStaticData) {
        StaticDataManagerDialog(staticDataState, staticDataViewModel) { showStaticData = false }
    }
    if (showPreferences) {
        PreferencesWindow(
            currentZoom = mapState.viewport?.zoom,
            preferences = mapState.appPreferences,
            onMapDisplayChange = mapViewModel::updateMapDisplayPreferences,
            onResetDefaults = mapViewModel::resetMapDisplayPreferences,
            onDismiss = { showPreferences = false },
        )
    }
}

private fun createUpdateService(
    paths: ManagedStaticDataPaths,
    scope: CoroutineScope,
    onFirstInstallActivated: (Long) -> Unit = {},
): SdeUpdateService {
    val transport = JdkSdeHttpTransport()
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
