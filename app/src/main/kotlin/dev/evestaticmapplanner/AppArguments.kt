package dev.evestaticmapplanner

import java.nio.file.Path
import kotlin.io.path.Path

data class AppArguments(
    val databasePath: Path? = null,
    val userDatabasePath: Path? = null,
    val focusSystemName: String? = null,
) {
    companion object {
        fun parse(arguments: Array<String>): AppArguments {
            var databasePath: Path? = null
            var userDatabasePath: Path? = null
            var focusSystemName: String? = null
            var index = 0
            while (index < arguments.size) {
                when (val argument = arguments[index]) {
                    "--database" -> {
                        val value = arguments.getOrNull(++index)
                            ?: throw IllegalArgumentException("--database requires a path")
                        require(value.isNotBlank()) { "--database requires a non-blank path" }
                        databasePath = Path(value)
                    }
                    "--user-database" -> {
                        val value = arguments.getOrNull(++index)
                            ?: throw IllegalArgumentException("--user-database requires a path")
                        require(value.isNotBlank()) { "--user-database requires a non-blank path" }
                        userDatabasePath = Path(value)
                    }
                    "--focus-system" -> {
                        val value = arguments.getOrNull(++index)
                            ?: throw IllegalArgumentException("--focus-system requires a name")
                        require(value.isNotBlank()) { "--focus-system requires a non-blank name" }
                        focusSystemName = value.trim()
                    }
                    else -> throw IllegalArgumentException("Unknown argument: $argument")
                }
                index++
            }
            return AppArguments(databasePath, userDatabasePath, focusSystemName)
        }
    }
}

enum class DatabasePathSource {
    COMMAND_LINE,
    JVM_PROPERTY,
    ENVIRONMENT,
    APP_DATA,
}

data class ResolvedDatabasePath(
    val path: Path,
    val source: DatabasePathSource,
)

enum class StaticDatabaseMode {
    MANAGED,
    EXTERNAL,
}

data class ResolvedStaticDatabasePath(
    val path: Path,
    val source: DatabasePathSource,
    val mode: StaticDatabaseMode,
) {
    val managedRoot: Path?
        get() = if (mode == StaticDatabaseMode.MANAGED) path.parent?.parent else null
}

object DatabasePathResolver {
    const val JVM_PROPERTY = "eve.static.database"
    const val ENVIRONMENT_VARIABLE = "EVE_STATIC_DB"

    fun resolve(
        arguments: AppArguments,
        systemProperties: Map<String, String> = System.getProperties().entries.associate {
            it.key.toString() to it.value.toString()
        },
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        userHome: Path = Path(System.getProperty("user.home")),
    ): ResolvedStaticDatabasePath {
        arguments.databasePath?.let {
            return ResolvedStaticDatabasePath(
                it.toAbsolutePath().normalize(),
                DatabasePathSource.COMMAND_LINE,
                StaticDatabaseMode.EXTERNAL,
            )
        }
        systemProperties[JVM_PROPERTY]?.takeIf(String::isNotBlank)?.let {
            return ResolvedStaticDatabasePath(
                Path(it).toAbsolutePath().normalize(),
                DatabasePathSource.JVM_PROPERTY,
                StaticDatabaseMode.EXTERNAL,
            )
        }
        environment[ENVIRONMENT_VARIABLE]?.takeIf(String::isNotBlank)?.let {
            return ResolvedStaticDatabasePath(
                Path(it).toAbsolutePath().normalize(),
                DatabasePathSource.ENVIRONMENT,
                StaticDatabaseMode.EXTERNAL,
            )
        }
        val appData = ApplicationDirectories.root(environment, osName, userHome)
        return ResolvedStaticDatabasePath(
            appData.resolve("data").resolve("static.db").toAbsolutePath().normalize(),
            DatabasePathSource.APP_DATA,
            StaticDatabaseMode.MANAGED,
        )
    }
}

object UserDatabasePathResolver {
    const val JVM_PROPERTY = "eve.user.database"
    const val ENVIRONMENT_VARIABLE = "EVE_USER_DB"

    fun resolve(
        arguments: AppArguments,
        systemProperties: Map<String, String> = System.getProperties().entries.associate {
            it.key.toString() to it.value.toString()
        },
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        userHome: Path = Path(System.getProperty("user.home")),
    ): ResolvedDatabasePath {
        arguments.userDatabasePath?.let {
            return ResolvedDatabasePath(it.toAbsolutePath().normalize(), DatabasePathSource.COMMAND_LINE)
        }
        systemProperties[JVM_PROPERTY]?.takeIf(String::isNotBlank)?.let {
            return ResolvedDatabasePath(Path(it).toAbsolutePath().normalize(), DatabasePathSource.JVM_PROPERTY)
        }
        environment[ENVIRONMENT_VARIABLE]?.takeIf(String::isNotBlank)?.let {
            return ResolvedDatabasePath(Path(it).toAbsolutePath().normalize(), DatabasePathSource.ENVIRONMENT)
        }
        return ResolvedDatabasePath(
            ApplicationDirectories.root(environment, osName, userHome)
                .resolve("data")
                .resolve("user.db")
                .toAbsolutePath()
                .normalize(),
            DatabasePathSource.APP_DATA,
        )
    }
}

object ApplicationDirectories {
    const val APPLICATION_NAME = "EVE Static Map Planner"

    fun root(
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        userHome: Path = Path(System.getProperty("user.home")),
    ): Path {
        val platformDataDirectory = if (osName.startsWith("Windows", ignoreCase = true)) {
            environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let(::Path)
                ?: userHome.resolve("AppData").resolve("Local")
        } else if (osName.startsWith("Mac", ignoreCase = true)) {
            userHome.resolve("Library").resolve("Application Support")
        } else {
            environment["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)?.let(::Path)
                ?: userHome.resolve(".local").resolve("share")
        }
        return platformDataDirectory.resolve(APPLICATION_NAME).toAbsolutePath().normalize()
    }
}
