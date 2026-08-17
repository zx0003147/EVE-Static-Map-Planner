package dev.evestaticmapplanner.data.qa

import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.ProjectedRouteOverlayBuilder
import dev.evestaticmapplanner.core.map.RealXzProjection
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.route.NormalRouteEngine
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteGraphBuilder
import dev.evestaticmapplanner.core.route.RouteOptions
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportService
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import java.nio.file.Files
import kotlin.io.path.Path

fun main(arguments: Array<String>) {
    require(arguments.size == 4) {
        "Usage: <static.db> <fresh-user.db> <synthetic.csv> <synthetic.json>"
    }
    val staticDatabase = Path(arguments[0]).toAbsolutePath().normalize()
    val userDatabase = Path(arguments[1]).toAbsolutePath().normalize()
    val syntheticCsv = Path(arguments[2]).toAbsolutePath().normalize()
    val syntheticJson = Path(arguments[3]).toAbsolutePath().normalize()
    require(Files.isRegularFile(staticDatabase)) { "Static database not found: $staticDatabase" }
    require(!Files.exists(userDatabase)) { "Acceptance user database must not already exist: $userDatabase" }
    require(Files.isRegularFile(syntheticCsv) && Files.isRegularFile(syntheticJson)) {
        "Synthetic acceptance fixtures are missing"
    }

    val map = SqliteStaticMapRepository(staticDatabase).load()
    val universe = SqliteUniverseRepository(staticDatabase)
    val search = SqliteSystemSearchRepository(staticDatabase)
    val ansiblex = SqliteAnsiblexRepository(userDatabase)
    val importer = AnsiblexImportService(userDatabase, universe, search)
    val engine = NormalRouteEngine()
    val names = map.systems.associateBy(SolarSystem::id)

    fun system(name: String): SolarSystem = search.searchSystems(name, 20)
        .single { it.name.equals(name, ignoreCase = true) }

    fun route(
        from: String,
        to: String,
        useAnsiblex: Boolean,
        data: StaticMapData = map,
    ): RouteResult {
        val graph = RouteGraphBuilder.build(data, ansiblex.getAll())
        val outcome = engine.calculate(graph, system(from).id, system(to).id, RouteOptions(useAnsiblex))
        return when (outcome) {
            is RouteCalculationOutcome.Found -> outcome.route
            is RouteCalculationOutcome.SameSystem -> outcome.route
            else -> error("Route $from -> $to failed: $outcome")
        }
    }

    fun printRoute(label: String, route: RouteResult) {
        println(
            "$label jumps=${route.totalJumps} stargate=${route.stargateJumps} ansiblex=${route.ansiblexJumps} " +
                "systems=${route.systems.joinToString(" -> ") { names.getValue(it).name }}",
        )
    }

    printRoute("GATE_JITA_PERIMETER", route("Jita", "Perimeter", false))
    printRoute("GATE_1DQ_NOL", route("1DQ1-A", "NOL-M9", false))
    printRoute("GATE_JITA_AMARR", route("Jita", "Amarr", false))

    val merge = importer.preview(syntheticCsv, AnsiblexImportMode.MERGE)
    println(
        "PREVIEW_MERGE valid=${merge.validRowCount} invalid=${merge.invalidRowCount} duplicates=${merge.duplicateCount} " +
            "add=${merge.additions.size} update=${merge.updates.size} unchanged=${merge.unchanged.size} remove=${merge.removals.size}",
    )
    check(merge.canApply)
    importer.apply(merge)

    val repeatMerge = importer.preview(syntheticCsv, AnsiblexImportMode.MERGE)
    println(
        "VERIFY_MERGE_REPEAT add=${repeatMerge.additions.size} update=${repeatMerge.updates.size} " +
            "unchanged=${repeatMerge.unchanged.size} remove=${repeatMerge.removals.size}",
    )

    printRoute("ANSIBLEX_OFF_1DQ_NOL", route("1DQ1-A", "NOL-M9", false))
    printRoute("ANSIBLEX_ON_1DQ_NOL", route("1DQ1-A", "NOL-M9", true))
    printRoute("ANSIBLEX_OFF_GE_1DQ", route("GE-8JV", "1DQ1-A", false))
    printRoute("ANSIBLEX_ON_GE_1DQ", route("GE-8JV", "1DQ1-A", true))
    printRoute("DISABLED_VFK_6VDT", route("VFK-IV", "6VDT-H", true))

    val disabled = ansiblex.getAll().single { it.displayName == "QA VFK-6VDT" }
    check(!disabled.enabled)
    check(ansiblex.setEnabled(disabled.id, true))
    printRoute("ENABLED_VFK_6VDT", route("VFK-IV", "6VDT-H", true))

    ansiblex.addManual(
        AnsiblexDraft(
            fromSystemId = system("Amarr").id,
            toSystemId = system("Ashab").id,
            displayName = "QA Manual Preserve",
            notes = "Synthetic manual record for REPLACE acceptance",
        ),
    )
    val replace = importer.preview(syntheticJson, AnsiblexImportMode.REPLACE)
    println(
        "PREVIEW_REPLACE valid=${replace.validRowCount} invalid=${replace.invalidRowCount} " +
            "add=${replace.additions.size} update=${replace.updates.size} unchanged=${replace.unchanged.size} remove=${replace.removals.size}",
    )
    check(replace.canApply)
    importer.apply(replace)
    val afterReplace = ansiblex.getAll()
    println(
        "VERIFY_REPLACE total=${afterReplace.size} import=${afterReplace.count { it.source == AnsiblexSource.IMPORT }} " +
            "manual=${afterReplace.count { it.source == AnsiblexSource.MANUAL }} " +
            "manualPreserved=${afterReplace.any { it.displayName == "QA Manual Preserve" }}",
    )

    val overlayRoute = route("1DQ1-A", "NOL-M9", true)
    val official = ProjectedRouteOverlayBuilder.build(
        overlayRoute,
        MapSceneBuilder().build(map, OfficialPosition2DProjection),
    )
    val real = ProjectedRouteOverlayBuilder.build(
        overlayRoute,
        MapSceneBuilder().build(map, RealXzProjection),
    )
    println(
        "OVERLAY_OFFICIAL legs=${official.legs.size} omittedSystems=${official.omittedSystemIds.size} " +
            "omittedLegs=${official.omittedLegCount}",
    )
    println(
        "OVERLAY_REAL_XZ legs=${real.legs.size} omittedSystems=${real.omittedSystemIds.size} " +
            "omittedLegs=${real.omittedLegCount}",
    )
    println("USER_DB path=$userDatabase schemaVersion=1")
    println("SYNTHETIC_NOTICE QA fixtures are synthetic and are not a real alliance Jump Bridge network")
}
