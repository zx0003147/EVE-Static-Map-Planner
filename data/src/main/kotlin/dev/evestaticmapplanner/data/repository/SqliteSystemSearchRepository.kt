package dev.evestaticmapplanner.data.repository

import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.data.db.SqliteConnectionFactory
import java.nio.file.Path

class SqliteSystemSearchRepository(
    private val databasePath: Path,
) : SystemSearchRepository {
    override fun searchSystems(query: String, limit: Int): List<SolarSystem> {
        require(limit in 1..100) { "Search limit must be between 1 and 100" }
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        normalized.toIntOrNull()?.takeIf { it > 0 }?.let { id ->
            return SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
                connection.prepareStatement("SELECT * FROM systems WHERE system_id = ? LIMIT 1").use { statement ->
                    statement.setInt(1, id)
                    statement.executeQuery().use { result -> if (result.next()) listOf(result.toSolarSystem()) else emptyList() }
                }
            }
        }
        val escapedPrefix = normalized
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"
        return SqliteConnectionFactory.open(databasePath, queryOnly = true).use { connection ->
            connection.prepareStatement(
                """
                SELECT * FROM systems
                WHERE name_en LIKE ? ESCAPE '\' COLLATE NOCASE
                ORDER BY CASE WHEN name_en = ? COLLATE NOCASE THEN 0 ELSE 1 END,
                         name_en COLLATE NOCASE,
                         system_id
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, escapedPrefix)
                statement.setString(2, normalized)
                statement.setInt(3, limit)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toSolarSystem()) } }
            }
        }
    }
}
