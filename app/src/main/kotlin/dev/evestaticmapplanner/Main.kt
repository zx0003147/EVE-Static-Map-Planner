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
import dev.evestaticmapplanner.map.StaticMapScreen
import dev.evestaticmapplanner.map.MapViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files

fun main(arguments: Array<String>) {
    val startup = runCatching {
        val parsed = AppArguments.parse(arguments)
        val database = DatabasePathResolver.resolve(parsed)
        require(Files.isRegularFile(database.path)) {
            "Static database does not exist or is not a regular file: ${database.path}"
        }
        StartupConfiguration(database, parsed.focusSystemName)
    }

    application {
    val windowState = rememberWindowState(width = 820.dp, height = 500.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "EVE Static Map Planner",
        state = windowState,
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            startup.fold(
                onSuccess = { configuration ->
                    val viewModel = remember(configuration) {
                        MapViewModel(
                            staticMapRepository = SqliteStaticMapRepository(configuration.database.path),
                            universeRepository = SqliteUniverseRepository(configuration.database.path),
                            focusSystemName = configuration.focusSystemName,
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                        )
                    }
                    DisposableEffect(viewModel) {
                        onDispose(viewModel::close)
                    }
                    val state by viewModel.state.collectAsState()
                    StaticMapScreen(configuration.database.path, state, viewModel)
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
    val focusSystemName: String?,
)
