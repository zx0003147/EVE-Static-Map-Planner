package dev.evestaticmapplanner.core.route

import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.jump.jumpTestSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapitalRouteEngineTest {
    private val systems = listOf(
        jumpTestSystem(30_000_001, xLy = 0.0),
        jumpTestSystem(30_000_002, xLy = 4.0),
        jumpTestSystem(30_000_003, xLy = 8.0),
        jumpTestSystem(30_000_004, xLy = 12.0),
    )
    private val engine = CapitalRouteEngine(
        CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(systems)),
    )

    @Test
    fun `finds a direct jump when destination is within range`() {
        val outcome = assertIs<CapitalRouteOutcome.Found>(
            engine.calculate(30_000_001, 30_000_002, JumpProfile.manual(5.0)),
        )

        assertEquals(listOf(30_000_001, 30_000_002), outcome.route.systems)
        assertEquals(1, outcome.route.totalJumps)
        assertEquals(4.0, outcome.route.legs.single().distanceLy)
    }

    @Test
    fun `BFS returns a minimum jump multi-hop route`() {
        val outcome = assertIs<CapitalRouteOutcome.Found>(
            engine.calculate(30_000_001, 30_000_004, JumpProfile.manual(5.0)),
        )

        assertEquals(listOf(30_000_001, 30_000_002, 30_000_003, 30_000_004), outcome.route.systems)
        assertEquals(3, outcome.route.totalJumps)
        assertTrue(outcome.route.legs.all { it.distanceLy <= 5.0 })
    }

    @Test
    fun `reports unreachable route when no valid mid exists`() {
        val isolated = CapitalRouteEngine(
            CapitalJumpCandidateProvider(
                UniformGridSystemPositionIndex(listOf(systems.first(), systems.last())),
            ),
        )
        assertIs<CapitalRouteOutcome.Unreachable>(
            isolated.calculate(30_000_001, 30_000_004, JumpProfile.manual(5.0)),
        )
    }
}
