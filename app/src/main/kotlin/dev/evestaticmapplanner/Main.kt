package dev.evestaticmapplanner

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportService
import dev.evestaticmapplanner.map.StaticMapScreen
import dev.evestaticmapplanner.map.MapViewModel
import dev.evestaticmapplanner.route.RoutePlannerViewModel
import dev.evestaticmapplanner.capital.CapitalRouteViewModel
import dev.evestaticmapplanner.jump.JumpOverlayViewModel
import dev.evestaticmapplanner.core.repository.CachingStaticMapRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files

fun main(arguments: Array<String>) {
    val startup = runCatching {
        val parsed = AppArguments.parse(arguments)
        val database = DatabasePathResolver.resolve(parsed)
        val userDatabase = UserDatabasePathResolver.resolve(parsed)
        require(Files.isRegularFile(database.path)) {
            "Static database does not exist or is not a regular file: ${database.path}"
        }
        StartupConfiguration(database, userDatabase, parsed.focusSystemName)
    }

    application {
    val windowState = rememberWindowState(width = 1280.dp, height = 780.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "EVE Static Map Planner",
        state = windowState,
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            startup.fold(
                onSuccess = { configuration ->
                    val staticRepository = remember(configuration) {
                        CachingStaticMapRepository(SqliteStaticMapRepository(configuration.database.path))
                    }
                    val searchRepository = remember(configuration) {
                        SqliteSystemSearchRepository(configuration.database.path)
                    }
                    val viewModel = remember(configuration) {
                        MapViewModel(
                            staticMapRepository = staticRepository,
                            universeRepository = SqliteUniverseRepository(configuration.database.path),
                            focusSystemName = configuration.focusSystemName,
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
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
                            staticMapRepository = staticRepository,
                            searchRepository = searchRepository,
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                        )
                    }
                    val capitalViewModel = remember(configuration) {
                        CapitalRouteViewModel(
                            staticMapRepository = staticRepository,
                            searchRepository = searchRepository,
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                        )
                    }
                    DisposableEffect(viewModel, routeViewModel, jumpViewModel, capitalViewModel) {
                        onDispose {
                            viewModel.close()
                            routeViewModel.close()
                            jumpViewModel.close()
                            capitalViewModel.close()
                        }
                    }
                    val state by viewModel.state.collectAsState()
                    val routeState by routeViewModel.state.collectAsState()
                    val jumpState by jumpViewModel.state.collectAsState()
                    val capitalState by capitalViewModel.state.collectAsState()
                    StaticMapScreen(
                        databasePath = configuration.database.path,
                        userDatabasePath = configuration.userDatabase.path,
                        state = state,
                        routeState = routeState,
                        jumpState = jumpState,
                        capitalState = capitalState,
                        viewModel = viewModel,
                        routeViewModel = routeViewModel,
                        jumpViewModel = jumpViewModel,
                        capitalViewModel = capitalViewModel,
                    )
                },
                onFailure = { error ->
                    androidx.compose.material3.Text(
                        text = "Unable to start EVE Static Map Planner\n\n${error.message}",
                        modifier = androidx.compose.ui.Modifier.padding(24.dp),
                    )
                },
            )
        }
    }
    }
}

private data class StartupConfiguration(
    val database: ResolvedDatabasePath,
    val userDatabase: ResolvedDatabasePath,
    val focusSystemName: String?,
)
