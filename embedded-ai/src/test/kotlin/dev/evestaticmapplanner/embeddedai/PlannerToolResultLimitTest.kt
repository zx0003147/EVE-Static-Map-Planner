package dev.evestaticmapplanner.embeddedai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlannerToolResultLimitTest {
    @Test
    fun `tool results are accepted at the bound and rejected above it`() {
        assertEquals(MAX_TOOL_RESULT_CHARS, "x".repeat(MAX_TOOL_RESULT_CHARS).boundedToolResult().length)

        val failure = assertFailsWith<EmbeddedAiToolException> {
            "x".repeat(MAX_TOOL_RESULT_CHARS + 1).boundedToolResult()
        }
        assertTrue(failure.message.orEmpty().startsWith("RESULT_TOO_LARGE:"))
    }
}
