package dev.evestaticmapplanner.data.mission

import dev.evestaticmapplanner.control.mission.*
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.route.*
import dev.evestaticmapplanner.data.db.UserDatabase
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.*

class SqliteMissionRepository(
    private val databasePath: Path,
    initializeDatabase: Boolean = true,
) : MissionRepository {
    init { if (initializeDatabase) UserDatabase.initialize(databasePath) }

    override fun load(): List<Mission> = UserDatabase.open(databasePath).use { connection ->
        connection.prepareStatement("SELECT payload_json FROM ai_missions ORDER BY order_index").use { statement ->
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(decodeMission(Json.parseToJsonElement(result.getString(1)).jsonObject)) }
            }
        }
    }

    override fun save(missions: List<Mission>) {
        UserDatabase.open(databasePath).use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.executeUpdate("DELETE FROM ai_missions") }
                connection.prepareStatement(
                    "INSERT INTO ai_missions(mission_id, view_id, order_index, payload_json) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    missions.forEachIndexed { index, mission ->
                        statement.setString(1, mission.missionId.value)
                        statement.setString(2, mission.viewId)
                        statement.setInt(3, index)
                        statement.setString(4, encodeMission(mission).toString())
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

private fun encodeMission(mission: Mission) = buildJsonObject {
    put("missionId", mission.missionId.value)
    put("viewId", mission.viewId)
    put("title", mission.title)
    put("createdAt", mission.createdAt.toString())
    put("revision", mission.revision)
    put("routes", buildJsonArray { mission.routes.forEach { add(encodeRoute(it)) } })
    put("jumpRanges", buildJsonArray { mission.jumpRanges.forEach { range ->
        add(buildJsonObject {
            put("id", range.jumpRangeId.value); put("origin", range.originSystemId)
            put("profileId", range.profile.id); put("profileName", range.profile.displayName); put("range", range.profile.maxRangeLy)
            put("systems", JsonArray(range.reachableSystemIds.sorted().map(::JsonPrimitive)))
            range.label?.let { put("label", it) }
        })
    } })
    put("markers", buildJsonArray { mission.markers.forEach { marker ->
        add(buildJsonObject {
            put("id", marker.markerId.value); put("systemId", marker.systemId); put("role", marker.role.name)
            marker.label?.let { put("label", it) }; marker.notes?.let { put("notes", it) }
            marker.colorOverride?.let { put("colorOverride", it.name) }
        })
    } })
}

private fun encodeRoute(route: MissionRoute): JsonObject = when (route) {
    is MissionRoute.Normal -> buildJsonObject {
        put("type", "NORMAL"); put("id", route.routeId.value)
        put("start", route.route.startSystemId); put("destination", route.route.destinationSystemId)
        put("systems", JsonArray(route.route.systems.map(::JsonPrimitive)))
        put("edges", buildJsonArray { route.route.edges.forEach { edge -> add(buildJsonObject {
            put("id", edge.id.value); put("connectionId", edge.connectionId.value)
            put("from", edge.fromSystemId); put("to", edge.toSystemId); put("type", edge.type.name)
        }) } })
    }
    is MissionRoute.Capital -> buildJsonObject {
        put("type", "CAPITAL"); put("id", route.routeId.value)
        put("start", route.route.startSystemId); put("destination", route.route.destinationSystemId)
        put("profileId", route.route.profile.id); put("profileName", route.route.profile.displayName)
        put("range", route.route.profile.maxRangeLy); put("systems", JsonArray(route.route.systems.map(::JsonPrimitive)))
        put("legs", buildJsonArray { route.route.legs.forEach { leg -> add(buildJsonObject {
            put("from", leg.fromSystemId); put("to", leg.toSystemId); put("distanceMeters", leg.distanceMeters)
        }) } })
    }
}

private fun decodeMission(value: JsonObject): Mission {
    val missionId = MissionId(value.text("missionId"))
    val routes = value.array("routes").map { decodeRoute(missionId, it.jsonObject) }
    val ranges = value.array("jumpRanges").map { item -> item.jsonObject.let { range ->
        MissionJumpRange(
            missionId, MissionJumpRangeId(range.text("id")), range.int("origin"),
            JumpProfile(range.text("profileId"), range.text("profileName"), range.double("range")),
            range.array("systems").map { it.jsonPrimitive.int }.toSet(), range.optionalText("label"),
        )
    } }
    val markers = value.array("markers").map { item -> item.jsonObject.let { marker ->
        MissionMarker(
            missionId, MissionMarkerId(marker.text("id")), marker.int("systemId"),
            MissionMarkerRole.valueOf(marker.text("role")), marker.optionalText("label"), marker.optionalText("notes"),
            marker.optionalText("colorOverride")?.let(MarkerColor::valueOf),
        )
    } }
    return Mission(
        missionId, value.text("title"), Instant.parse(value.text("createdAt")), value.long("revision"),
        routes, ranges, markers,
        buildSet { routes.forEach { addAll(it.systemIds) }; ranges.forEach { add(it.originSystemId) }; markers.forEach { add(it.systemId) } },
        value.text("viewId"),
    )
}

private fun decodeRoute(missionId: MissionId, value: JsonObject): MissionRoute {
    val routeId = MissionRouteId(value.text("id"))
    val systems = value.array("systems").map { it.jsonPrimitive.int }
    return when (value.text("type")) {
        "NORMAL" -> MissionRoute.Normal(missionId, routeId, RouteResult(
            value.int("start"), value.int("destination"), systems,
            value.array("edges").map { item -> item.jsonObject.let { edge -> RouteEdge(
                RouteEdgeId(edge.text("id")), RouteConnectionId(edge.text("connectionId")),
                edge.int("from"), edge.int("to"), RouteEdgeType.valueOf(edge.text("type")),
            ) } },
        ))
        "CAPITAL" -> MissionRoute.Capital(missionId, routeId, CapitalRouteResult(
            value.int("start"), value.int("destination"),
            JumpProfile(value.text("profileId"), value.text("profileName"), value.double("range")), systems,
            value.array("legs").map { item -> item.jsonObject.let { leg ->
                CapitalRouteLeg(leg.int("from"), leg.int("to"), leg.double("distanceMeters"))
            } },
        ))
        else -> error("Unsupported persisted Mission route type")
    }
}

private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
private fun JsonObject.optionalText(name: String) = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long
private fun JsonObject.double(name: String) = getValue(name).jsonPrimitive.double
private fun JsonObject.array(name: String) = getValue(name).jsonArray
