package dev.evestaticmapplanner.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.forceprojection.ForceProjectionZone
import dev.evestaticmapplanner.core.forceprojection.ForceProjectionZoneSnapshot
import dev.evestaticmapplanner.core.forceprojection.ForceProjectionZoneStatus
import dev.evestaticmapplanner.core.forceprojection.SystemForceProjectionZone
import dev.evestaticmapplanner.preferences.DEFAULT_ANSIBLEX_ZONE_LABEL_FONT_SIZE_SP
import dev.evestaticmapplanner.preferences.MapDisplayPreferences
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiblexZoneLabelPresentationTest {
    @Test
    fun `shared 2D and Real 3D label style uses configured size bold weight and light shadow`() {
        val edgeColor = Color(0x7F12ABEF)
        val defaultStyle = ansiblexZoneLabelTextStyle(MapDisplayPreferences.Defaults, edgeColor)
        val largeStyle = ansiblexZoneLabelTextStyle(
            MapDisplayPreferences.Defaults.copy(ansiblexZoneLabelFontSizeSp = 16f),
            edgeColor,
        )

        assertEquals(DEFAULT_ANSIBLEX_ZONE_LABEL_FONT_SIZE_SP.sp, defaultStyle.fontSize)
        assertEquals(16.sp, largeStyle.fontSize)
        assertEquals(FontWeight.Bold, defaultStyle.fontWeight)
        assertEquals(edgeColor, defaultStyle.color)
        assertTrue(defaultStyle.shadow != null)
        assertTrue(checkNotNull(defaultStyle.shadow).blurRadius > 0f)
    }

    @Test
    fun `single direction uses only destination Zone`() {
        val firstToSecond = present(
            AnsiblexDirection.FIRST_TO_SECOND,
            zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3),
        )
        val secondToFirst = present(
            AnsiblexDirection.SECOND_TO_FIRST,
            zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3),
        )

        assertEquals("[Z3]", firstToSecond.text)
        assertEquals("[Z1]", secondToFirst.text)
    }

    @Test
    fun `bidirectional equal known Zone collapses to one value`() {
        val label = present(
            AnsiblexDirection.BIDIRECTIONAL,
            zones(first = ForceProjectionZone.ZONE_3, second = ForceProjectionZone.ZONE_3),
        )

        assertEquals("[Z3]", label.text)
    }

    @Test
    fun `bidirectional label follows current projected left and right endpoints`() {
        val connection = connection(AnsiblexDirection.BIDIRECTIONAL)
        val zones = zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3)

        val normal = AnsiblexZoneLabelPresentation.build(connection, Offset.Zero, Offset(100f, 0f), zones)
        val swapped = AnsiblexZoneLabelPresentation.build(connection, Offset(100f, 0f), Offset.Zero, zones)

        assertEquals("[Z1/Z3]", normal.text)
        assertEquals("[Z3/Z1]", swapped.text)
    }

    @Test
    fun `near vertical ordering uses stable screen Y fallback before system ID`() {
        val connection = connection(AnsiblexDirection.BIDIRECTIONAL)
        val zones = zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3)

        val slightlyRight = AnsiblexZoneLabelPresentation.build(
            connection,
            Offset(20f, 0f),
            Offset(20.2f, 100f),
            zones,
        )
        val slightlyLeft = AnsiblexZoneLabelPresentation.build(
            connection,
            Offset(20f, 0f),
            Offset(19.8f, 100f),
            zones,
        )

        assertEquals("[Z1/Z3]", slightlyRight.text)
        assertEquals(slightlyRight.text, slightlyLeft.text)
    }

    @Test
    fun `readable angle follows projected line without turning text upside down`() {
        val zones = zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3)
        val diagonal = AnsiblexZoneLabelPresentation.build(
            connection(AnsiblexDirection.FIRST_TO_SECOND),
            Offset.Zero,
            Offset(100f, 100f),
            zones,
        )
        val nearlyReversed = AnsiblexZoneLabelPresentation.build(
            connection(AnsiblexDirection.FIRST_TO_SECOND),
            Offset.Zero,
            Offset(-100f, 1f),
            zones,
        )

        assertEquals(45f, diagonal.angleDegrees, 0.001f)
        assertTrue(nearlyReversed.angleDegrees in -1f..0f)
        assertTrue(nearlyReversed.angleDegrees in -90f..90f)
    }

    @Test
    fun `larger font measurement grows the true curve gap`() {
        val label = present(
            AnsiblexDirection.BIDIRECTIONAL,
            zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3),
        )
        val narrow = AnsiblexZoneLabelLayout.build(label, Offset(50f, 0f), measuredLabelWidthPx = 20f)
        val wide = AnsiblexZoneLabelLayout.build(label, Offset(50f, 0f), measuredLabelWidthPx = 40f)

        assertTrue(wide.gapStartT < narrow.gapStartT)
        assertTrue(wide.gapEndT > narrow.gapEndT)
        assertEquals(narrow.gapStart, narrow.firstSegment.end)
        assertEquals(narrow.gapEnd, narrow.secondSegment.start)
    }

    @Test
    fun `label center is exactly the quadratic edge midpoint`() {
        val label = present(
            AnsiblexDirection.FIRST_TO_SECOND,
            zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3),
        )
        val layout = AnsiblexZoneLabelLayout.build(
            label = label,
            control = Offset(50f, 20f),
            measuredLabelWidthPx = 24f,
        )

        assertEquals(Offset(50f, 10f), layout.center)
    }

    @Test
    fun `Real 3D camera projection changes endpoint order text and angle without reprojection`() {
        val connection = connection(AnsiblexDirection.BIDIRECTIONAL)
        val zones = zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3)
        val before = AnsiblexZoneLabelPresentation.build(
            connection,
            Offset(-40f, 20f),
            Offset(80f, 100f),
            zones,
        )
        val after = AnsiblexZoneLabelPresentation.build(
            connection,
            Offset(80f, 100f),
            Offset(-40f, 20f),
            zones,
        )

        assertEquals("[Z1/Z3]", before.text)
        assertEquals("[Z3/Z1]", after.text)
        assertEquals(before.angleDegrees, after.angleDegrees, 0.001f)
    }

    @Test
    fun `Unknown Zones retain safe endpoint semantics`() {
        val firstUnknown = zones(first = null, second = ForceProjectionZone.ZONE_3)
        val secondUnknown = zones(first = ForceProjectionZone.ZONE_1, second = null)
        val bothUnknown = zones(first = null, second = null)

        assertEquals("[?/Z3]", present(AnsiblexDirection.BIDIRECTIONAL, firstUnknown).text)
        assertEquals("[Z1/?]", present(AnsiblexDirection.BIDIRECTIONAL, secondUnknown).text)
        assertEquals("[?/?]", present(AnsiblexDirection.BIDIRECTIONAL, bothUnknown).text)
        assertEquals("[?]", present(AnsiblexDirection.FIRST_TO_SECOND, secondUnknown).text)
    }

    @Test
    fun `shared 2D and Real 3D presentation produces the same endpoint result`() {
        val connection = connection(AnsiblexDirection.BIDIRECTIONAL)
        val zones = zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3)
        val projectedFirst = Offset(-40f, 20f)
        val projectedSecond = Offset(80f, 100f)

        val twoDimensional = AnsiblexZoneLabelPresentation.build(
            connection,
            projectedFirst,
            projectedSecond,
            zones,
        )
        val realThreeDimensional = AnsiblexZoneLabelPresentation.build(
            connection,
            projectedFirst,
            projectedSecond,
            zones,
        )

        assertEquals("[Z1/Z3]", twoDimensional.text)
        assertEquals(twoDimensional.text, realThreeDimensional.text)
    }

    @Test
    fun `label inherits final edge color and alpha unchanged`() {
        val edgeColor = Color(0x7F12ABEF)
        val label = AnsiblexZoneLabelPresentation.build(
            connection(AnsiblexDirection.FIRST_TO_SECOND),
            Offset.Zero,
            Offset(100f, 0f),
            zones(first = ForceProjectionZone.ZONE_1, second = ForceProjectionZone.ZONE_3),
            color = edgeColor,
        )

        assertEquals(edgeColor, label.color)
    }

    private fun present(
        direction: AnsiblexDirection,
        zones: ForceProjectionZoneSnapshot,
    ) =
        AnsiblexZoneLabelPresentation.build(
            connection(direction),
            Offset.Zero,
            Offset(100f, 0f),
            zones,
        )

    private fun zones(
        first: ForceProjectionZone?,
        second: ForceProjectionZone?,
    ) = ForceProjectionZoneSnapshot(
        buildMap {
            first?.let { put(1, result(1, it)) }
            second?.let { put(2, result(2, it)) }
        },
    )

    private fun result(id: Int, zone: ForceProjectionZone) = SystemForceProjectionZone(
        systemId = id,
        ownerAllianceId = 10,
        capitalSystemId = 1,
        distanceLy = zone.ordinal * 5.0,
        zone = zone,
        status = ForceProjectionZoneStatus.AVAILABLE,
    )

    private fun connection(direction: AnsiblexDirection) = AnsiblexConnection(
        id = "test",
        firstSystemId = 1,
        secondSystemId = 2,
        direction = direction,
        displayName = null,
        notes = null,
        source = AnsiblexSource.MANUAL,
        sourceBatchId = null,
        enabled = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
