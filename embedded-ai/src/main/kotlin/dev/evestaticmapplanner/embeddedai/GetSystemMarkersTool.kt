package dev.evestaticmapplanner.embeddedai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.evestaticmapplanner.control.GetSystemMarkersRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.SystemMarkersDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetSystemMarkersTool(
    private val mapControlService: MapControlService,
    private val diagnostics: (String) -> Unit = {},
) : SimpleTool<GetSystemMarkersTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Read Saved Marker and temporary Mission marker information for one canonical solar-system ID. " +
        "Saved Marker data is returned only when the existing Saved Marker access permission allows it. " +
        "This tool never creates, changes, or deletes a marker.",
) {
    @Serializable
    data class Args(val systemId: Int)

    override suspend fun execute(args: Args): String = executePlannerQuery(NAME, diagnostics, query = {
        mapControlService.getSystemMarkers(
            GetSystemMarkersRequest(
                requestId = embeddedAiRequestId(),
                systemId = args.systemId,
            ),
        )
    }, serialize = SystemMarkersDto::toToolJson, mapErrorCode = ::mapMarkerToolError)

    companion object {
        const val NAME = "get_system_markers"
    }
}

private fun SystemMarkersDto.toToolJson(): String = buildJsonObject {
    put("systemId", systemId)
    val saved = savedMarker
    if (saved == null) {
        put("savedMarker", JsonNull)
    } else {
        put("savedMarker", buildJsonObject {
            put("systemId", saved.systemId)
            nullableString("name", saved.name)
            put("color", saved.color.name)
            nullableString("notes", saved.notes)
            put("children", buildJsonArray {
                saved.children.forEach { child ->
                    add(buildJsonObject {
                        put("id", child.id)
                        put("type", child.type)
                        put("orderIndex", child.orderIndex)
                    })
                }
            })
            put("createdBy", saved.createdBy.name)
        })
    }
    put("missionMarkers", buildJsonArray {
        missionMarkers.forEach { marker ->
            add(buildJsonObject {
                put("missionId", marker.missionId.value)
                put("markerId", marker.markerId.value)
                put("systemId", marker.systemId)
                put("role", marker.role.name)
                nullableString("label", marker.label)
                nullableString("notes", marker.notes)
                put("color", marker.color.name)
            })
        }
    })
}.toString()

private fun kotlinx.serialization.json.JsonObjectBuilder.nullableString(name: String, value: String?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}
