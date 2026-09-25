package dev.evestaticmapplanner.charactertracking

import dev.evestaticmapplanner.feature.api.OverlayImage
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterMapPresentationTest {
    @Test
    fun `single current character keeps portrait and current identity highlight`() {
        val presentation = CharacterMapPresentationBuilder.build(
            listOf(character(7, "Current Pilot", 30_000_001, portrait = OverlayImage("image/png", PNG_BYTES))),
            currentIdentityCharacterId = 7,
        )

        val character = presentation.characters.single()
        val marker = presentation.markers.single()
        assertNotNull(character.portrait.segments.single().image)
        assertTrue(character.isCurrentIdentity)
        assertTrue(marker.containsCurrentIdentity)
        assertEquals(30_000_001, marker.systemId)
    }

    @Test
    fun `characters use deterministic grouping with four portraits and overflow`() {
        val presentation = CharacterMapPresentationBuilder.build(
            listOf(
                character(5, "Zulu", 30_000_001),
                character(2, "bravo", 30_000_001),
                character(6, "Remote", 30_000_002),
                character(4, "Echo", 30_000_001),
                character(1, "Alpha", 30_000_001),
                character(3, "Charlie", 30_000_001),
            ),
            currentIdentityCharacterId = null,
        )

        assertEquals(listOf(30_000_001, 30_000_002), presentation.markers.map { it.systemId })
        val grouped = presentation.markers.first()
        assertEquals(listOf("Alpha", "bravo", "Charlie", "Echo", "Zulu"), grouped.characters.map { it.characterName })
        assertEquals(listOf("AL", "BR", "CH", "EC"), grouped.portraits.segments.map { it.fallbackText })
        assertEquals(1, grouped.portraits.overflowCount)
    }

    @Test
    fun `disabled and unknown characters stay in list but do not create markers`() {
        val disabled = character(1, "Disabled", 30_000_001, trackingEnabled = false)
        val unknown = character(2, "Unknown", null, TrackedCharacterLocationStatus.UNKNOWN)
        val current = character(3, "Visible", 30_000_002)

        val presentation = CharacterMapPresentationBuilder.build(
            listOf(disabled, unknown, current),
            currentIdentityCharacterId = null,
        )

        assertEquals(listOf("Disabled", "Unknown", "Visible"), presentation.characters.map { it.characterName })
        assertEquals(listOf(30_000_002), presentation.markers.map { it.systemId })
    }

    @Test
    fun `stale and degraded state is retained for marker styling and tooltip data`() {
        val presentation = CharacterMapPresentationBuilder.build(
            listOf(
                character(1, "Stale", 30_000_001, TrackedCharacterLocationStatus.STALE),
                character(2, "Degraded", 30_000_001, TrackedCharacterLocationStatus.DEGRADED),
            ),
            currentIdentityCharacterId = null,
        )

        val marker = presentation.markers.single()
        assertTrue(marker.hasStaleLocation)
        assertTrue(marker.hasDegradedLocation)
        assertFalse(marker.allLocationsStale)
        assertEquals(
            listOf(TrackedCharacterLocationStatus.DEGRADED, TrackedCharacterLocationStatus.STALE),
            marker.characters.map { it.locationStatus },
        )
    }

    private fun character(
        id: Long,
        name: String,
        systemId: Int?,
        status: TrackedCharacterLocationStatus = TrackedCharacterLocationStatus.CURRENT,
        trackingEnabled: Boolean = true,
        portrait: OverlayImage? = null,
    ) = TrackedCharacterSnapshot(
        id,
        name,
        TrackedCharacterAuthorizationState.CONNECTED,
        trackingEnabled,
        systemId,
        status,
        systemId?.let { Instant.EPOCH },
        systemId?.let { Instant.EPOCH },
        systemId?.let { Instant.EPOCH },
        TrackedCharacterOnlineState.UNKNOWN,
        null,
        if (status == TrackedCharacterLocationStatus.DEGRADED) {
            dev.evestaticmapplanner.feature.api.TrackedCharacterErrorCategory.NETWORK
        } else {
            null
        },
        portrait,
    )

    private companion object {
        val PNG_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
