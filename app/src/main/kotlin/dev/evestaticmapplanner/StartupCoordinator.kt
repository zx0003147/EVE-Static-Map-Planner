package dev.evestaticmapplanner

import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.db.StaticDatabaseSchema
import dev.evestaticmapplanner.data.db.StaticDatabaseSchemaCompatibility
import dev.evestaticmapplanner.data.db.StaticDatabaseSchemaCompatibilityInspector
import dev.evestaticmapplanner.data.db.StaticDatabaseValidator
import dev.evestaticmapplanner.localization.StaticDatabaseStartupIssue
import dev.evestaticmapplanner.localization.StaticDatabaseStartupUiMessage
import dev.evestaticmapplanner.localization.UiMessage
import dev.evestaticmapplanner.sde.update.ActivationOutcome
import dev.evestaticmapplanner.sde.update.CachedOrDownloadedSdeArchiveSource
import dev.evestaticmapplanner.sde.update.DiskSpacePreflight
import dev.evestaticmapplanner.sde.update.JdkSdeHttpTransport
import dev.evestaticmapplanner.sde.update.LatestBuildCacheStore
import dev.evestaticmapplanner.sde.update.ManagedStaticDataPaths
import dev.evestaticmapplanner.sde.update.ManagedStaticDataCleaner
import dev.evestaticmapplanner.sde.update.ManagedSchemaUpgradeOutcome
import dev.evestaticmapplanner.sde.update.ManagedStaticDatabaseSchemaUpgrader
import dev.evestaticmapplanner.sde.update.PendingUpdateActivator
import dev.evestaticmapplanner.sde.update.SdeArchiveDownloader
import dev.evestaticmapplanner.sde.update.SdeCandidatePreparer
import dev.evestaticmapplanner.sde.update.SdeUpdateClient
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
    data class ExternalPathError(val path: java.nio.file.Path, val message: UiMessage, val diagnostic: String) : StartupResolution
    data class Fatal(val message: UiMessage, val diagnostic: String) : StartupResolution
}

class StartupCoordinator(
    private val activatorFactory: (ManagedStaticDataPaths) -> PendingUpdateActivator = ::PendingUpdateActivator,
    private val schemaUpgrade: (ManagedStaticDataPaths) -> ManagedSchemaUpgradeOutcome = ::upgradeManagedSchema,
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
                    startupMessage(StaticDatabaseStartupIssue.EXTERNAL_DATABASE_MISSING),
                    "External static database does not exist or is not a regular file; managed installation is disabled",
                )
            }
            return validateReady(base)
        }

        val managedRoot = database.managedRoot
            ?: return StartupResolution.Fatal(
                startupMessage(StaticDatabaseStartupIssue.MANAGED_PATH_INVALID),
                "Managed static database path has no application root",
            )
        val paths = ManagedStaticDataPaths(managedRoot)
        val configuration = base.copy(managedPaths = paths)
        val activation = activatorFactory(paths).recoverAndApplyPending()
        if (activation !is ActivationOutcome.Fatal) ManagedStaticDataCleaner(paths).cleanOrphans()
        return when (activation) {
            is ActivationOutcome.Fatal -> StartupResolution.Fatal(
                startupMessage(StaticDatabaseStartupIssue.DATABASE_INVALID),
                activation.message,
            )
            is ActivationOutcome.Failed -> if (Files.isRegularFile(database.path)) {
                resolveManagedCompatibility(configuration.copy(notice = activation.message), paths)
            } else {
                StartupResolution.Bootstrap(configuration, activation.message)
            }
            is ActivationOutcome.RolledBack -> resolveManagedCompatibility(
                configuration.copy(notice = "Static data update rolled back to build ${activation.activeBuild}: ${activation.reason}"),
                paths,
            )
            else -> if (Files.isRegularFile(database.path)) resolveManagedCompatibility(configuration, paths)
            else StartupResolution.Bootstrap(configuration)
        }
    }

    private fun resolveManagedCompatibility(
        configuration: StartupConfiguration,
        paths: ManagedStaticDataPaths,
    ): StartupResolution = when (val compatibility = StaticDatabaseSchemaCompatibilityInspector.inspect(configuration.database.path)) {
        is StaticDatabaseSchemaCompatibility.Compatible -> validateReady(configuration)
        is StaticDatabaseSchemaCompatibility.Older -> when (val upgrade = schemaUpgrade(paths)) {
            is ManagedSchemaUpgradeOutcome.Upgraded -> validateReady(
                configuration.copy(
                    notice = "Managed static database schema upgraded from v${upgrade.oldSchema} to v${upgrade.newSchema} " +
                        "for SDE build ${upgrade.build}",
                ),
            )
            is ManagedSchemaUpgradeOutcome.Failed -> StartupResolution.Fatal(
                startupMessage(
                    StaticDatabaseStartupIssue.MANAGED_SCHEMA_UPGRADE_FAILED,
                    compatibility.actualVersion,
                ),
                upgrade.message,
            )
            is ManagedSchemaUpgradeOutcome.NotRequired -> validateReady(configuration)
        }
        is StaticDatabaseSchemaCompatibility.Newer -> StartupResolution.Fatal(
            startupMessage(StaticDatabaseStartupIssue.DATABASE_SCHEMA_NEWER, compatibility.actualVersion),
            "Managed static database schema ${compatibility.actualVersion} is newer than supported schema " +
                StaticDatabaseSchema.VERSION,
        )
        is StaticDatabaseSchemaCompatibility.Invalid -> StartupResolution.Fatal(
            startupMessage(StaticDatabaseStartupIssue.DATABASE_INVALID),
            "Managed static database schema is invalid: ${compatibility.reason}",
        )
    }

    private fun validateReady(configuration: StartupConfiguration): StartupResolution = try {
        StaticDatabaseValidator.validate(configuration.database.path)
        StaticDatabaseMetadataReader.read(configuration.database.path)
        StartupResolution.Ready(configuration)
    } catch (error: Throwable) {
        if (configuration.database.mode == StaticDatabaseMode.EXTERNAL) {
            val compatibility = StaticDatabaseSchemaCompatibilityInspector.inspect(configuration.database.path)
            StartupResolution.ExternalPathError(
                configuration.database.path,
                compatibility.toUiMessage(),
                "External static database is invalid: ${error.message}",
            )
        } else {
            StartupResolution.Fatal(
                startupMessage(StaticDatabaseStartupIssue.DATABASE_INVALID),
                "Managed static database is invalid: ${error.message}",
            )
        }
    }

    private fun StaticDatabaseSchemaCompatibility.toUiMessage(): UiMessage = when (this) {
        is StaticDatabaseSchemaCompatibility.Older ->
            startupMessage(StaticDatabaseStartupIssue.DATABASE_SCHEMA_OLDER, actualVersion)
        is StaticDatabaseSchemaCompatibility.Newer ->
            startupMessage(StaticDatabaseStartupIssue.DATABASE_SCHEMA_NEWER, actualVersion)
        else -> startupMessage(StaticDatabaseStartupIssue.DATABASE_INVALID)
    }
}

private fun startupMessage(issue: StaticDatabaseStartupIssue, actualSchema: Int? = null) =
    StaticDatabaseStartupUiMessage(issue, StaticDatabaseSchema.VERSION, actualSchema)

private fun upgradeManagedSchema(paths: ManagedStaticDataPaths): ManagedSchemaUpgradeOutcome {
    val transport = JdkSdeHttpTransport("EVE-Static-Map-Planner/${ApplicationBuildInfo.current.appVersion}")
    val client = SdeUpdateClient(transport, LatestBuildCacheStore(paths))
    val downloader = SdeArchiveDownloader(transport, paths, DiskSpacePreflight())
    return ManagedStaticDatabaseSchemaUpgrader(
        paths = paths,
        archiveSource = CachedOrDownloadedSdeArchiveSource(paths, client, downloader),
        preparer = SdeCandidatePreparer(paths),
        activator = PendingUpdateActivator(paths),
    ).upgradeIfRequired()
}
