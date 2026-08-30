package dev.evestaticmapplanner.data.view

import dev.evestaticmapplanner.data.db.UserDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

data class PlanningViewRecord(
    val id: String,
    val label: String,
    val normalFromSystemId: Int? = null,
    val normalToSystemId: Int? = null,
    val normalUseAnsiblex: Boolean = false,
    val normalCalculated: Boolean = false,
    val capitalFromSystemId: Int? = null,
    val capitalToSystemId: Int? = null,
    val capitalRangeText: String = "5",
    val capitalCalculated: Boolean = false,
    val selectedRouteActionTargets: Map<String, String> = emptyMap(),
)

data class PlanningViewsRecord(
    val views: List<PlanningViewRecord>,
    val currentViewId: String,
)

interface PlanningViewRepository {
    fun load(): PlanningViewsRecord?
    fun save(state: PlanningViewsRecord)
}

class SqlitePlanningViewRepository(
    private val databasePath: Path,
    initializeDatabase: Boolean = true,
) : PlanningViewRepository {
    init {
        if (initializeDatabase) UserDatabase.initialize(databasePath)
    }

    override fun load(): PlanningViewsRecord? = UserDatabase.open(databasePath).use { connection ->
        connection.prepareStatement(
            """
            SELECT id, label, is_current,
                   normal_from_system_id, normal_to_system_id, normal_use_ansiblex, normal_calculated,
                   capital_from_system_id, capital_to_system_id, capital_range_text, capital_calculated,
                   selected_route_action_targets_json
            FROM planning_views
            ORDER BY order_index
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                val views = buildList {
                    while (result.next()) {
                        add(
                            PlanningViewRecord(
                                id = result.getString("id"),
                                label = result.getString("label"),
                                normalFromSystemId = result.nullableInt("normal_from_system_id"),
                                normalToSystemId = result.nullableInt("normal_to_system_id"),
                                normalUseAnsiblex = result.getInt("normal_use_ansiblex") == 1,
                                normalCalculated = result.getInt("normal_calculated") == 1,
                                capitalFromSystemId = result.nullableInt("capital_from_system_id"),
                                capitalToSystemId = result.nullableInt("capital_to_system_id"),
                                capitalRangeText = result.getString("capital_range_text"),
                                capitalCalculated = result.getInt("capital_calculated") == 1,
                                selectedRouteActionTargets = decodeTargets(
                                    result.getString("selected_route_action_targets_json"),
                                ),
                            )
                        )
                    }
                }
                if (views.isEmpty()) null else PlanningViewsRecord(
                    views,
                    connection.createStatement().use { current ->
                        current.executeQuery("SELECT id FROM planning_views WHERE is_current = 1").use {
                            check(it.next()) { "Planning views contain no current row" }
                            val id = it.getString(1)
                            check(!it.next()) { "Planning views contain multiple current rows" }
                            id
                        }
                    },
                )
            }
        }
    }

    override fun save(state: PlanningViewsRecord) {
        require(state.views.isNotEmpty())
        require(state.views.any { it.id == state.currentViewId })
        UserDatabase.open(databasePath).use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.executeUpdate("DELETE FROM planning_views") }
                connection.prepareStatement(
                    """
                    INSERT INTO planning_views(
                        id, label, order_index, is_current,
                        normal_from_system_id, normal_to_system_id, normal_use_ansiblex, normal_calculated,
                        capital_from_system_id, capital_to_system_id, capital_range_text, capital_calculated,
                        selected_route_action_targets_json
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    state.views.forEachIndexed { index, view ->
                        statement.setString(1, view.id)
                        statement.setString(2, view.label)
                        statement.setInt(3, index)
                        statement.setInt(4, if (view.id == state.currentViewId) 1 else 0)
                        statement.setNullableInt(5, view.normalFromSystemId)
                        statement.setNullableInt(6, view.normalToSystemId)
                        statement.setInt(7, view.normalUseAnsiblex.flag())
                        statement.setInt(8, view.normalCalculated.flag())
                        statement.setNullableInt(9, view.capitalFromSystemId)
                        statement.setNullableInt(10, view.capitalToSystemId)
                        statement.setString(11, view.capitalRangeText)
                        statement.setInt(12, view.capitalCalculated.flag())
                        statement.setString(13, encodeTargets(view.selectedRouteActionTargets))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }
}

private fun java.sql.ResultSet.nullableInt(name: String): Int? = getInt(name).let { if (wasNull()) null else it }
private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) = if (value == null) {
    setNull(index, java.sql.Types.INTEGER)
} else {
    setInt(index, value)
}
private fun Boolean.flag() = if (this) 1 else 0
private fun encodeTargets(targets: Map<String, String>): String = JsonObject(
    targets.toSortedMap().mapValues { JsonPrimitive(it.value) },
).toString()
private fun decodeTargets(value: String): Map<String, String> =
    (Json.parseToJsonElement(value) as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
