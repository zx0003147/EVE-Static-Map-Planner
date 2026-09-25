package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.charactertracking.CharacterMapPresentationBuilder
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.localization.en.EnglishMapStrings
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CharacterTrackingSystemMarkerTest {
    @Test
    fun `shared positioning supports both 2D and projected 3D screen anchors`() {
        val presentation = CharacterMapPresentationBuilder.build(
            listOf(character(1, "Pilot", 30_000_001)),
            currentIdentityCharacterId = null,
        )

        val twoDimensional = positionCharacterSystemMarkers(
            presentation,
            setOf(30_000_001),
            { "System" },
            { MapPoint(40.0, 50.0) },
            EnglishMapStrings,
        ).single()
        val real3D = positionCharacterSystemMarkers(
            presentation,
            setOf(30_000_001),
            { "System" },
            { MapPoint(70.0, 80.0) },
            EnglishMapStrings,
        ).single()

        assertEquals(40f, twoDimensional.center.x)
        assertEquals(35f, twoDimensional.center.y)
        assertEquals(70f, real3D.center.x)
        assertEquals(65f, real3D.center.y)
        assertEquals(20f, OverlaySystemMarkerVisuals.PORTRAIT_DIAMETER_PX)
    }

    @Test
    fun `unprojected systems are omitted and portrait hit test is bounded`() {
        val presentation = CharacterMapPresentationBuilder.build(
            listOf(character(1, "Pilot", 30_000_001)),
            currentIdentityCharacterId = null,
        )
        assertTrue(
            positionCharacterSystemMarkers(
                presentation,
                setOf(30_000_001),
                { "System" },
                { null },
                EnglishMapStrings,
            ).isEmpty(),
        )

        val marker = positionCharacterSystemMarkers(
            presentation,
            setOf(30_000_001),
            { "System" },
            { MapPoint(40.0, 50.0) },
            EnglishMapStrings,
        ).single()
        assertEquals(marker, hitTestCharacterSystemMarker(listOf(marker), MapPoint(40.0, 35.0)))
        assertNull(hitTestCharacterSystemMarker(listOf(marker), MapPoint(40.0, 50.0)))
    }

    @Test
    fun `tooltip includes every state and current identity badge`() {
        val presentation = CharacterMapPresentationBuilder.build(
            listOf(
                character(1, "Current", 30_000_001, TrackedCharacterLocationStatus.CURRENT),
                character(2, "Stale", 30_000_001, TrackedCharacterLocationStatus.STALE),
                character(3, "Degraded", 30_000_001, TrackedCharacterLocationStatus.DEGRADED),
            ),
            currentIdentityCharacterId = 1,
        )

        val lines = characterMarkerTooltipLines(presentation.markers.single(), "System", EnglishMapStrings)

        assertEquals("System", lines.first())
        assertTrue(lines.any { "Current identity" in it })
        assertTrue(lines.any { "Stale" in it })
        assertTrue(lines.any { "Degraded" in it })
    }

    private fun character(
        id: Long,
        name: String,
        systemId: Int,
        status: TrackedCharacterLocationStatus = TrackedCharacterLocationStatus.CURRENT,
    ) = TrackedCharacterSnapshot(
        id,
        name,
        TrackedCharacterAuthorizationState.CONNECTED,
        true,
        systemId,
        status,
        Instant.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        TrackedCharacterOnlineState.UNKNOWN,
        null,
        null,
    )
}
