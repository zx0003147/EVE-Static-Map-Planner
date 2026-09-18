package dev.evestaticmapplanner.ai

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantComposerStateTest {
    @Test
    fun `Auto Send off overwrites the existing draft without sending`() {
        val state = AssistantComposerState().apply { prompt = TextFieldValue("old draft") }
        val sent = mutableListOf<String>()

        val shouldFocus = state.acceptVoiceTranscript("voice transcript", false, true, sent::add)

        assertTrue(shouldFocus)
        assertEquals("voice transcript", state.prompt.text)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `Auto Send on clears the draft and sends when input is available`() {
        val state = AssistantComposerState().apply { prompt = TextFieldValue("old draft") }
        val sent = mutableListOf<String>()

        val shouldFocus = state.acceptVoiceTranscript("voice transcript", true, true, sent::add)

        assertFalse(shouldFocus)
        assertEquals("", state.prompt.text)
        assertEquals(listOf("voice transcript"), sent)
    }

    @Test
    fun `Auto Send falls back to draft while the agent is busy`() {
        val state = AssistantComposerState()
        val sent = mutableListOf<String>()

        state.acceptVoiceTranscript("hold this", true, false, sent::add)

        assertEquals("hold this", state.prompt.text)
        assertTrue(sent.isEmpty())
    }
}
