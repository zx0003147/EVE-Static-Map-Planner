package dev.evestaticmapplanner.webpack

import dev.evestaticmapplanner.core.repository.CachingStaticMapRepository
import dev.evestaticmapplanner.data.db.StaticDatabaseMetadataReader
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
    require(args.size == 4) {
        "Usage: WebPackExportCli <static.db> <user.db> <output-directory> <desktop-version>"
    }
    val staticDatabase = normalizedPath(args[0])
    val userDatabase = normalizedPath(args[1])
    val outputDirectory = normalizedPath(args[2])
    val metadata = StaticDatabaseMetadataReader.read(staticDatabase)
    val report = WebPackExporter(
        staticMapRepository = CachingStaticMapRepository(SqliteStaticMapRepository(staticDatabase)),
        ansiblexRepository = SqliteAnsiblexRepository(userDatabase),
    ).export(
        WebPackExportRequest(
            outputDirectory = outputDirectory,
            desktopAppVersion = args[3],
            sdeBuild = metadata.sdeBuild,
        ),
    )
    println(
        "Web Pack ${report.packVersion}: ${report.counts.systems} systems, " +
            "${report.counts.stargateLinks} Stargates, ${report.counts.ansiblexLinks} Ansiblex, " +
            "${report.packSizeBytes} bytes -> ${report.outputDirectory}",
    )
}

private fun normalizedPath(value: String): Path = Path(value).toAbsolutePath().normalize()
