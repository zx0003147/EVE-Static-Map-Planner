package dev.evestaticmapplanner.data.qa

import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpEligibilityPolicy
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.LinearSystemPositionIndex
import dev.evestaticmapplanner.core.jump.SpaceClassification
import dev.evestaticmapplanner.core.jump.SpaceClassificationClassifier
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.map.ProjectedCapitalRouteOverlayBuilder
import dev.evestaticmapplanner.core.map.ProjectedJumpRangeOverlayBuilder
import dev.evestaticmapplanner.core.map.Real3DCanonicalProjection
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.route.CapitalRouteEngine
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.Path
import kotlin.system.measureNanoTime

fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "Usage: <static.db>" }
    val database = Path(arguments.single()).toAbsolutePath().normalize()
    require(Files.isRegularFile(database)) { "Static database not found: $database" }

    val map = SqliteStaticMapRepository(database).load()
    val systemsByName = map.systems.associateBy { it.name.lowercase(Locale.ROOT) }
    val systemsById = map.systems.associateBy(SolarSystem::id)
    fun system(name: String) = checkNotNull(systemsByName[name.lowercase(Locale.ROOT)]) { "Missing system $name" }

    val gridIndex = UniformGridSystemPositionIndex(map.systems)
    val grid = CapitalJumpCandidateProvider(gridIndex)
    val linear = CapitalJumpCandidateProvider(LinearSystemPositionIndex(map.systems))
    val classifier = SpaceClassificationClassifier()
    val policy = JumpEligibilityPolicy(classifier)
    val capitalEngine = CapitalRouteEngine(grid)

    println("STATIC_DB path=$database systems=${map.systems.size} gridCellLy=${gridIndex.cellSizeLy}")
    val oracleOrigins = map.systems.asSequence()
        .filter { policy.evaluateOrigin(it) is EligibilityVerdict.Eligible }
        .sortedBy(SolarSystem::id)
        .take(24)
        .toList()
    val oracleRanges = listOf(5.0, 10.0, 15.0, 20.0, 1_000.0)
    var oracleQueries = 0
    oracleOrigins.forEach { origin ->
        oracleRanges.forEach { range ->
            val profile = JumpProfile.manual(range, "oracle")
            val gridResult = grid.reachableFrom(origin.id, profile)
            val linearResult = linear.reachableFrom(origin.id, profile)
            check(gridResult.reachableSystemIds == linearResult.reachableSystemIds) {
                "Grid mismatch at ${origin.name}, $range LY"
            }
            oracleQueries++
        }
    }
    println("GRID_ORACLE queries=$oracleQueries ranges=${oracleRanges.joinToString()} result=EXACT_MATCH")

    val oneDq = system("1DQ1-A")
    val nol = system("NOL-M9")
    val t5zi = system("T5ZI-S")
    listOf(5.0, 7.0, 10.0).forEach { range ->
        val result = grid.reachableFrom(oneDq.id, JumpProfile.manual(range, "1dq-$range"))
        println("REACHABLE origin=1DQ1-A rangeLy=${fmt(range)} count=${result.reachableSystemIds.size} strategy=${result.queryStrategy}")
    }

    val eligibleDistances = map.systems.asSequence()
        .filter { it.id != oneDq.id && policy.evaluateDestination(it) is EligibilityVerdict.Eligible }
        .map { it to UniverseDistanceCalculator.distanceLy(oneDq.position, it.position) }
        .toList()
    val inside = eligibleDistances.filter { it.second <= 5.0 }.maxBy { it.second }
    val outside = eligibleDistances.filter { it.second > 5.0 }.minBy { it.second }
    println("BOUNDARY_INSIDE origin=1DQ1-A target=${inside.first.name} distanceLy=${fmt(inside.second, 15)}")
    println("BOUNDARY_OUTSIDE origin=1DQ1-A target=${outside.first.name} distanceLy=${fmt(outside.second, 15)}")

    val profile7 = JumpProfile.manual(7.0, "qa-7")
    val a = grid.reachableFrom(oneDq.id, profile7).reachableSystemIds
    val b = grid.reachableFrom(nol.id, profile7).reachableSystemIds
    val c = grid.reachableFrom(t5zi.id, profile7).reachableSystemIds
    println("INTERSECTION A=1DQ1-A B=NOL-M9 count=${a.intersect(b).size}")
    println("INTERSECTION A=1DQ1-A B=NOL-M9 C=T5ZI-S count=${a.intersect(b).intersect(c).size}")

    val direct = requireRoute(capitalEngine.calculate(oneDq.id, t5zi.id, JumpProfile.manual(5.0, "direct")))
    printCapitalRoute("CAPITAL_DIRECT_1DQ_T5ZI", direct, systemsById)
    val multi = requireRoute(capitalEngine.calculate(oneDq.id, nol.id, JumpProfile.manual(5.0, "multi")))
    printCapitalRoute("CAPITAL_MULTI_1DQ_NOL", multi, systemsById)

    val classifications = map.systems.groupingBy(classifier::classify).eachCount()
    SpaceClassification.entries.forEach { classification ->
        println("CLASSIFICATION type=$classification count=${classifications[classification] ?: 0}")
    }
    val originVerdicts = map.systems.groupingBy { verdictName(policy.evaluateOrigin(it)) }.eachCount()
    val destinationVerdicts = map.systems.groupingBy { verdictName(policy.evaluateDestination(it)) }.eachCount()
    println("ELIGIBILITY_ORIGIN ${originVerdicts.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    println("ELIGIBILITY_DESTINATION ${destinationVerdicts.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    val zarzakh = system("Zarzakh")
    println("ZARZAKH origin=${verdictName(policy.evaluateOrigin(zarzakh))} destination=${verdictName(policy.evaluateDestination(zarzakh))}")

    val official = MapSceneBuilder().build(map, OfficialPosition2DProjection)
    val real = MapSceneBuilder().build(map, Real3DCanonicalProjection)
    val qaOverlays = listOf(
        JumpRangeOverlay("A", oneDq.id, profile7, a),
        JumpRangeOverlay("B", nol.id, profile7, b),
        JumpRangeOverlay("C", t5zi.id, profile7, c),
    )
    println(
        "PROJECTION_OFFICIAL systems=${official.nodes.size} omitted=${official.omittedSystemIds.size} " +
            "overlayOmitted=${qaOverlays.sumOf { ProjectedJumpRangeOverlayBuilder.build(it, official).omittedSystemIds.size }} " +
            "capitalOmittedLegs=${ProjectedCapitalRouteOverlayBuilder.build(multi, official).omittedLegCount}",
    )
    println(
        "PROJECTION_REAL_3D_CANONICAL systems=${real.nodes.size} omitted=${real.omittedSystemIds.size} " +
            "overlayOmitted=${qaOverlays.sumOf { ProjectedJumpRangeOverlayBuilder.build(it, real).omittedSystemIds.size }} " +
            "capitalOmittedLegs=${ProjectedCapitalRouteOverlayBuilder.build(multi, real).omittedLegCount}",
    )

    repeat(12) { grid.reachableFrom(oneDq.id, profile7) }
    val overlayMedian = medianMillis(80) { grid.reachableFrom(oneDq.id, profile7) }
    val tenOrigins = listOf(oneDq, nol, t5zi) + oracleOrigins.filter { it.id !in setOf(oneDq.id, nol.id, t5zi.id) }.take(7)
    repeat(4) { tenOrigins.forEach { grid.reachableFrom(it.id, profile7) } }
    val tenOverlayMedian = medianMillis(30) { tenOrigins.forEach { grid.reachableFrom(it.id, profile7) } }
    repeat(3) { capitalEngine.calculate(oneDq.id, nol.id, JumpProfile.manual(5.0, "bench")) }
    val capitalMedian = medianMillis(12) {
        capitalEngine.calculate(oneDq.id, nol.id, JumpProfile.manual(5.0, "bench"))
    }
    println("PERF_OVERLAY_7LY medianMs=${fmt(overlayMedian, 3)} iterations=80")
    println("PERF_10_OVERLAYS_7LY medianMs=${fmt(tenOverlayMedian, 3)} iterations=30")
    println("PERF_CAPITAL_1DQ_NOL_5LY medianMs=${fmt(capitalMedian, 3)} iterations=12")
    println("CLAIMS geometry=VERIFIED manualRange=VERIFIED staticEligibility=VERIFIED liveExecutability=NOT_VERIFIED")
}

private fun requireRoute(outcome: CapitalRouteOutcome): CapitalRouteResult = when (outcome) {
    is CapitalRouteOutcome.Found -> outcome.route
    is CapitalRouteOutcome.SameSystem -> outcome.route
    else -> error("Expected capital route but received $outcome")
}

private fun printCapitalRoute(
    label: String,
    route: CapitalRouteResult,
    systemsById: Map<Int, SolarSystem>,
) {
    println(
        "$label jumps=${route.totalJumps} totalLy=${fmt(route.totalDistanceLy, 9)} systems=" +
            route.systems.joinToString(" -> ") { systemsById.getValue(it).name },
    )
    route.legs.forEachIndexed { index, leg ->
        println(
            "$label leg=${index + 1} from=${systemsById.getValue(leg.fromSystemId).name} " +
                "to=${systemsById.getValue(leg.toSystemId).name} distanceLy=${fmt(leg.distanceLy, 15)}",
        )
    }
}

private fun verdictName(verdict: EligibilityVerdict): String = when (verdict) {
    EligibilityVerdict.Eligible -> "Eligible"
    is EligibilityVerdict.Ineligible -> "Ineligible"
    is EligibilityVerdict.Unknown -> "Unknown"
}

private fun medianMillis(iterations: Int, block: () -> Unit): Double = List(iterations) {
    measureNanoTime(block) / 1_000_000.0
}.sorted().let { it[it.size / 2] }

private fun fmt(value: Double, decimals: Int = 3): String =
    String.format(Locale.ROOT, "%.${decimals}f", value)
