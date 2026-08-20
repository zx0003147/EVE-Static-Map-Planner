package dev.evestaticmapplanner

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppArgumentsTest {
    @Test
    fun `command line parses database and focus system`() {
        val arguments = AppArguments.parse(
            arrayOf(
                "--database", "C:\\data\\static.db",
                "--user-database", "C:\\data\\user.db",
                "--focus-system", "  Jita  ",
            ),
        )

        assertEquals(Path("C:\\data\\static.db"), arguments.databasePath)
        assertEquals(Path("C:\\data\\user.db"), arguments.userDatabasePath)
        assertEquals("Jita", arguments.focusSystemName)
    }

    @Test
    fun `unknown argument is rejected`() {
        assertFailsWith<IllegalArgumentException> { AppArguments.parse(arrayOf("--route", "Jita")) }
    }

    @Test
    fun `database resolver follows approved priority`() {
        val commandLine = DatabasePathResolver.resolve(
            arguments = AppArguments(Path("command.db")),
            systemProperties = mapOf(DatabasePathResolver.JVM_PROPERTY to "property.db"),
            environment = mapOf(DatabasePathResolver.ENVIRONMENT_VARIABLE to "environment.db"),
            userHome = Path("home"),
        )
        val property = DatabasePathResolver.resolve(
            arguments = AppArguments(),
            systemProperties = mapOf(DatabasePathResolver.JVM_PROPERTY to "property.db"),
            environment = mapOf(DatabasePathResolver.ENVIRONMENT_VARIABLE to "environment.db"),
            userHome = Path("home"),
        )
        val environment = DatabasePathResolver.resolve(
            arguments = AppArguments(),
            systemProperties = emptyMap(),
            environment = mapOf(DatabasePathResolver.ENVIRONMENT_VARIABLE to "environment.db"),
            userHome = Path("home"),
        )

        assertEquals(DatabasePathSource.COMMAND_LINE, commandLine.source)
        assertEquals(StaticDatabaseMode.EXTERNAL, commandLine.mode)
        assertEquals(DatabasePathSource.JVM_PROPERTY, property.source)
        assertEquals(StaticDatabaseMode.EXTERNAL, property.mode)
        assertEquals(DatabasePathSource.ENVIRONMENT, environment.source)
        assertEquals(StaticDatabaseMode.EXTERNAL, environment.mode)
    }

    @Test
    fun `windows default uses local app data and never a project work directory`() {
        val result = DatabasePathResolver.resolve(
            arguments = AppArguments(),
            systemProperties = emptyMap(),
            environment = mapOf("LOCALAPPDATA" to "C:\\Local"),
            osName = "Windows 11",
            userHome = Path("C:\\Users\\test"),
        )

        assertEquals(DatabasePathSource.APP_DATA, result.source)
        assertTrue(result.path.toString().endsWith("EVE Static Map Planner\\data\\static.db"))
        assertTrue(!result.path.toString().contains(".sde-work"))
        assertEquals(StaticDatabaseMode.MANAGED, result.mode)

        val user = UserDatabasePathResolver.resolve(
            arguments = AppArguments(),
            systemProperties = emptyMap(),
            environment = mapOf("LOCALAPPDATA" to "C:\\Local"),
            osName = "Windows 11",
            userHome = Path("C:\\Users\\test"),
        )
        assertTrue(user.path.toString().endsWith("EVE Static Map Planner\\data\\user.db"))
    }

    @Test
    fun `application directories share one app data root`() {
        val root = ApplicationDirectories.root(
            environment = mapOf("LOCALAPPDATA" to "C:\\Isolated"),
            osName = "Windows 11",
            userHome = Path("C:\\Users\\test"),
        )

        assertTrue(root.toString().endsWith("EVE Static Map Planner"))
        assertEquals(root.resolve("data/static.db"), DatabasePathResolver.resolve(
            arguments = AppArguments(),
            systemProperties = emptyMap(),
            environment = mapOf("LOCALAPPDATA" to "C:\\Isolated"),
            osName = "Windows 11",
            userHome = Path("C:\\Users\\test"),
        ).path)
    }
}
