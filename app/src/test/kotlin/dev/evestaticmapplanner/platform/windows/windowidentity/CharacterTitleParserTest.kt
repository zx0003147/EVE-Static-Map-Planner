package dev.evestaticmapplanner.platform.windows.windowidentity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CharacterTitleParserTest {
    @Test
    fun `exact historical EVE title extracts character name`() {
        val result = assertIs<CharacterTitleParseResult.Match>(CharacterTitleParser.parse("EVE - Character Name"))

        assertEquals("Character Name", result.characterName)
    }

    @Test
    fun `unicode character name is preserved`() {
        val result = assertIs<CharacterTitleParseResult.Match>(CharacterTitleParser.parse("EVE - 星海 航行者"))

        assertEquals("星海 航行者", result.characterName)
    }

    @Test
    fun `blank and unexpected titles are not guessed`() {
        assertIs<CharacterTitleParseResult.NoMatch>(CharacterTitleParser.parse(""))
        assertIs<CharacterTitleParseResult.NoMatch>(CharacterTitleParser.parse("   "))
        assertIs<CharacterTitleParseResult.NoMatch>(CharacterTitleParser.parse("EVE Online"))
        assertIs<CharacterTitleParseResult.NoMatch>(CharacterTitleParser.parse("Character Name - EVE"))
    }

    @Test
    fun `unusual edge whitespace is rejected rather than normalized`() {
        assertIs<CharacterTitleParseResult.NoMatch>(CharacterTitleParser.parse(" EVE - Character Name"))
        assertIs<CharacterTitleParseResult.NoMatch>(CharacterTitleParser.parse("EVE - Character Name "))
        assertIs<CharacterTitleParseResult.NoMatch>(CharacterTitleParser.parse("EVE -  Character Name"))
    }

    @Test
    fun `control characters are rejected`() {
        assertIs<CharacterTitleParseResult.NoMatch>(CharacterTitleParser.parse("EVE - Character\nName"))
    }
}
