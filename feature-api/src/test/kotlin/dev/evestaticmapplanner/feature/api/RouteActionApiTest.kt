package dev.evestaticmapplanner.feature.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class RouteActionApiTest {
    @Test
    fun `route identity validates and behaves as a value`() {
        assertEquals(RouteIdentity("route-42"), RouteIdentity("route-42"))
        assertEquals("route-42", RouteIdentity("route-42").toString())

        listOf("", " ", " leading", "trailing ", "line\nbreak", "x".repeat(257)).forEach { value ->
            assertFailsWith<IllegalArgumentException>("value=$value") { RouteIdentity(value) }
        }
    }

    @Test
    fun `route segment validates endpoints and optional distance`() {
        assertNull(RouteSegment(1, 2, RouteSegmentKind.STARGATE, null).distanceLy)
        assertEquals(0.0, RouteSegment(1, 2, RouteSegmentKind.ANSIBLEX, 0.0).distanceLy)
        assertEquals(7.5, RouteSegment(1, 2, RouteSegmentKind.CAPITAL_JUMP, 7.5).distanceLy)

        assertFailsWith<IllegalArgumentException> { RouteSegment(0, 2, RouteSegmentKind.STARGATE, null) }
        assertFailsWith<IllegalArgumentException> { RouteSegment(1, 0, RouteSegmentKind.STARGATE, null) }
        assertFailsWith<IllegalArgumentException> { RouteSegment(1, 1, RouteSegmentKind.STARGATE, null) }
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { distance ->
            assertFailsWith<IllegalArgumentException>("distance=$distance") {
                RouteSegment(1, 2, RouteSegmentKind.CAPITAL_JUMP, distance)
            }
        }
    }

    @Test
    fun `route snapshot copies inputs and exposes unmodifiable ordered collections`() {
        val systems = mutableListOf(1, 2, 3)
        val segments = mutableListOf(
            RouteSegment(1, 2, RouteSegmentKind.STARGATE, null),
            RouteSegment(2, 3, RouteSegmentKind.CAPITAL_JUMP, 4.25),
        )
        val snapshot = RouteSnapshot(RouteIdentity("route"), RouteKind.CAPITAL, 1, 3, systems, segments)

        systems[1] = 99
        segments.clear()

        assertEquals(listOf(1, 2, 3), snapshot.orderedSystemIds)
        assertEquals(2, snapshot.orderedSegments.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.orderedSystemIds as MutableList<Int>).add(4)
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.orderedSegments as MutableList<RouteSegment>).clear()
        }
    }

    @Test
    fun `route snapshot rejects invalid topology`() {
        val segment = RouteSegment(1, 2, RouteSegmentKind.STARGATE, null)
        assertFailsWith<IllegalArgumentException> {
            RouteSnapshot(RouteIdentity("route"), RouteKind.NORMAL, 0, 2, listOf(1, 2), listOf(segment))
        }
        assertFailsWith<IllegalArgumentException> {
            RouteSnapshot(RouteIdentity("route"), RouteKind.NORMAL, 1, 0, listOf(1, 2), listOf(segment))
        }
        assertFailsWith<IllegalArgumentException> {
            RouteSnapshot(RouteIdentity("route"), RouteKind.NORMAL, 1, 2, emptyList(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            RouteSnapshot(RouteIdentity("route"), RouteKind.NORMAL, 1, 2, listOf(9, 2), listOf(segment))
        }
        assertFailsWith<IllegalArgumentException> {
            RouteSnapshot(RouteIdentity("route"), RouteKind.NORMAL, 1, 2, listOf(1, 9), listOf(segment))
        }
        assertFailsWith<IllegalArgumentException> {
            RouteSnapshot(RouteIdentity("route"), RouteKind.NORMAL, 1, 2, listOf(1, 0, 2), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            RouteSnapshot(RouteIdentity("route"), RouteKind.NORMAL, 1, 2, listOf(1, 2), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            RouteSnapshot(
                RouteIdentity("route"),
                RouteKind.NORMAL,
                1,
                3,
                listOf(1, 2, 3),
                listOf(segment, RouteSegment(1, 3, RouteSegmentKind.STARGATE, null)),
            )
        }
    }

    @Test
    fun `route snapshot supports a zero-hop route`() {
        val snapshot = RouteSnapshot(
            RouteIdentity("already-there"),
            RouteKind.NORMAL,
            30_000_142,
            30_000_142,
            listOf(30_000_142),
            emptyList(),
        )

        assertEquals(listOf(30_000_142), snapshot.orderedSystemIds)
        assertEquals(emptyList(), snapshot.orderedSegments)
    }

    @Test
    fun `route action descriptor validates and copies supported kinds`() {
        val kinds = linkedSetOf(RouteKind.NORMAL, RouteKind.MISSION_NORMAL)
        val descriptor = RouteActionDescriptor("send-route", "Send route", "Sends this route", kinds)
        kinds.clear()

        assertEquals(setOf(RouteKind.NORMAL, RouteKind.MISSION_NORMAL), descriptor.supportedRouteKinds)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (descriptor.supportedRouteKinds as MutableSet<RouteKind>).clear()
        }

        listOf("", "UPPER", "bad--id", " leading").forEach { id ->
            assertFailsWith<IllegalArgumentException>("id=$id") {
                RouteActionDescriptor(id, "Label", null, setOf(RouteKind.NORMAL))
            }
        }
        listOf("", " ", " leading", "trailing ", "line\nbreak", "x".repeat(101)).forEach { label ->
            assertFailsWith<IllegalArgumentException>("label=$label") {
                RouteActionDescriptor("valid", label, null, setOf(RouteKind.NORMAL))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            RouteActionDescriptor("valid", "Label", null, emptySet())
        }
        listOf("", " leading", "trailing ", "line\nbreak", "x".repeat(241)).forEach { description ->
            assertFailsWith<IllegalArgumentException>("description=$description") {
                RouteActionDescriptor("valid", "Label", description, setOf(RouteKind.NORMAL))
            }
        }
    }

    @Test
    fun `route action target snapshots are generic validated and immutable`() {
        val options = mutableListOf(
            RouteActionTargetOption(RouteActionTargetId("42"), "Primary", "Available target"),
            RouteActionTargetOption(RouteActionTargetId("84"), "Secondary", available = false),
        )
        val snapshot = RouteActionTargetSnapshot("send-target", "Target", options)
        options.clear()

        assertEquals(listOf("42", "84"), snapshot.options.map { it.id.value })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.options as MutableList<RouteActionTargetOption>).clear()
        }
        assertFailsWith<IllegalArgumentException> {
            RouteActionTargetSnapshot(
                "send-target",
                "Target",
                listOf(
                    RouteActionTargetOption(RouteActionTargetId("42"), "One"),
                    RouteActionTargetOption(RouteActionTargetId("42"), "Two"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> { RouteActionTargetId(" ") }
    }

    @Test
    fun `route action result validates optional message`() {
        assertNull(RouteActionResult(RouteActionStatus.SUCCEEDED, null).message)
        assertEquals("Not applicable", RouteActionResult(RouteActionStatus.REJECTED, "Not applicable").message)
        RouteActionStatus.entries.forEach { status -> RouteActionResult(status, "Result") }

        listOf("", " ", " leading", "trailing ", "line\nbreak", "x".repeat(501)).forEach { message ->
            assertFailsWith<IllegalArgumentException>("message=$message") {
                RouteActionResult(RouteActionStatus.FAILED, message)
            }
        }
    }

    @Test
    fun `provider capability and registration contracts are synchronously usable`() {
        val events = mutableListOf<String>()
        val provider = object : RouteActionProvider {
            override fun descriptor() = RouteActionDescriptor(
                "test-action",
                "Test action",
                null,
                setOf(RouteKind.NORMAL),
            )

            override fun execute(context: RouteActionContext): RouteActionResult {
                events += context.route.identity.value
                return RouteActionResult(RouteActionStatus.SUCCEEDED, null)
            }
        }
        val registration = object : RouteActionRegistration {
            override fun requestTargetRefresh() {
                events += "refresh"
            }

            override fun close() {
                events += "closed"
            }
        }
        val capability = object : RouteActionCapability {
            override fun register(provider: RouteActionProvider): RouteActionRegistration {
                assertEquals("test-action", provider.descriptor().id)
                return registration
            }
        }
        val context = RouteActionContext(
            RouteSnapshot(RouteIdentity("route"), RouteKind.NORMAL, 1, 1, listOf(1), emptyList()),
        )

        val returned = capability.register(provider)
        assertIs<AutoCloseable>(returned)
        assertEquals(RouteActionStatus.SUCCEEDED, provider.execute(context).status)
        returned.requestTargetRefresh()
        returned.close()

        assertEquals(listOf("route", "refresh", "closed"), events)
    }
}
