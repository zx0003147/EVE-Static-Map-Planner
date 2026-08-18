package dev.evestaticmapplanner

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseValidator
import dev.evestaticmapplanner.sde.update.ActivationOutcome
import dev.evestaticmapplanner.sde.update.ManagedStaticDataPaths
import dev.evestaticmapplanner.sde.update.ManagedStaticDataCleaner
import dev.evestaticmapplanner.sde.update.PendingUpdateActivator
import java.nio.file.Files
import java.nio.file.Path

data class StartupConfiguration(
    val database: ResolvedStaticDatabasePath,
    val userDatabase: ResolvedDatabasePath,
    val focusSystemName: String?,
    val managedPaths: ManagedStaticDataPaths? = null,
    val notice: String? = null,
)

sealed interface StartupResolution {
    data class Ready(val configuration: StartupConfiguration) : StartupResolution
    data class Bootstrap(val configuration: StartupConfiguration, val message: String? = null) : StartupResolution
    data class ExternalPathError(val path: java.nio.file.Path, val message: String) : StartupResolution
    data class Fatal(val message: String) : StartupResolution
}

class StartupCoordinator(
    private val activatorFactory: (ManagedStaticDataPaths) -> PendingUpdateActivator = ::PendingUpdateActivator,
) {
    fun resolve(
        arguments: AppArguments,
        systemProperties: Map<String, String> = System.getProperties().entries.associate {
            it.key.toString() to it.value.toString()
        },
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        userHome: Path = Path.of(System.getProperty("user.home")),
    ): StartupResolution {
        val database = DatabasePathResolver.resolve(arguments, systemProperties, environment, osName, userHome)
        val userDatabase = UserDatabasePathResolver.resolve(arguments, systemProperties, environment, osName, userHome)
        val base = StartupConfiguration(database, userDatabase, arguments.focusSystemName)
        if (database.mode == StaticDatabaseMode.EXTERNAL) {
            if (!Files.isRegularFile(database.path)) {
                return StartupResolution.ExternalPathError(
                    database.path,
                    "External static database does not exist or is not a regular file. Managed installation is disabled for explicit database paths.",
                )
            }
            return validateReady(base)
        }

        val managedRoot = database.managedRoot
            ?: return StartupResolution.Fatal("Managed static database path has no application root")
        val paths = ManagedStaticDataPaths(managedRoot)
        val configuration = base.copy(managedPaths = paths)
        val activation = activatorFactory(paths).recoverAndApplyPending()
        if (activation !is ActivationOutcome.Fatal) ManagedStaticDataCleaner(paths).cleanOrphans()
        return when (activation) {
            is ActivationOutcome.Fatal -> StartupResolution.Fatal(activation.message)
            is ActivationOutcome.Failed -> if (Files.isRegularFile(database.path)) {
                validateReady(configuration.copy(notice = activation.message))
            } else {
                StartupResolution.Bootstrap(configuration, activation.message)
            }
            is ActivationOutcome.RolledBack -> validateReady(
                configuration.copy(notice = "Static data update rolled back to build ${activation.activeBuild}: ${activation.reason}"),
            )
            else -> if (Files.isRegularFile(database.path)) validateReady(configuration)
            else StartupResolution.Bootstrap(configuration)
        }
    }

    private fun validateReady(configuration: StartupConfiguration): StartupResolution = try {
        StaticDatabaseValidator.validate(configuration.database.path)
        StaticDatabaseMetadataReader.read(configuration.database.path)
        StartupResolution.Ready(configuration)
    } catch (error: Throwable) {
        if (configuration.database.mode == StaticDatabaseMode.EXTERNAL) {
            StartupResolution.ExternalPathError(configuration.database.path, "External static database is invalid: ${error.message}")
        } else {
            StartupResolution.Fatal("Managed static database is invalid: ${error.message}")
        }
    }
}
