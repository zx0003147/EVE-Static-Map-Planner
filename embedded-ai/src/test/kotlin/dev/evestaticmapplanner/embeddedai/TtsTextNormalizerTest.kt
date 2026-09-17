package dev.evestaticmapplanner.embeddedai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsTextNormalizerTest {
    @Test
    fun `plain text and eve identifiers remain readable`() {
        val input = "你好，这是第一句。这是第二句。 Route from Atioth to L-TOFR is 24 jumps."

        assertEquals(input, TtsTextNormalizer.normalize(input))
    }

    @Test
    fun `markdown links lists headings and inline code become plain speech`() {
        val spoken = TtsTextNormalizer.normalize(
            """
            ## Route result
            1. First `mid`
            2. Read [the report](https://example.com/report)
            - Third item
            """.trimIndent(),
        )

        assertContains(spoken, "Route result")
        assertContains(spoken, "First mid.")
        assertContains(spoken, "Read the report.")
        assertContains(spoken, "Third item.")
        assertFalse(spoken.contains("https://"))
        assertFalse(spoken.contains('`'))
    }

    @Test
    fun `markdown table becomes natural speech and preserves negative decimal`() {
        val spoken = TtsTextNormalizer.normalize(
            """
            | 名称 | 星系 ID | securityStatus |
            |---|---|---|
            | Atioth | 30002489 | -0.018471 |
            """.trimIndent(),
        )

        assertEquals("Atioth，星系 ID 30002489，安全等级 负 0.018471。", spoken)
    }

    @Test
    fun `control characters code fences and excess whitespace are removed`() {
        val spoken = TtsTextNormalizer.normalize("Title\u0000\u200B\n\n```json\n{\"secret\":true}\n```\n\nFinal")

        assertEquals("Title\n\nFinal", spoken)
    }

    @Test
    fun `long text chunks in order without breaking surrogate pairs or decimals`() {
        val sentence = "Atioth 安全等级为 -0.018471。😀 "
        val normalized = sentence.repeat(80).trim()

        val chunks = TtsTextChunker.chunk(normalized, maxCodePoints = 90)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 90 })
        assertEquals(normalized.replace(Regex("\\s+"), ""), chunks.joinToString("").replace(Regex("\\s+"), ""))
        assertTrue(chunks.all { !it.contains('\uFFFD') })
    }

    @Test
    fun `oversized ascii identifier is never cut through the middle`() {
        val identifier = "EVE_" + "A".repeat(120)

        val chunks = TtsTextChunker.chunk("Route identifier $identifier remains intact.", maxCodePoints = 50)

        assertEquals(1, chunks.count { identifier in it })
        assertEquals(1, chunks.count { "EVE_" in it })
    }
}
