package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.data.db.UserDatabase
import dev.evestaticmapplanner.data.db.UserDatabaseException
import dev.evestaticmapplanner.data.view.PlanningViewRecord
import dev.evestaticmapplanner.data.view.PlanningViewsRecord
import dev.evestaticmapplanner.data.view.SqlitePlanningViewRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PlanningViewRepositoryTest {
    @Test
    fun `repository round trips ordered views current view routes and generic targets`() {
        val path = createTempDirectory("planning-views").resolve("user.db")
        val repository = SqlitePlanningViewRepository(path)
        val record = PlanningViewsRecord(
            views = listOf(
                PlanningViewRecord(
                    id = "view-a",
                    label = "View 1",
                    normalFromSystemId = 1,
                    normalToSystemId = 2,
                    normalUseAnsiblex = true,
                    normalCalculated = true,
                    selectedRouteActionTargets = mapOf("esi.pack:character" to "9001"),
                ),
                PlanningViewRecord(
                    id = "view-b",
                    label = "Scout",
                    capitalFromSystemId = 3,
                    capitalToSystemId = 4,
                    capitalRangeText = "7.5",
                    capitalCalculated = true,
                ),
            ),
            currentViewId = "view-b",
        )

        repository.save(record)

        assertEquals(record, repository.load())
    }

    @Test
    fun `version four migrates atomically to planning views schema`() {
        val path = createTempDirectory("planning-views-v4").resolve("user.db")
        UserDatabase.initialize(path)
        UserDatabase.open(path).use { connection ->
            connection.createStatement().use {
                it.execute("DROP TABLE planning_views")
                it.execute("DROP TABLE ai_missions")
                it.execute("PRAGMA user_version = 4")
            }
        }

        UserDatabase.initialize(path)
        assertEquals(null, SqlitePlanningViewRepository(path, initializeDatabase = false).load())

        UserDatabase.open(path).use { connection ->
            connection.createStatement().execute("DROP TABLE planning_views")
            connection.createStatement().execute("DROP TABLE ai_missions")
            connection.createStatement().execute("PRAGMA user_version = 4")
        }
        assertFailsWith<UserDatabaseException> {
            UserDatabase.initialize(path) { error("forced migration failure") }
        }
        UserDatabase.open(path).use { connection ->
            val version = connection.createStatement().executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            assertEquals(4, version)
            val exists = connection.metaData.getTables(null, null, "planning_views", arrayOf("TABLE")).use { it.next() }
            assertFalse(exists)
        }
    }
}
