package dev.evestaticmapplanner.sde.io

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

data class SdeSourceFiles(
    val regions: Path,
    val constellations: Path,
    val solarSystems: Path,
    val stargates: Path,
)

object SdeSourceLocator {
    private val requiredNames = setOf(
        "mapRegions.jsonl",
        "mapConstellations.jsonl",
        "mapSolarSystems.jsonl",
        "mapStargates.jsonl",
    )

    fun locate(root: Path): SdeSourceFiles {
        require(Files.isDirectory(root)) { "SDE input is not a directory: $root" }
        val found = requiredNames.associateWith { name ->
            Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() && it.fileName.toString() == name }.toList()
            }.also { matches ->
                require(matches.size == 1) {
                    "Expected exactly one $name under $root, found ${matches.size}"
                }
            }.single()
        }
        return SdeSourceFiles(
            regions = found.getValue("mapRegions.jsonl"),
            constellations = found.getValue("mapConstellations.jsonl"),
            solarSystems = found.getValue("mapSolarSystems.jsonl"),
            stargates = found.getValue("mapStargates.jsonl"),
        )
    }
}
