package dev.evestaticmapplanner.data.db

import java.nio.file.Path
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

object SqliteConnectionFactory {
    fun open(databasePath: Path, queryOnly: Boolean = false): Connection {
        if (queryOnly) {
            require(Files.isRegularFile(databasePath)) {
                "Static database does not exist or is not a regular file: ${databasePath.toAbsolutePath()}"
            }
        }
        Class.forName("org.sqlite.JDBC")
        val connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = 5000")
            if (queryOnly) statement.execute("PRAGMA query_only = ON")
        }
        return connection
    }
}
