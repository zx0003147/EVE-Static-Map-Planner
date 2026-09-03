package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.feature.api.NavigationActionContext
import dev.evestaticmapplanner.feature.api.NavigationRouteActionProvider
import dev.evestaticmapplanner.feature.api.NavigationSnapshot
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.RouteActionContext
import dev.evestaticmapplanner.feature.api.RouteActionDescriptor
import dev.evestaticmapplanner.feature.api.RouteActionResult
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteActionTargetId
import dev.evestaticmapplanner.feature.api.RouteActionTargetOption
import dev.evestaticmapplanner.feature.api.RouteActionTargetSnapshot
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.featurepack.RouteActionHost
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FeaturePackMissionNavigationActionAdapterTest {
    @Test
    fun `lists safe targets and sends exact Mission Normal intent to explicit character`() = runBlocking {
        RouteActionHost().use { host ->
            val seen = AtomicReference<NavigationSnapshot?>()
            host.scopedCapability(PackId("test.navigation")).register(provider(available = true, seen = seen))
            val adapter = FeaturePackMissionNavigationActionAdapter(host)

            val targets = adapter.listTargets()
            val result = adapter.send(
                routeIdentity = "mission:mission-7:route:route-9",
                intent = NavigationIntent(30_000_001, listOf(30_000_004, 30_000_006), 30_000_009),
                characterId = "character-42",
            )

            assertEquals(1, targets.size)
            assertEquals("character-42", targets.single().characterId)
            assertEquals("Pilot Forty Two", targets.single().label)
            assertEquals("Connected", targets.single().description)
            assertEquals(NavigationActionExecutionStatus.SUCCEEDED, result.status)
            val snapshot = requireNotNull(seen.get())
            assertEquals(RouteKind.MISSION_NORMAL, snapshot.kind)
            assertEquals("mission:mission-7:route:route-9", snapshot.identity.value)
            assertEquals(listOf(30_000_004, 30_000_006, 30_000_009), snapshot.orderedTargetSystemIds)
            assertFalse(snapshot.startSystemId in snapshot.orderedTargetSystemIds)
        }
    }

    @Test
    fun `disconnected explicit character is rejected without provider execution or fallback`() = runBlocking {
        RouteActionHost().use { host ->
            val seen = AtomicReference<NavigationSnapshot?>()
            host.scopedCapability(PackId("test.navigation")).register(provider(available = false, seen = seen))
            val adapter = FeaturePackMissionNavigationActionAdapter(host)

            val result = adapter.send(
                routeIdentity = "mission:mission-7:route:route-9",
                intent = NavigationIntent(30_000_001, destinationSystemId = 30_000_009),
                characterId = "character-42",
            )

            assertEquals(NavigationActionExecutionStatus.REJECTED, result.status)
            assertEquals("The selected EVE character is disconnected or unavailable.", result.message)
            assertNull(seen.get())
        }
    }

    private fun provider(
        available: Boolean,
        seen: AtomicReference<NavigationSnapshot?>,
    ) = object : NavigationRouteActionProvider {
        override fun descriptor() = RouteActionDescriptor(
            id = "send-navigation",
            label = "Send Navigation to EVE",
            description = null,
            supportedRouteKinds = setOf(RouteKind.NORMAL, RouteKind.MISSION_NORMAL),
            targetSelectorId = "eve-character",
        )

        override fun targets() = RouteActionTargetSnapshot(
            selectorId = "eve-character",
            label = "EVE Character",
            options = listOf(
                RouteActionTargetOption(
                    RouteActionTargetId("character-42"),
                    "Pilot Forty Two",
                    if (available) "Connected" else "Disconnected",
                    available,
                ),
            ),
        )

        override fun execute(context: RouteActionContext) =
            RouteActionResult(RouteActionStatus.REJECTED, "Calculated routes are prohibited")

        override fun executeNavigation(context: NavigationActionContext): RouteActionResult {
            seen.set(context.navigation)
            return RouteActionResult(RouteActionStatus.SUCCEEDED, "Sent")
        }
    }
}
