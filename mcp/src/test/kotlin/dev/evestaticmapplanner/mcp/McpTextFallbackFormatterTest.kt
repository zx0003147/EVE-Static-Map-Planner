package dev.evestaticmapplanner.mcp

import dev.evestaticmapplanner.control.transport.LocalControlClientError
import dev.evestaticmapplanner.control.transport.LocalControlClientErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpTextFallbackFormatterTest {
    @Test
    fun `small search result includes canonical name and system id`() {
        val result = buildJsonObject {
            put("systems", buildJsonArray {
                add(buildJsonObject {
                    put("systemId", 30000772)
                    put("canonicalName", "C-J6MT")
                    put("regionId", 10000005)
                    put("constellationId", 20000061)
                    put("securityStatus", -0.1)
                })
            })
        }

        val text = McpTextFallbackFormatter.format("search_system", result)

        assertContains(text, "Found 1 system.")
        assertContains(text, "Name:\nC-J6MT")
        assertContains(text, "System ID:\n30000772")
        assertFalse(text.contains(result.toString()))
    }

    @Test
    fun `route result includes type endpoints jumps and bounded path`() {
        val result = buildJsonObject {
            put("startSystemId", 1)
            put("destinationSystemId", 24)
            put("systemIds", JsonArray((1..24).map(::JsonPrimitive)))
            put("totalJumps", 23)
            put("stargateJumps", 21)
            put("ansiblexJumps", 2)
        }

        val text = McpTextFallbackFormatter.format("calculate_normal_route", result)

        assertContains(text, "Route calculated.")
        assertContains(text, "From (system ID):\n1")
        assertContains(text, "To (system ID):\n24")
        assertContains(text, "Jumps:\n23")
        assertContains(text, "Route type:\nNORMAL")
        assertContains(text, "systems omitted")
        assertContains(text, "1 -> 2")
        assertTrue(text.endsWith("23 -> 24"))
    }

    @Test
    fun `mutation result includes affected ids and revision`() {
        val result = buildJsonObject {
            put("missionId", "mission-1")
            put("markerId", "marker-2")
            put("systemId", 30000142)
            put("role", "DESTINATION")
            put("revision", 7)
        }

        val text = McpTextFallbackFormatter.format("add_mission_marker", result)

        assertContains(text, "Mission marker added successfully.")
        assertContains(text, "Mission ID:\nmission-1")
        assertContains(text, "Marker ID:\nmarker-2")
        assertContains(text, "Revision:\n7")
    }

    @Test
    fun `large mission summarizes arrays without dumping reachable system ids`() {
        val reachableIds = (9_000_000..9_000_099).map(::JsonPrimitive)
        val result = buildJsonObject {
            put("missionId", "mission-large")
            put("title", "Large Mission")
            put("revision", 14)
            put("routes", buildJsonArray {
                repeat(4) { index ->
                    add(buildJsonObject {
                        put("missionId", "mission-large")
                        put("routeId", "route-$index")
                        put("type", "NORMAL")
                        put("route", buildJsonObject {
                            put("startSystemId", index + 1)
                            put("destinationSystemId", index + 2)
                            put("systemIds", JsonArray((1..100).map(::JsonPrimitive)))
                            put("totalJumps", 99)
                            put("stargateJumps", 99)
                            put("ansiblexJumps", 0)
                        })
                    })
                }
            })
            put("jumpRanges", buildJsonArray {
                repeat(12) { index ->
                    add(buildJsonObject {
                        put("missionId", "mission-large")
                        put("jumpRangeId", "range-$index")
                        put("originSystemId", 30000142 + index)
                        put("profile", buildJsonObject {
                            put("id", "custom")
                            put("displayName", "Custom")
                            put("maxRangeLy", 10.0)
                        })
                        put("reachableSystemIds", JsonArray(reachableIds))
                    })
                }
            })
            put("markers", buildJsonArray {
                repeat(20) { index ->
                    add(buildJsonObject {
                        put("markerId", "marker-$index")
                        put("systemId", 30_000_000 + index)
                        put("role", "INFO")
                    })
                }
            })
            put("referencedSystemIds", JsonArray((1..128).map(::JsonPrimitive)))
        }

        val text = McpTextFallbackFormatter.format("get_mission", result)

        assertContains(text, "Jump ranges:\n12")
        assertContains(text, "100 reachable systems")
        assertContains(text, "[4 more jump ranges omitted.]")
        assertContains(text, "[12 more markers omitted.]")
        assertFalse(text.contains("9000000"), "reachableSystemIds must not be dumped")
        assertFalse(text.contains("reachableSystemIds"))
        assertTrue(text.length <= 4_000)
    }

    @Test
    fun `system markers distinguish empty saved and Mission lifecycles`() {
        val empty = buildJsonObject {
            put("systemId", 30000142)
            put("savedMarker", kotlinx.serialization.json.JsonNull)
            put("missionMarkers", buildJsonArray { })
        }
        assertTrue(McpTextFallbackFormatter.format("get_system_markers", empty)
            .contains("No markers found for system 30000142."))

        val aggregate = buildJsonObject {
            put("systemId", 30000142)
            put("savedMarker", buildJsonObject {
                put("systemId", 30000142)
                put("name", "Logistics")
                put("color", "GREEN")
                put("notes", "Persistent notes")
                put("createdBy", "AI")
                put("children", buildJsonArray {
                    add(buildJsonObject { put("id", "child-1"); put("type", "staging"); put("orderIndex", 0) })
                })
            })
            put("missionMarkers", buildJsonArray {
                add(buildJsonObject {
                    put("missionId", "mission-1")
                    put("markerId", "marker-1")
                    put("role", "DANGER")
                    put("label", "Camp")
                    put("notes", "Temporary notes")
                    put("color", "RED")
                })
            })
        }
        val text = McpTextFallbackFormatter.format("get_system_markers", aggregate)
        assertContains(text, "Saved Marker (persistent):")
        assertContains(text, "Created by:\nAI")
        assertContains(text, "Tags:\nstaging")
        assertContains(text, "Temporary Mission Markers: 1")
        assertContains(text, "mission-1")
        assertContains(text, "Temporary notes")
    }

    @Test
    fun `saved marker creation summary includes AI provenance`() {
        val result = buildJsonObject {
            put("marker", buildJsonObject {
                put("systemId", 30000142)
                put("name", "Logistics")
                put("color", "GREEN")
                put("createdBy", "AI")
            })
        }

        val text = McpTextFallbackFormatter.format("create_saved_marker", result)

        assertContains(text, "Saved Marker created successfully.")
        assertContains(text, "System ID:\n30000142")
        assertContains(text, "Created by:\nAI")
    }

    @Test
    fun `saved marker errors provide distinct actionable fallback text`() {
        val expected = mapOf(
            LocalControlClientErrorCode.CAPABILITY_DENIED to "access is disabled",
            LocalControlClientErrorCode.MARKER_ALREADY_EXISTS to "not overwritten",
            LocalControlClientErrorCode.SYSTEM_NOT_FOUND to "does not exist",
            LocalControlClientErrorCode.INVALID_ARGUMENT to "request is invalid",
            LocalControlClientErrorCode.INVALID_MARKER_DATA to "data is invalid",
            LocalControlClientErrorCode.DATABASE_UNAVAILABLE to "storage is unavailable",
            LocalControlClientErrorCode.IDEMPOTENCY_CONFLICT to "did not match",
            LocalControlClientErrorCode.INTERNAL_ERROR to "Do not claim",
        )
        expected.forEach { (code, phrase) ->
            val text = McpTextFallbackFormatter.formatError(
                "create_saved_marker",
                LocalControlClientError(code, "sanitized"),
            )
            assertContains(text, code.name)
            assertContains(text, phrase)
        }
    }

    @Test
    fun `unrelated tools retain their original error message`() {
        val text = McpTextFallbackFormatter.formatError(
            "calculate_normal_route",
            LocalControlClientError(LocalControlClientErrorCode.INVALID_ARGUMENT, "Route endpoint is invalid"),
        )

        assertEquals("INVALID_ARGUMENT: Route endpoint is invalid", text)
    }
}
