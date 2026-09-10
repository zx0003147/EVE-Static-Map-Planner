package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.PrimarySystemNodeShape
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemNodeShapePresentationTest {
    @Test
    fun `Desktop combines local and shared tags into one final primary node`() {
        val localMarker = Marker.saved(
            1,
            MarkerDraft.create(color = MarkerColor.RED),
            Instant.EPOCH,
            Instant.EPOCH,
        )
        val localFortizar = SavedMarkerChild.create("local-fortizar", 1, SavedMarkerChildType.FORTIZAR, 0)
        val sharedKeepstar = sharedMarker(1, listOf("keepstar"), SharedMarkerColor.GREEN)

        val presented = SystemNodeShapePresentationBuilder.build(
            visibleSystemIds = listOf(1),
            localMarkersBySystemId = mapOf(1 to localMarker),
            localChildrenBySystemId = mapOf(1 to listOf(localFortizar)),
            showLocalMarkers = true,
            sharedMarkerState = SharedMarkerPresentationState(markersBySystemId = mapOf(1 to sharedKeepstar)),
            screenPosition = { MapPoint(20.0, 30.0) },
        )

        assertEquals(1, presented.size)
        assertEquals(PrimarySystemNodeShape.KEEPSTAR, presented.single().shape)
        assertEquals(MapPoint(20.0, 30.0), presented.single().screenCenter)
        assertEquals(sharedMarkerColor(SharedMarkerColor.GREEN), presented.single().color)
    }

    @Test
    fun `Desktop deduplicates matching local and shared Fortizar tags`() {
        val localMarker = Marker.saved(1, MarkerDraft.create(), Instant.EPOCH, Instant.EPOCH)
        val localFortizar = SavedMarkerChild.create("local-fortizar", 1, SavedMarkerChildType.FORTIZAR, 0)

        val presented = SystemNodeShapePresentationBuilder.build(
            visibleSystemIds = listOf(1),
            localMarkersBySystemId = mapOf(1 to localMarker),
            localChildrenBySystemId = mapOf(1 to listOf(localFortizar)),
            showLocalMarkers = true,
            sharedMarkerState = SharedMarkerPresentationState(
                markersBySystemId = mapOf(1 to sharedMarker(1, listOf("fortizar"), SharedMarkerColor.BLUE)),
            ),
            screenPosition = { MapPoint(0.0, 0.0) },
        )

        assertEquals(listOf(PrimarySystemNodeShape.FORTIZAR), presented.map { it.shape })
    }

    private fun sharedMarker(
        systemId: Int,
        tags: List<String>,
        color: SharedMarkerColor,
    ) = SharedMarkerPresentation(
        markerId = "shared-$systemId",
        systemId = systemId,
        name = "Shared",
        color = color,
        tags = tags,
        notes = null,
        updatedByUserId = "user",
        updatedByDisplayName = "Pilot",
        updatedAt = Instant.EPOCH,
    )
}
