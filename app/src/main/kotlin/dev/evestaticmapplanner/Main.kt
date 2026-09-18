package dev.evestaticmapplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Platform
import java.awt.EventQueue
import dev.evestaticmapplanner.ai.EmbeddedAiAssistantWindow
import dev.evestaticmapplanner.ai.AiAssistantProviderStatus
import dev.evestaticmapplanner.ai.AiProviderSettingsController
import dev.evestaticmapplanner.ai.WebSearchSettingsController
import dev.evestaticmapplanner.ai.JavaSoundVoiceAudioPlayer
import dev.evestaticmapplanner.ai.MicrophoneWavRecorder
import dev.evestaticmapplanner.ai.SpeechPackManager
import dev.evestaticmapplanner.ai.defaultTtsDiagnosticCapture
import dev.evestaticmapplanner.ai.VoiceController
import dev.evestaticmapplanner.ai.VoiceSettingsController
import dev.evestaticmapplanner.ai.WhisperCppTranscriber
import dev.evestaticmapplanner.ai.WindowsSpeechSynthesizer
import dev.evestaticmapplanner.ai.WindowsDpapiAiCredentialStore
import dev.evestaticmapplanner.capital.CapitalRouteViewModel
import dev.evestaticmapplanner.control.AppMapControlCoordinator
import dev.evestaticmapplanner.control.FeaturePackMissionNavigationActionAdapter
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
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.featurepack.FeaturePackRuntimeValidation
import dev.evestaticmapplanner.featurepack.FeaturePackRuntimeValidationArguments
import dev.evestaticmapplanner.featurepack.FeaturePackManagerViewModel
import dev.evestaticmapplanner.featurepack.ProductionFeaturePackRuntime
import dev.evestaticmapplanner.feature.api.CharacterTrackingPriority
import dev.evestaticmapplanner.core.repository.CachingStaticMapRepository
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportService
import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.db.UserDatabase
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import dev.evestaticmapplanner.data.repository.SqliteSavedMarkerRepository
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.embeddedai.EmbeddedAiController
import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiProviderConfig
import dev.evestaticmapplanner.embeddedai.ConfiguredKoogAgentFactory
import dev.evestaticmapplanner.embeddedai.BraveSearchTester
import dev.evestaticmapplanner.embeddedai.BraveWebSearchClient
import dev.evestaticmapplanner.embeddedai.DefaultAiClientFactory
import dev.evestaticmapplanner.embeddedai.InMemoryAiCredentialStore
import dev.evestaticmapplanner.embeddedai.KoogAiConnectionTester
import dev.evestaticmapplanner.embeddedai.OpenAiVoiceClient
import dev.evestaticmapplanner.embeddedai.OpenAiSpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.OpenAiTextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechClient
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechToTextProvider
import dev.evestaticmapplanner.embeddedai.AlibabaTextToSpeechProvider
import dev.evestaticmapplanner.embeddedai.MapSpeechProviderFactory
import dev.evestaticmapplanner.embeddedai.SavedOrEnvironmentAiProviderConfigSource
import dev.evestaticmapplanner.embeddedai.UnavailableAiCredentialStore
import dev.evestaticmapplanner.embeddedai.WebSearchConfigSource
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.localization.AppLocalizationState
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.localization.ProvideAppLocalization
import dev.evestaticmapplanner.localization.StaticDatabaseStartupIssue
import dev.evestaticmapplanner.localization.StaticDatabaseStartupUiMessage
import dev.evestaticmapplanner.map.MapViewModel
import dev.evestaticmapplanner.map.SharedMarkerPresentationAdapter
import dev.evestaticmapplanner.map.SharedMarkerPresentationState
import dev.evestaticmapplanner.map.StaticMapScreen
import dev.evestaticmapplanner.marker.MarkerViewModel
import dev.evestaticmapplanner.marker.MarkerManagerWindow
import dev.evestaticmapplanner.marker.application.SavedMarkerService
import dev.evestaticmapplanner.marker.application.AiSavedMarkerApplicationService
import dev.evestaticmapplanner.marker.application.AiSavedMarkerPermissionPolicy
import dev.evestaticmapplanner.minimap.MiniMapViewModel
import dev.evestaticmapplanner.minimap.MiniMapWindow
import dev.evestaticmapplanner.minimap.CharacterFollowState
import dev.evestaticmapplanner.minimap.ForegroundCharacterFollowCoordinator
import dev.evestaticmapplanner.minimap.ManualWindowBindingResult
import dev.evestaticmapplanner.minimap.MiniMapHudController
import dev.evestaticmapplanner.minimap.MiniMapRecoveryHotkeyStatus
import dev.evestaticmapplanner.minimap.afterNativeHudFailure
import dev.evestaticmapplanner.platform.windows.minimaphud.WindowsMiniMapGlobalHotkey
import dev.evestaticmapplanner.preferences.MiniMapInteractionMode
import dev.evestaticmapplanner.mcp.LocalhostMcpHost
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.preferences.FeatureSettingsWindowState
import dev.evestaticmapplanner.preferences.MarkerSettingsWindow
import dev.evestaticmapplanner.preferences.MiniMapSettingsWindow
import dev.evestaticmapplanner.preferences.PreferencesWindow
import dev.evestaticmapplanner.preferences.PreferencesCategory
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
import dev.evestaticmapplanner.shared.api.KtorSharedMapClient
import dev.evestaticmapplanner.shared.SharedMapViewModel
import dev.evestaticmapplanner.shared.SharedMarkerManagerWindow
import dev.evestaticmapplanner.shared.WindowsDpapiCredentialStore
import dev.evestaticmapplanner.shared.sync.SharedMapConfigurationSink
import dev.evestaticmapplanner.shared.sync.SharedMapSession
import dev.evestaticmapplanner.shared.toPreferences
import dev.evestaticmapplanner.staticdata.StaticDataBootstrapScreen
import dev.evestaticmapplanner.staticdata.StaticDataManagerDialog
import dev.evestaticmapplanner.staticdata.StaticDataManagerViewModel
import dev.evestaticmapplanner.ui.EveAlwaysOnTopButton
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveTheme
import dev.evestaticmapplanner.ui.EveTopMenuBar
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.view.PlanningViewCoordinator
import dev.evestaticmapplanner.wormhole.WormholeSessionStore
import dev.evestaticmapplanner.wormhole.WormholeViewModel
import dev.evestaticmapplanner.webpack.WebPackExportRequest
import dev.evestaticmapplanner.webpack.WebPackExporter
import dev.evestaticmapplanner.webpack.WebPackExportUiState
import dev.evestaticmapplanner.webpack.WebPackSchema
import dev.evestaticmapplanner.webpack.chooseWebPackExportParentDirectory
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        StartupResolution.Fatal(
            StaticDatabaseStartupUiMessage(
                StaticDatabaseStartupIssue.DATABASE_INVALID,
                StaticDatabaseSchema.VERSION,
            ),
            it.message ?: "Unable to resolve startup configuration",
        )
    }
    logStartupResolution(initial)

    application {
        val wormholeSessionStore = remember { WormholeSessionStore() }
        val windowState = rememberWindowState(width = 1280.dp, height = 780.dp)
        val preferencesStore = remember {
            PropertiesPreferencesStore(
                ApplicationDirectories.root().resolve("settings.properties"),
                warningSink = AppDiagnostics::warning,
            )
        }
        val localizationState = remember(preferencesStore) {
            AppLocalizationState(preferencesStore.load().uiLocale)
        }
        val localization by localizationState.state.collectAsState()
        var startup by remember { mutableStateOf(initial) }
        var exitRequested by remember { mutableStateOf(false) }
        var isAlwaysOnTop by remember { mutableStateOf(false) }
        val windowIcon = painterResource("icons/app-icon.png")
        ProvideAppLocalization(localization) {
            val strings = LocalAppStrings.current
            Window(
                onCloseRequest = {
                    if (startup is StartupResolution.Ready) exitRequested = true else exitApplication()
                },
                title = strings.appTitle,
                state = windowState,
                icon = windowIcon,
                alwaysOnTop = isAlwaysOnTop,
            ) {
                EveTheme {
                    EveWindowChrome(window)
                    when (val resolution = startup) {
                        is StartupResolution.Ready -> ReadyApplication(
                            resolution.configuration,
                            featurePackRuntime,
                            wormholeSessionStore,
                            preferencesStore,
                            localizationState,
                            exitRequested,
                            ::exitApplication,
                            isAlwaysOnTop,
                            { isAlwaysOnTop = !isAlwaysOnTop },
                        )
                        is StartupResolution.Bootstrap -> BootstrapApplication(
                            resolution.configuration,
                            onInstalled = { startup = StartupResolution.Ready(resolution.configuration) },
                        )
                        is StartupResolution.ExternalPathError -> StartupError(
                            "${strings.staticData.externalDatabaseErrorTitle}\n\n" +
                                "${resolution.message.resolve(strings)}\n\n${resolution.path}",
                        )
                        is StartupResolution.Fatal -> StartupError(
                            "${strings.staticData.fatalStaticDataErrorTitle}\n\n${resolution.message.resolve(strings)}",
                        )
                    }
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
    preferencesStore: PropertiesPreferencesStore,
    localizationState: AppLocalizationState,
    exitRequested: Boolean,
    onExitApplication: () -> Unit,
    isAlwaysOnTop: Boolean,
    onToggleAlwaysOnTop: () -> Unit,
) {
    val strings = LocalAppStrings.current
    configuration.notice?.let { AppDiagnostics.warning("Static data startup notice: $it") }
    val staticRepository = remember(configuration) {
        CachingStaticMapRepository(SqliteStaticMapRepository(configuration.database.path))
    }
    val searchRepository = remember(configuration) { SqliteSystemSearchRepository(configuration.database.path) }
    val universeRepository = remember(configuration) { SqliteUniverseRepository(configuration.database.path) }
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
            createUserComponents(
                userDatabasePath = configuration.userDatabase.path,
                universeRepository = universeRepository,
                searchRepository = searchRepository,
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
    val miniMapViewModel = remember(configuration, mapViewModel) {
        MiniMapViewModel(persistPreferences = mapViewModel::updateMiniMapPreferences)
    }
    val characterTrackingAvailable by featurePackRuntime.characterTrackingHost.availability.collectAsState()
    val miniMapHudController = remember(configuration, characterTrackingAvailable) {
        MiniMapHudController(if (Platform.isWindows()) WindowsMiniMapGlobalHotkey() else null)
    }
    DisposableEffect(miniMapHudController, miniMapViewModel, characterTrackingAvailable) {
        if (characterTrackingAvailable) {
            miniMapHudController.start {
                EventQueue.invokeLater {
                    val current = miniMapViewModel.state.value.preferences
                    if (featurePackRuntime.characterTrackingHost.availability.value && current.enabled) {
                        val next = when (current.interactionMode) {
                            MiniMapInteractionMode.INTERACTIVE -> MiniMapInteractionMode.HUD_LOCKED
                            MiniMapInteractionMode.HUD_LOCKED -> MiniMapInteractionMode.INTERACTIVE
                        }
                        miniMapViewModel.updatePreferences(current.copy(interactionMode = next), fit = false)
                    }
                }
            }
        }
        onDispose {
            runCatching(miniMapHudController::close)
                .onFailure { AppDiagnostics.warning("Mini-map recovery hotkey did not close cleanly", it) }
        }
    }
    val foregroundCharacterFollow = remember(configuration) {
        if (Platform.isWindows()) ForegroundCharacterFollowCoordinator() else null
    }
    DisposableEffect(foregroundCharacterFollow) {
        foregroundCharacterFollow?.let { coordinator ->
            runCatching {
                coordinator.start { failure ->
                    AppDiagnostics.warning("Foreground EVE character follow reported a failure", failure)
                }
            }
                .onFailure { AppDiagnostics.warning("Foreground EVE character follow could not start", it) }
        }
        onDispose {
            runCatching { foregroundCharacterFollow?.close() }
                .onFailure { AppDiagnostics.warning("Foreground EVE character follow did not close cleanly", it) }
        }
    }
    val foregroundFollowStateFlow = remember(foregroundCharacterFollow) {
        foregroundCharacterFollow?.state ?: MutableStateFlow(CharacterFollowState())
    }
    val foregroundFollowState by foregroundFollowStateFlow.collectAsState()
    var previousHighPriorityCharacterId by remember { mutableStateOf<Long?>(null) }
    val sharedMapScope = remember(configuration) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    val sharedMapViewModel = remember(configuration, mapViewModel, sharedMapScope) {
        val session = SharedMapSession(
            client = KtorSharedMapClient(),
            credentialStore = WindowsDpapiCredentialStore(ApplicationDirectories.root()),
            configurationSink = object : SharedMapConfigurationSink {
                override suspend fun save(configuration: dev.evestaticmapplanner.shared.model.SharedMapConfiguration) {
                    mapViewModel.updateSharedMapPreferences(configuration.toPreferences()).getOrThrow()
                }
            },
            scope = sharedMapScope,
        )
        SharedMapViewModel(session, sharedMapScope)
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
    val controlServiceScope = remember(configuration) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    val planningPorts = remember(configuration, wormholeSessionStore, userComponents) {
        ExistingPlanningPorts(
            staticMapRepository = staticRepository,
            ansiblexRepository = userComponents.getOrNull()?.ansiblexRepository,
            wormholeSessionStore = wormholeSessionStore,
        )
    }
    val systemReadPort = remember(configuration) {
        RepositorySystemReadPort(
            searchRepository,
            universeRepository,
        )
    }
    val mapControlCoordinator = remember(
        configuration,
        planningPorts,
        planningViewCoordinator,
        featurePackRuntime,
        aiSavedMarkerApplicationService,
    ) {
        AppMapControlCoordinator(
            systemReadPort = systemReadPort,
            routePlanningPort = planningPorts,
            jumpPlanningPort = planningPorts,
            viewportControlPort = MapViewportControlAdapter(mapViewModel),
            missionRenderStatePort = missionMapStateStore,
            savedMarkerControlPort = AiSavedMarkerControlAdapter(aiSavedMarkerApplicationService),
            wormholeControlPort = AppWormholeControlAdapter(wormholeSessionStore),
            missionNavigationActionPort = FeaturePackMissionNavigationActionAdapter(
                featurePackRuntime.routeActionHost,
            ),
            wormholeConnectionIds = wormholeSessionStore.connections.map { connections ->
                connections.mapTo(mutableSetOf()) { it.id }
            },
            planningViewControlPort = dev.evestaticmapplanner.view.PlanningViewControlAdapter(planningViewCoordinator),
            scope = controlServiceScope,
        )
    }
    val aiSessionCredentialStore = remember(configuration) { InMemoryAiCredentialStore() }
    val aiSecureCredentialStore = remember(configuration) {
        if (Platform.isWindows()) {
            WindowsDpapiAiCredentialStore(ApplicationDirectories.root())
        } else {
            UnavailableAiCredentialStore
        }
    }
    val aiCredentialResolver = remember(configuration, aiSecureCredentialStore, aiSessionCredentialStore) {
        AiCredentialResolver(aiSecureCredentialStore, aiSessionCredentialStore)
    }
    val aiClientFactory = remember(configuration) { DefaultAiClientFactory() }
    val braveSearchClient = remember(configuration) { BraveWebSearchClient() }
    val aiConfigSource = remember(configuration, mapViewModel) {
        SavedOrEnvironmentAiProviderConfigSource(
            savedConfig = { mapViewModel.state.value.appPreferences.aiProvider },
        )
    }
    val webSearchConfigSource = remember(configuration, mapViewModel) {
        WebSearchConfigSource { mapViewModel.state.value.appPreferences.webSearch }
    }
    val embeddedAiController = remember(mapControlCoordinator, aiCredentialResolver, aiClientFactory, aiConfigSource) {
        EmbeddedAiController(
            ConfiguredKoogAgentFactory(
                mapControlService = mapControlCoordinator,
                configSource = aiConfigSource,
                credentialResolver = aiCredentialResolver,
                clientFactory = aiClientFactory,
                webSearchConfigSource = webSearchConfigSource,
                webSearchClient = braveSearchClient,
                diagnostics = AppDiagnostics::info,
            ),
            uiDispatcher = Dispatchers.Main.immediate,
        )
    }
    val aiProviderSettingsController = remember(
        configuration,
        aiSecureCredentialStore,
        aiSessionCredentialStore,
        aiCredentialResolver,
        aiClientFactory,
        mapViewModel,
        embeddedAiController,
    ) {
        AiProviderSettingsController(
            secureStore = aiSecureCredentialStore,
            sessionStore = aiSessionCredentialStore,
            credentialResolver = aiCredentialResolver,
            connectionTester = KoogAiConnectionTester(aiClientFactory),
            persistConfig = mapViewModel::updateAiProviderConfig,
            onConfigurationChanged = embeddedAiController::configurationChanged,
            diagnostics = AppDiagnostics::info,
        )
    }
    val webSearchSettingsController = remember(
        configuration,
        aiSecureCredentialStore,
        aiSessionCredentialStore,
        aiCredentialResolver,
        braveSearchClient,
        mapViewModel,
    ) {
        WebSearchSettingsController(
            secureStore = aiSecureCredentialStore,
            sessionStore = aiSessionCredentialStore,
            credentialResolver = aiCredentialResolver,
            tester = BraveSearchTester(braveSearchClient),
            persistConfig = mapViewModel::updateWebSearchConfig,
            diagnostics = AppDiagnostics::info,
        )
    }
    val speechPackManager = remember(configuration) { SpeechPackManager() }
    val windowsSpeechSynthesizer = remember(configuration) { WindowsSpeechSynthesizer() }
    val localSpeechTranscriber = remember(configuration, speechPackManager) {
        WhisperCppTranscriber(speechPackManager)
    }
    val openAiVoiceClient = remember(configuration) { OpenAiVoiceClient() }
    val alibabaSpeechClient = remember(configuration) {
        AlibabaSpeechClient(diagnostics = { AppDiagnostics.warning(it) })
    }
    val ttsDiagnosticCapture = remember(configuration) {
        defaultTtsDiagnosticCapture()?.also { capture ->
            AppDiagnostics.info(
                "TTS Diagnostic Mode enabled: playback=" +
                    (if (capture.isolatePlayback) "SUPPRESSED" else "ENABLED") + ", " +
                    "artifactRoot=${capture.root.toAbsolutePath().normalize()}",
            )
        }
    }
    val voiceAudioPlayer = remember(configuration, ttsDiagnosticCapture) {
        JavaSoundVoiceAudioPlayer(diagnosticSink = { snapshot ->
            AppDiagnostics.debug(snapshot.toSafeLogMessage())
        })
    }
    val speechProviderFactory = remember(
        configuration,
        aiCredentialResolver,
        localSpeechTranscriber,
        windowsSpeechSynthesizer,
        openAiVoiceClient,
        alibabaSpeechClient,
    ) {
        MapSpeechProviderFactory(
            speechToTextProviders = mapOf(
                VoiceInputProvider.LOCAL to localSpeechTranscriber,
                VoiceInputProvider.OPENAI to OpenAiSpeechToTextProvider(aiCredentialResolver, openAiVoiceClient),
                VoiceInputProvider.ALIBABA to AlibabaSpeechToTextProvider(aiCredentialResolver, alibabaSpeechClient),
            ),
            textToSpeechProviders = mapOf(
                VoiceOutputProvider.LOCAL to windowsSpeechSynthesizer,
                VoiceOutputProvider.OPENAI to OpenAiTextToSpeechProvider(aiCredentialResolver, openAiVoiceClient),
                VoiceOutputProvider.ALIBABA to AlibabaTextToSpeechProvider(aiCredentialResolver, alibabaSpeechClient),
            ),
        )
    }
    val voiceController = remember(
        configuration,
        mapViewModel,
        speechProviderFactory,
        voiceAudioPlayer,
        ttsDiagnosticCapture,
    ) {
        VoiceController(
            configSource = { mapViewModel.state.value.appPreferences.voice },
            providerFactory = speechProviderFactory,
            recorder = MicrophoneWavRecorder(),
            audioPlayer = voiceAudioPlayer,
            diagnostics = AppDiagnostics::info,
            ttsDiagnosticCapture = ttsDiagnosticCapture,
        )
    }
    val voiceSettingsController = remember(
        configuration,
        aiSecureCredentialStore,
        aiSessionCredentialStore,
        aiCredentialResolver,
        speechPackManager,
        windowsSpeechSynthesizer,
        speechProviderFactory,
        voiceAudioPlayer,
        mapViewModel,
        voiceController,
    ) {
        VoiceSettingsController(
            secureStore = aiSecureCredentialStore,
            sessionStore = aiSessionCredentialStore,
            credentialResolver = aiCredentialResolver,
            speechPackManager = speechPackManager,
            localSynthesizer = windowsSpeechSynthesizer,
            providerFactory = speechProviderFactory,
            audioPlayer = voiceAudioPlayer,
            persistConfig = mapViewModel::updateVoiceConfig,
            onConfigSaved = voiceController::providerConfigurationChanged,
        )
    }
    val controlLifecycle = remember(configuration, mapControlCoordinator) {
        AiMapControlLifecycleController(
            discoveryRoot = ApplicationDirectories.root().resolve("control"),
            sessionFactory = {
                AppAiControlSession(
                    server = LocalControlServer(
                        service = mapControlCoordinator,
                        appVersion = ApplicationBuildInfo.current.appVersion,
                        auditSink = AppLocalControlAuditSink,
                    ),
                    clearMissionState = mapControlCoordinator::resetExternalSession,
                    closeControlSession = {},
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
    val webPackExporter = remember(staticRepository, userComponents) {
        userComponents.getOrNull()?.let { WebPackExporter(staticRepository, it.ansiblexRepository) }
    }
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
        sharedMapViewModel,
        routeViewModel,
        wormholeViewModel,
        jumpViewModel,
        capitalViewModel,
        markerViewModel,
        savedMarkerService,
        staticDataViewModel,
        embeddedAiController,
        aiProviderSettingsController,
        webSearchSettingsController,
        voiceController,
        voiceSettingsController,
        aiSessionCredentialStore,
        mapControlCoordinator,
        controlServiceScope,
    ) {
        ApplicationShutdownCoordinator(
            shutdownLocalhostMcp = localhostMcpHost::shutdown,
            shutdownAiControl = {
                controlLifecycle.shutdown()
                embeddedAiController.shutdown()
            },
            resourceClosers = listOf(
                mapControlCoordinator::close,
                { controlServiceScope.cancel() },
                sharedMapViewModel::close,
                mapViewModel::close,
                routeViewModel::close,
                wormholeViewModel::close,
                jumpViewModel::close,
                capitalViewModel::close,
                markerViewModel::close,
                savedMarkerService::close,
                staticDataViewModel::close,
                aiProviderSettingsController::close,
                webSearchSettingsController::close,
                voiceSettingsController::close,
                voiceController::close,
                aiSessionCredentialStore::close,
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
    LaunchedEffect(mapState.isLoading, mapState.appPreferences.uiLocale, localizationState) {
        if (!mapState.isLoading) localizationState.updateLocale(mapState.appPreferences.uiLocale)
    }
    val aiProviderSettingsState by aiProviderSettingsController.state.collectAsState()
    val webSearchSettingsState by webSearchSettingsController.state.collectAsState()
    val voiceSettingsState by voiceSettingsController.state.collectAsState()
    val trackedCharacters by featurePackRuntime.characterTrackingHost.state.collectAsState()
    val miniMapState by miniMapViewModel.state.collectAsState()
    val miniMapHudState by miniMapHudController.state.collectAsState()
    LaunchedEffect(miniMapHudState.hotkeyStatus) {
        if (miniMapHudState.hotkeyStatus == MiniMapRecoveryHotkeyStatus.FAILED) {
            miniMapHudState.diagnostic?.let(AppDiagnostics::warning)
        }
    }
    LaunchedEffect(miniMapHudState.hotkeyStatus, miniMapState.preferences.interactionMode) {
        if (
            miniMapHudState.hotkeyStatus in setOf(
                MiniMapRecoveryHotkeyStatus.FAILED,
                MiniMapRecoveryHotkeyStatus.UNSUPPORTED,
            ) && miniMapState.preferences.interactionMode == MiniMapInteractionMode.HUD_LOCKED
        ) {
            miniMapViewModel.updatePreferences(
                miniMapState.preferences.copy(interactionMode = MiniMapInteractionMode.INTERACTIVE),
                fit = false,
            )
        }
    }
    LaunchedEffect(mapState.scene, miniMapViewModel) {
        miniMapViewModel.updateScene(mapState.scene)
    }
    LaunchedEffect(trackedCharacters, miniMapViewModel) {
        miniMapViewModel.updateCharacters(trackedCharacters)
        foregroundCharacterFollow?.updateCharacters(trackedCharacters)
    }
    LaunchedEffect(foregroundFollowState.followedCharacterId, miniMapViewModel) {
        val nextCharacterId = foregroundFollowState.followedCharacterId
        previousHighPriorityCharacterId?.takeIf { it != nextCharacterId }?.let { previousCharacterId ->
            featurePackRuntime.characterTrackingHost.setPriority(
                previousCharacterId,
                CharacterTrackingPriority.NORMAL,
            )
        }
        nextCharacterId?.let { characterId ->
            featurePackRuntime.characterTrackingHost.setPriority(characterId, CharacterTrackingPriority.HIGH)
            featurePackRuntime.characterTrackingHost.requestRefresh(characterId)
        }
        previousHighPriorityCharacterId = nextCharacterId
        miniMapViewModel.setAutomaticCharacter(foregroundFollowState.followedCharacterId)
    }
    LaunchedEffect(mapState.appPreferences.miniMap, miniMapViewModel) {
        miniMapViewModel.restorePreferences(mapState.appPreferences.miniMap)
    }
    val sharedMapState by sharedMapViewModel.state.collectAsState()
    val sharedMapOperationError by sharedMapViewModel.operationError.collectAsState()
    val sharedMarkerMutation by sharedMapViewModel.markerMutation.collectAsState()
    val sharedAdminState by sharedMapViewModel.admin.collectAsState()
    val routeHandoffPublishState by sharedMapViewModel.routeHandoffPublish.collectAsState()
    var sharedMapRestored by remember(configuration) { mutableStateOf(false) }
    LaunchedEffect(mapState.isLoading, sharedMapRestored, sharedMapViewModel) {
        if (!mapState.isLoading && !sharedMapRestored) {
            sharedMapRestored = true
            sharedMapViewModel.restore(mapState.appPreferences.sharedMap)
        }
    }
    val routeState by routeViewModel.state.collectAsState()
    LaunchedEffect(routeState.ansiblexConnections, miniMapViewModel) {
        miniMapViewModel.updateAnsiblexConnections(routeState.ansiblexConnections)
    }
    LaunchedEffect(routeState.activeRoute, miniMapViewModel) {
        miniMapViewModel.updateActiveRoute(routeState.activeRoute)
    }
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
    LaunchedEffect(missionState.normalRoutes, missionState.capitalRoutes, miniMapViewModel) {
        miniMapViewModel.updateMissionRoutes(missionState.normalRoutes, missionState.capitalRoutes)
    }
    val sharedMarkerPresentation = remember(
        sharedMapState.snapshot,
        sharedMapState.stale,
        sharedMapState.selectedWorkspaceId,
        mapState.scene,
        mapState.appPreferences.marker.showSharedMarkers,
    ) {
        mapState.scene?.let { scene ->
            SharedMarkerPresentationAdapter.build(
                state = sharedMapState,
                knownSystemIds = scene.nodesById.keys + scene.omittedSystemIds,
                isVisible = mapState.appPreferences.marker.showSharedMarkers,
            )
        } ?: SharedMarkerPresentationState.Empty.copy(
            isVisible = mapState.appPreferences.marker.showSharedMarkers,
            isStale = sharedMapState.stale,
        )
    }
    LaunchedEffect(
        sharedMarkerPresentation.workspaceId,
        sharedMarkerPresentation.revision,
        sharedMarkerPresentation.skippedUnknownSystems,
    ) {
        sharedMarkerPresentation.skippedUnknownSystems.forEach { skipped ->
            AppDiagnostics.warning(
                "Shared Marker skipped for unknown system: markerId=${skipped.markerId} systemId=${skipped.systemId}",
            )
        }
    }
    val featureOverlayState by featurePackRuntime.overlayHost.state.collectAsState()
    val systemInfoState by featurePackRuntime.systemInfoHost.state.collectAsState()
    val routeActions by featurePackRuntime.routeActionHost.state.collectAsState()
    val normalRouteSnapshot = remember(routeState.activeRoute, featurePackRuntime) {
        featurePackRuntime.routeSnapshotAdapter.normal(routeState.activeRoute)
    }
    val normalNavigationSnapshot = remember(routeState.navigationIntent, featurePackRuntime) {
        featurePackRuntime.routeSnapshotAdapter.normalNavigation(routeState.navigationIntent)
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
    var showEmbeddedAi by remember { mutableStateOf(false) }
    var showPreferences by remember { mutableStateOf(false) }
    var preferencesInitialCategory by remember { mutableStateOf(PreferencesCategory.MAP_DISPLAY) }
    var markerSettingsWindow by remember { mutableStateOf(FeatureSettingsWindowState()) }
    var miniMapSettingsWindow by remember { mutableStateOf(FeatureSettingsWindowState()) }
    var showMarkerManager by remember { mutableStateOf(false) }
    var showSharedMarkerManager by remember { mutableStateOf(false) }
    var confirmClearTemporaryMarkers by remember { mutableStateOf(false) }
    var aiPreferenceError by remember { mutableStateOf<dev.evestaticmapplanner.localization.UiMessage?>(null) }
    var webPackExportState by remember(configuration) { mutableStateOf<WebPackExportUiState>(WebPackExportUiState.Idle) }
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
    val miniMapCapabilityUi = miniMapCapabilityUiDecision(
        characterTrackingAvailable = characterTrackingAvailable,
        miniMapEnabled = miniMapState.preferences.enabled,
        settingsWindowOpen = miniMapSettingsWindow.isOpen,
    )
    LaunchedEffect(miniMapCapabilityUi.disableMiniMap, miniMapCapabilityUi.closeSettingsWindow) {
        if (miniMapCapabilityUi.disableMiniMap) miniMapViewModel.setEnabled(false)
        if (miniMapCapabilityUi.closeSettingsWindow) {
            miniMapSettingsWindow = miniMapSettingsWindow.close()
        }
    }
    Column(Modifier.fillMaxSize().background(EveColors.PrimarySurface)) {
        EveTopMenuBar(
            menus = plannerTopMenus(
                state = PlannerTopMenuState(
                    markerManagerOpen = showMarkerManager,
                    sharedMarkerManagerOpen = showSharedMarkerManager,
                    temporaryMarkerCount = temporaryMarkerCount,
                    characterTrackingAvailable = characterTrackingAvailable,
                    miniMapEnabled = miniMapState.preferences.enabled,
                    staticDataOpen = showStaticData,
                ),
                actions = PlannerTopMenuActions(
                    openMarkerManager = { showMarkerManager = true },
                    openSharedMarkerManager = { showSharedMarkerManager = true },
                    clearTemporaryMarkers = { confirmClearTemporaryMarkers = true },
                    openMarkerSettings = { markerSettingsWindow = markerSettingsWindow.show() },
                    toggleMiniMap = {
                        if (characterTrackingAvailable) {
                            miniMapViewModel.setEnabled(!miniMapState.preferences.enabled)
                        }
                    },
                    openMiniMapSettings = {
                        if (characterTrackingAvailable) {
                            miniMapSettingsWindow = miniMapSettingsWindow.show()
                        }
                    },
                    openPreferences = {
                        preferencesInitialCategory = PreferencesCategory.MAP_DISPLAY
                        showPreferences = true
                    },
                    openStaticData = { showStaticData = true },
                ),
                strings = strings.mainShell,
            ),
            trailingContent = {
                EveAlwaysOnTopButton(
                    isAlwaysOnTop = isAlwaysOnTop,
                    onClick = onToggleAlwaysOnTop,
                )
            },
        )
        StaticMapScreen(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            databasePath = configuration.database.path,
            userDatabasePath = configuration.userDatabase.path,
            state = mapState,
            routeState = routeState,
            wormholeState = wormholeState,
            jumpState = jumpState,
            capitalState = capitalState,
            planningViewsState = planningViewsState,
            markerState = markerState,
            sharedMapState = sharedMapState,
            sharedMarkerState = sharedMarkerPresentation,
            sharedMarkerMutation = sharedMarkerMutation,
            routeHandoffPublishState = routeHandoffPublishState,
            universeBuild = currentBuild.toString(),
            missionState = missionState,
            featureOverlayState = visibleFeatureOverlayState,
            systemInfoState = systemInfoState,
            routeActions = routeActions,
            normalRouteSnapshot = normalRouteSnapshot,
            normalNavigationSnapshot = normalNavigationSnapshot,
            capitalRouteSnapshot = capitalRouteSnapshot,
            onInvokeRouteAction = featurePackRuntime.routeActionHost::invoke,
            onInvokeNavigationAction = featurePackRuntime.routeActionHost::invokeNavigation,
            viewModel = mapViewModel,
            routeViewModel = routeViewModel,
            wormholeViewModel = wormholeViewModel,
            jumpViewModel = jumpViewModel,
            capitalViewModel = capitalViewModel,
            planningViewCoordinator = planningViewCoordinator,
            markerViewModel = markerViewModel,
            sharedMapViewModel = sharedMapViewModel,
            onOpenEmbeddedAi = { showEmbeddedAi = true },
            onFirstMapDisplayed = featurePackRuntime::onFirstMapDisplayed,
            suppressMarkerOperationErrorDialog = showMarkerManager,
        )
    }
    if (miniMapCapabilityUi.showMiniMapWindow) {
        MiniMapWindow(
            state = miniMapState,
            viewModel = miniMapViewModel,
            automaticFollowDiagnostic = foregroundFollowState.diagnostic,
            onBindCurrentWindow = { characterId ->
                when (val result = foregroundCharacterFollow?.bindCurrentWindow(characterId)) {
                    ManualWindowBindingResult.Bound -> "Current EVE client session bound"
                    is ManualWindowBindingResult.Rejected -> result.reason
                    null -> "Foreground client detection is unavailable on this platform"
                }
            },
            hudRuntimeState = miniMapHudState,
            onNativeWindowFailure = { failure ->
                AppDiagnostics.warning("Mini-map native HUD style failed; restoring Standard + Interactive", failure)
                miniMapViewModel.updatePreferences(
                    miniMapViewModel.state.value.preferences.afterNativeHudFailure(),
                    fit = false,
                )
            },
            onClose = { miniMapViewModel.setEnabled(false) },
        )
    }
    if (showStaticData) {
        StaticDataManagerDialog(staticDataState, staticDataViewModel) { showStaticData = false }
    }
    if (showEmbeddedAi) {
        val effectiveAiConfig = mapState.appPreferences.aiProvider ?: aiConfigSource.current()
        EmbeddedAiAssistantWindow(
            controller = embeddedAiController,
            voiceController = voiceController,
            voiceInputEnabled = mapState.appPreferences.voice.inputProvider != VoiceInputProvider.OFF,
            providerStatus = AiAssistantProviderStatus(
                providerType = effectiveAiConfig?.providerType,
                modelId = effectiveAiConfig?.modelId,
                credentialSource = effectiveAiConfig?.let(aiCredentialResolver::source),
            ),
            onOpenSettings = {
                preferencesInitialCategory = PreferencesCategory.AI_FEATURES
                showPreferences = true
            },
            onDismiss = {
                voiceController.onAssistantWindowClosed()
                showEmbeddedAi = false
            },
        )
    }
    if (showPreferences) {
        PreferencesWindow(
            currentZoom = mapState.viewport?.zoom,
            preferences = mapState.appPreferences,
            onMapDisplayChange = mapViewModel::updateMapDisplayPreferences,
            onLocaleChange = { locale ->
                localizationState.updateLocale(locale)
                mapViewModel.updateAppLocale(locale)
            },
            aiProviderSettingsState = aiProviderSettingsState,
            webSearchSettingsState = webSearchSettingsState,
            voiceSettingsState = voiceSettingsState,
            initialCategory = preferencesInitialCategory,
            onAiProviderViewed = aiProviderSettingsController::refresh,
            onAiProviderTest = aiProviderSettingsController::test,
            onAiProviderSave = aiProviderSettingsController::save,
            onAiCredentialDelete = aiProviderSettingsController::deleteCredential,
            onWebSearchViewed = webSearchSettingsController::refresh,
            onWebSearchTest = webSearchSettingsController::test,
            onWebSearchSave = webSearchSettingsController::save,
            onWebSearchCredentialDelete = webSearchSettingsController::deleteCredential,
            onVoiceViewed = voiceSettingsController::refresh,
            onVoiceSave = voiceSettingsController::save,
            onVoiceCredentialDelete = voiceSettingsController::deleteCredential,
            onVoiceTestRecognition = voiceSettingsController::testRecognition,
            onVoiceTestVoice = voiceSettingsController::testVoice,
            onSpeechPackInstall = voiceSettingsController::installSpeechPack,
            onSpeechPackRemove = voiceSettingsController::removeSpeechPack,
            aiControlStatus = aiControlStatus,
            aiControlError = aiPreferenceError,
            featurePackManagerViewModel = featurePackManagerViewModel,
            overlayState = featureOverlayState,
            webPackExportState = webPackExportState,
            sharedMapState = sharedMapState,
            sharedMapOperationError = sharedMapOperationError,
            sharedAdminState = sharedAdminState,
            onSharedMapConnect = sharedMapViewModel::connect,
            onSharedMapWorkspaceChange = sharedMapViewModel::switchWorkspace,
            onSharedMapRefresh = sharedMapViewModel::refreshNow,
            onSharedMapDisconnect = sharedMapViewModel::disconnect,
            onSharedMapClearError = sharedMapViewModel::clearOperationError,
            onSharedMapLoadMembers = sharedMapViewModel::loadMembers,
            onSharedMapCreateMember = sharedMapViewModel::createMember,
            onSharedMapChangeMemberRole = sharedMapViewModel::changeMemberRole,
            onSharedMapRemoveMember = sharedMapViewModel::removeMember,
            onSharedMapCreateInvite = sharedMapViewModel::createInvite,
            onSharedMapClearAdminError = sharedMapViewModel::clearAdminError,
            onSharedMapClearInvite = sharedMapViewModel::clearOneTimeInvite,
            onOverlayVisibilityChange = mapViewModel::updateOverlayVisibilityPreferences,
            onExportWebPack = export@{
                val exporter = webPackExporter
                if (exporter == null) {
                    webPackExportState = WebPackExportUiState.Failure(
                        "Ansiblex data unavailable: " +
                            (userComponents.exceptionOrNull()?.message ?: "user database is not initialized"),
                    )
                    return@export
                }
                val parentDirectory = chooseWebPackExportParentDirectory(
                    strings.preferences.text(
                        dev.evestaticmapplanner.localization.PreferencesText.SELECT_WEB_PACK_PARENT,
                        WebPackSchema.EXPORT_DIRECTORY_NAME,
                    ),
                    strings.preferences.text(dev.evestaticmapplanner.localization.PreferencesText.EXPORT_HERE),
                ) ?: return@export
                val outputDirectory = parentDirectory.resolve(WebPackSchema.EXPORT_DIRECTORY_NAME)
                webPackExportState = WebPackExportUiState.Exporting
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            exporter.export(
                                WebPackExportRequest(
                                    outputDirectory = outputDirectory,
                                    desktopAppVersion = ApplicationBuildInfo.current.appVersion,
                                    sdeBuild = currentBuild,
                                ),
                            )
                        }
                    }.fold(
                        onSuccess = { report ->
                            webPackExportState = WebPackExportUiState.Success(report)
                            AppDiagnostics.info(
                                "Web Pack exported: version=${report.packVersion}, output=${report.outputDirectory}",
                            )
                        },
                        onFailure = { error ->
                            val message = error.message ?: "${error::class.simpleName}: export could not be completed"
                            webPackExportState = WebPackExportUiState.Failure(message)
                            AppDiagnostics.warning("Web Pack export failed: $message", error)
                        },
                    )
                }
            },
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
                            aiPreferenceError = dev.evestaticmapplanner.localization.PreferencesUiMessage(
                                dev.evestaticmapplanner.localization.PreferencesMessage.AI_CONTROL_SAVE_FAILED,
                            )
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
                        aiPreferenceError = dev.evestaticmapplanner.localization.PreferencesUiMessage(
                            dev.evestaticmapplanner.localization.PreferencesMessage.AI_SAVED_MARKER_ACCESS_SAVE_FAILED,
                        )
                        AppDiagnostics.warning("AI Saved Marker access preference save failed", it)
                    }
                }
            },
            onResetMapDisplay = mapViewModel::resetMapDisplayPreferences,
            onResetAiControl = {
                uiScope.launch {
                    aiPreferenceError = null
                    mapViewModel.updateAiControlPreferences(
                        dev.evestaticmapplanner.preferences.AiControlPreferences.Defaults,
                    ).fold(
                        onSuccess = {
                            controlLifecycle.setEnabled(false)
                        },
                        onFailure = {
                            aiPreferenceError = dev.evestaticmapplanner.localization.PreferencesUiMessage(
                                dev.evestaticmapplanner.localization.PreferencesMessage.AI_CONTROL_RESET_FAILED,
                            )
                            AppDiagnostics.warning("AI Control preference reset failed", it)
                        },
                    )
                }
            },
            onResetOverlayVisibility = mapViewModel::resetOverlayVisibilityPreferences,
            onResetAll = {
                uiScope.launch {
                    aiPreferenceError = null
                    val sharedDisconnected = sharedMapViewModel.disconnectForPreferencesReset()
                    if (sharedDisconnected.isFailure) {
                        aiPreferenceError = dev.evestaticmapplanner.localization.PreferencesUiMessage(
                            dev.evestaticmapplanner.localization.PreferencesMessage.RESET_SHARED_CREDENTIAL_FAILED,
                        )
                        AppDiagnostics.warning("Shared Map disconnect during preference reset failed")
                        return@launch
                    }
                    mapViewModel.resetAllPreferences().fold(
                        onSuccess = {
                            controlLifecycle.setEnabled(false)
                            embeddedAiController.configurationChanged()
                        },
                        onFailure = {
                            aiPreferenceError = dev.evestaticmapplanner.localization.PreferencesUiMessage(
                                dev.evestaticmapplanner.localization.PreferencesMessage.RESET_PREFERENCES_FAILED,
                            )
                            AppDiagnostics.warning("Preferences reset failed", it)
                        },
                    )
                }
            },
            onDismiss = { showPreferences = false },
        )
    }
    if (markerSettingsWindow.isOpen) {
        MarkerSettingsWindow(
            preferences = mapState.appPreferences.marker,
            onChange = mapViewModel::updateMarkerPreferences,
            onReset = mapViewModel::resetMarkerPreferences,
            focusRequest = markerSettingsWindow.focusRequest,
            onDismiss = { markerSettingsWindow = markerSettingsWindow.close() },
        )
    }
    if (miniMapCapabilityUi.showSettingsWindow) {
        MiniMapSettingsWindow(
            preferences = mapState.appPreferences.miniMap,
            onChange = { requested ->
                miniMapViewModel.updatePreferences(
                    requested.copy(interactionMode = miniMapHudController.safeMode(requested.interactionMode)),
                    fit = false,
                )
            },
            hudRuntimeState = miniMapHudState,
            onReset = mapViewModel::resetMiniMapPreferences,
            focusRequest = miniMapSettingsWindow.focusRequest,
            onDismiss = { miniMapSettingsWindow = miniMapSettingsWindow.close() },
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
    if (showSharedMarkerManager) {
        SharedMarkerManagerWindow(
            state = sharedMapState,
            mutation = sharedMarkerMutation,
            viewModel = sharedMapViewModel,
            searchRepository = searchRepository,
            onFocusSystem = mapViewModel::selectAndFocusSystem,
            onDismiss = { showSharedMarkerManager = false },
        )
    }
    if (confirmClearTemporaryMarkers) {
        AlertDialog(
            onDismissRequest = { confirmClearTemporaryMarkers = false },
            title = { Text(strings.marker.clearTemporaryTitle) },
            text = { Text(strings.marker.clearTemporaryMessage(temporaryMarkerCount)) },
            confirmButton = {
                TextButton(onClick = {
                    markerViewModel.clearTemporaryMarkers()
                    confirmClearTemporaryMarkers = false
                }) { Text(strings.common.clear) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearTemporaryMarkers = false }) { Text(strings.common.cancel) }
            },
        )
    }
}

internal data class UserComponents(
    val ansiblexRepository: SqliteAnsiblexRepository,
    val importService: AnsiblexImportService,
    val savedMarkerRepository: SqliteSavedMarkerRepository,
)

internal fun createUserComponents(
    userDatabasePath: Path,
    universeRepository: UniverseRepository,
    searchRepository: SystemSearchRepository,
    databaseInitializer: (Path) -> Unit = UserDatabase::initialize,
): UserComponents {
    databaseInitializer(userDatabasePath)
    return UserComponents(
        ansiblexRepository = SqliteAnsiblexRepository(
            databasePath = userDatabasePath,
            initializeDatabase = false,
        ),
        importService = AnsiblexImportService(
            userDatabasePath = userDatabasePath,
            universeRepository = universeRepository,
            searchRepository = searchRepository,
            initializeDatabase = false,
        ),
        savedMarkerRepository = SqliteSavedMarkerRepository(
            databasePath = userDatabasePath,
            initializeDatabase = false,
        ),
    )
}

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
        is StartupResolution.ExternalPathError -> AppDiagnostics.warning(
            "External static database validation failed: ${resolution.diagnostic}",
        )
        is StartupResolution.Fatal -> AppDiagnostics.fatal("Fatal startup state: ${resolution.diagnostic}")
    }
}
