package dev.evestaticmapplanner

import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.data.db.UserDatabase
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserComponentsInitializationTest {
    @Test
    fun `application composition initializes and validates user database exactly once`() = withTempDirectory { root ->
        val database = root.resolve("user.db")
        var initializeCalls = 0

        val components = createUserComponents(
            userDatabasePath = database,
            universeRepository = EmptyUniverseRepository,
            searchRepository = EmptySystemSearchRepository,
            databaseInitializer = { path ->
                initializeCalls += 1
                UserDatabase.initialize(path)
            },
        )

        assertEquals(1, initializeCalls)
        assertTrue(components.ansiblexRepository.getAll().isEmpty())
        assertTrue(components.savedMarkerRepository.getAll().isEmpty())
        UserDatabase.open(database).use { connection ->
            assertEquals(4, connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            })
        }
    }

    @Test
    fun `initialization failure preserves all-or-nothing component construction`() = withTempDirectory { root ->
        var initializeCalls = 0

        assertFailsWith<IllegalStateException> {
            createUserComponents(
                userDatabasePath = root.resolve("user.db"),
                universeRepository = EmptyUniverseRepository,
                searchRepository = EmptySystemSearchRepository,
                databaseInitializer = {
                    initializeCalls += 1
                    error("forced initialization failure")
                },
            )
        }

        assertEquals(1, initializeCalls)
    }

    private inline fun withTempDirectory(block: (java.nio.file.Path) -> Unit) {
        val root = createTempDirectory("user-components-initialization-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private object EmptyUniverseRepository : UniverseRepository {
        override fun getRegion(id: Int) = null
        override fun getConstellation(id: Int) = null
        override fun getSystem(id: Int) = null
        override fun findSystemByName(name: String) = null
        override fun getSystemDetails(id: Int) = null
    }

    private object EmptySystemSearchRepository : SystemSearchRepository {
        override fun searchSystems(query: String, limit: Int) = emptyList<dev.evestaticmapplanner.core.model.SolarSystem>()
    }
}
