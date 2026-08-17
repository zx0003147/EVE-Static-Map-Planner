package dev.evestaticmapplanner.core.jump

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class UniformGridSystemPositionIndexTest {
    private val systems = buildList {
        add(jumpTestSystem(30_000_001))
        val random = Random(51)
        repeat(600) { offset ->
            add(
                jumpTestSystem(
                    id = 30_000_002 + offset,
                    xLy = random.nextDouble(-70.0, 70.0),
                    yLy = random.nextDouble(-70.0, 70.0),
                    zLy = random.nextDouble(-70.0, 70.0),
                ),
            )
        }
    }

    @Test
    fun `grid equals linear oracle below cell size`() = assertGridMatchesLinear(5.0)

    @Test
    fun `grid equals linear oracle at cell size`() = assertGridMatchesLinear(10.0)

    @Test
    fun `grid equals linear oracle above cell size`() {
        assertGridMatchesLinear(15.0)
        assertGridMatchesLinear(20.0)
    }

    @Test
    fun `very large range falls back and still equals linear oracle`() = assertGridMatchesLinear(1_000.0)

    private fun assertGridMatchesLinear(rangeLy: Double) {
        val profile = JumpProfile.manual(rangeLy)
        val grid = CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(systems))
        val linear = CapitalJumpCandidateProvider(LinearSystemPositionIndex(systems))
        val gridResult = grid.reachableFrom(30_000_001, profile)
        val linearResult = linear.reachableFrom(30_000_001, profile)

        assertEquals(linearResult.reachableSystemIds, gridResult.reachableSystemIds)
        if (rangeLy == 1_000.0) assertEquals(PositionQueryStrategy.LINEAR_SCAN, gridResult.queryStrategy)
    }
}
