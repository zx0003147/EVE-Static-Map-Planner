package dev.evestaticmapplanner.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
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
}
