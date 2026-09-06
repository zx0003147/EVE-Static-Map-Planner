package dev.evestaticmapplanner.ui

import dev.evestaticmapplanner.feature.api.OverlayImage
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CharacterPortraitPresentationTest {
    @Test
    fun `same-system characters share deterministic four-portrait aggregation and overflow`() {
        val characters = listOf(
            character(5, "Zulu"),
            character(2, "bravo"),
            character(4, "Echo"),
            character(1, "Alpha"),
            character(3, "Charlie"),
        )

        val stack = presentCharacterPortraits(characters)

        assertEquals(listOf("AL", "BR", "CH", "EC"), stack.segments.map { it.fallbackText })
        assertEquals(4, stack.segments.size)
        assertEquals(1, stack.overflowCount)
    }

    @Test
    fun `portrait decode failure retains initials fallback while valid portrait is rendered`() {
        val invalid = character(1, "Pandogodzilla", OverlayImage("image/png", byteArrayOf(1)))
        val valid = character(2, "Fleet Scout", OverlayImage("image/png", PNG_BYTES))

        val stack = presentCharacterPortraits(listOf(invalid, valid))

        assertNotNull(stack.segments.single { it.fallbackText == "FS" }.image)
        assertNull(stack.segments.single { it.fallbackText == "PA" }.image)
        assertEquals("AB", characterPortraitFallback("Alpha Bravo"))
        assertEquals("CL", characterPortraitFallback("Clone"))
    }

    private fun character(id: Long, name: String, portrait: OverlayImage? = null) = TrackedCharacterSnapshot(
        id,
        name,
        TrackedCharacterAuthorizationState.CONNECTED,
        true,
        30_000_001,
        TrackedCharacterLocationStatus.CURRENT,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        TrackedCharacterOnlineState.UNKNOWN,
        null,
        null,
        portrait,
    )

    private companion object {
        val PNG_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
