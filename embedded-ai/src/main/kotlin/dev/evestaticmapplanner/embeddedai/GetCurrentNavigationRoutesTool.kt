package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.CurrentNavigationRouteDto
import dev.evestaticmapplanner.control.CurrentNavigationRouteType
import dev.evestaticmapplanner.control.CurrentNavigationRoutesDto
import dev.evestaticmapplanner.control.CurrentNavigationSystemDto
import dev.evestaticmapplanner.control.GetCurrentNavigationRoutesRequest
import dev.evestaticmapplanner.control.MapControlService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetCurrentNavigationRoutesTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<GetCurrentNavigationRoutesTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Read the current manually planned normal and capital navigation routes exactly as they exist in the " +
        "Planner at tool execution time. This read-only tool never recalculates, displays, changes, or sends a route. " +
        "Leave routeType unset to inspect both routes. To inspect a contiguous sub-route, provide both canonical endpoint " +
        "system IDs; ambiguous repeated occurrences are returned as candidates instead of being guessed.",
) {
    @Serializable
    data class Args(
        val routeType: RouteType? = null,
        val segmentStartSystemId: Int? = null,
        val segmentEndSystemId: Int? = null,
    )

    @Serializable
    enum class RouteType {
        NORMAL,
        CAPITAL,
    }

    override suspend fun execute(args: Args): String {
        if ((args.segmentStartSystemId == null) != (args.segmentEndSystemId == null)) {
            return buildJsonObject {
                put("success", false)
                put("error", "INVALID_SEGMENT_ENDPOINTS")
                put("message", "segmentStartSystemId and segmentEndSystemId must be supplied together")
            }.toString()
        }
        return executePlannerQuery(NAME, diagnostics, query = {
            mapControlService.getCurrentNavigationRoutes(
                GetCurrentNavigationRoutesRequest(embeddedAiRequestId()),
            )
        }, serialize = { routes -> routes.toToolJson(args) })
    }

    companion object {
        const val NAME = "get_current_navigation_routes"
    }
}

private fun CurrentNavigationRoutesDto.toToolJson(args: GetCurrentNavigationRoutesTool.Args): String {
    val selectedRoutes = listOf(normal, capital).filter { route ->
        args.routeType == null || route.routeType.name == args.routeType.name
    }
    return buildJsonObject {
        put("success", true)
        put("snapshotSemantics", "CURRENT_AT_TOOL_EXECUTION")
        put("routes", buildJsonArray {
            selectedRoutes.forEach { route -> add(route.toToolJson(args)) }
        })
    }.toString()
}

private fun CurrentNavigationRouteDto.toToolJson(args: GetCurrentNavigationRoutesTool.Args) = buildJsonObject {
    put("routeType", routeType.name)
    put("status", status.name)
    put("exists", exists)
    put("valid", valid)
    put("stale", stale)
    putNullable("unavailableReason", unavailableReason)
    putNullable("startSystemId", startSystemId)
    putNullable("destinationSystemId", destinationSystemId)
    put("startSystem", systems.firstOrNull()?.toEndpointJson() ?: JsonNull)
    put("destinationSystem", systems.lastOrNull()?.toEndpointJson() ?: JsonNull)
    put("totalJumps", totalJumps)
    put("waypointSystemIds", waypointSystemIds.toJsonArray())
    putNullable("explicitDestinationSystemId", explicitDestinationSystemId)
    effectiveRangeLy?.let { put("effectiveRangeLy", it) }

    val occurrences = mutableMapOf<Int, Int>()
    put("systems", buildJsonArray {
        systems.forEach { system ->
            val occurrenceIndex = occurrences.getOrDefault(system.systemId, 0)
            occurrences[system.systemId] = occurrenceIndex + 1
            add(system.toToolJson(occurrenceIndex))
        }
    })
    put("connections", buildJsonArray {
        connections.forEach { connection ->
            add(buildJsonObject {
                put("routeIndex", connection.routeIndex)
                put("fromSystemId", connection.fromSystemId)
                putNullable(
                    "fromSystemName",
                    systems.getOrNull(connection.routeIndex)?.name,
                )
                put("toSystemId", connection.toSystemId)
                putNullable(
                    "toSystemName",
                    systems.getOrNull(connection.routeIndex + 1)?.name,
                )
                put("type", connection.type.name)
                connection.distanceLy?.let { put("distanceLy", it) }
            })
        }
    })

    val segmentStart = args.segmentStartSystemId
    val segmentEnd = args.segmentEndSystemId
    if (segmentStart != null && segmentEnd != null) {
        put("segmentQuery", segmentQueryJson(segmentStart, segmentEnd))
    }
}

private fun CurrentNavigationRouteDto.segmentQueryJson(startSystemId: Int, endSystemId: Int) = buildJsonObject {
    put("startSystemId", startSystemId)
    put("endSystemId", endSystemId)
    if (!valid) {
        put("status", "ROUTE_NOT_VALID")
        put("message", "A contiguous segment is available only from a current valid calculated route")
        return@buildJsonObject
    }
    when (val match = findCurrentRouteSegments(systems.map(CurrentNavigationSystemDto::systemId), startSystemId, endSystemId)) {
        is CurrentRouteSegmentMatch.NotFound -> {
            put("status", "NOT_FOUND")
            put("startPresent", match.startPresent)
            put("endPresent", match.endPresent)
        }
        is CurrentRouteSegmentMatch.Ambiguous -> {
            put("status", "AMBIGUOUS")
            put("candidates", buildJsonArray {
                match.candidates.forEach { range ->
                    add(buildJsonObject {
                        put("startRouteIndex", range.first)
                        put("endRouteIndex", range.last)
                    })
                }
            })
        }
        is CurrentRouteSegmentMatch.Unique -> {
            put("status", "UNIQUE")
            put("startRouteIndex", match.range.first)
            put("endRouteIndex", match.range.last)
            put("systems", buildJsonArray {
                systems.subList(match.range.first, match.range.last + 1).forEach { system ->
                    add(system.toToolJson(occurrenceIndex = null))
                }
            })
            put("connections", buildJsonArray {
                connections.subList(match.range.first, match.range.last).forEach { connection ->
                    add(buildJsonObject {
                        put("routeIndex", connection.routeIndex)
                        put("fromSystemId", connection.fromSystemId)
                        put("toSystemId", connection.toSystemId)
                        put("type", connection.type.name)
                        connection.distanceLy?.let { put("distanceLy", it) }
                    })
                }
            })
        }
    }
}

private fun CurrentNavigationSystemDto.toToolJson(occurrenceIndex: Int?) = buildJsonObject {
    put("routeIndex", routeIndex)
    occurrenceIndex?.let { put("occurrenceIndex", it) }
    put("systemId", systemId)
    putNullable("name", name)
    if (securityStatus == null) put("securityStatus", JsonNull) else put("securityStatus", securityStatus)
    putNullable("spaceClassification", spaceClassification)
    putNullable("securityBand", securityBand?.name)
}

private fun CurrentNavigationSystemDto.toEndpointJson() = buildJsonObject {
    put("systemId", systemId)
    putNullable("name", name)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Int?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

internal sealed interface CurrentRouteSegmentMatch {
    data class Unique(val range: IntRange) : CurrentRouteSegmentMatch
    data class Ambiguous(val candidates: List<IntRange>) : CurrentRouteSegmentMatch
    data class NotFound(val startPresent: Boolean, val endPresent: Boolean) : CurrentRouteSegmentMatch
}

internal fun findCurrentRouteSegments(
    systemIds: List<Int>,
    startSystemId: Int,
    endSystemId: Int,
): CurrentRouteSegmentMatch {
    val candidates = buildList {
        systemIds.forEachIndexed { startIndex, systemId ->
            if (systemId != startSystemId) return@forEachIndexed
            for (endIndex in startIndex until systemIds.size) {
                if (systemIds[endIndex] == endSystemId) add(startIndex..endIndex)
            }
        }
    }
    return when (candidates.size) {
        0 -> CurrentRouteSegmentMatch.NotFound(startSystemId in systemIds, endSystemId in systemIds)
        1 -> CurrentRouteSegmentMatch.Unique(candidates.single())
        else -> CurrentRouteSegmentMatch.Ambiguous(candidates)
    }
}
