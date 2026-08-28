package dev.evestaticmapplanner.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.feature.api.SystemInfoField
import dev.evestaticmapplanner.feature.api.SystemInfoSection
import dev.evestaticmapplanner.feature.api.SystemInfoState
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.preferences.AppPreferences
import dev.evestaticmapplanner.route.RoutePlannerUiState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CompactSystemInfoCardTest {
    @Test
    fun `no selection hides compact card`() {
        val presentation = CompactSystemInfoPresentationBuilder.build(
            state = MapUiState(hoveredSystemId = 99),
            routeState = RoutePlannerUiState(),
            jumpState = JumpOverlayUiState(),
        )

        assertNull(presentation)
    }

    @Test
    fun `selected system shows all current details in compact presentation`() {
        val state = selectedState(system(2, "1DQ1-A"), hoveredSystemId = 99)
        val routeState = RoutePlannerUiState(
            ansiblexConnections = (3..8).map { other -> connection(other) },
        )
        val jumpState = JumpOverlayUiState(
            overlays = listOf(
                overlay("first", "Bridge Range", enabled = true),
                overlay("second", null, enabled = true),
                overlay("disabled", "Hidden", enabled = false),
            ),
            intersectionSystemIds = setOf(2),
        )

        val presentation = assertNotNull(
            CompactSystemInfoPresentationBuilder.build(state, routeState, jumpState),
        )

        assertEquals("1DQ1-A", presentation.title)
        assertEquals("Delve · 1-A81R", presentation.subtitle)
        assertEquals(
            mapOf(
                "System ID" to "2",
                "Security" to "-0.390000",
                "Stargates" to "0",
                "Ansiblex" to "6",
                "Jump Coverage" to "2",
            ),
            presentation.fields.associate { it.label to it.value },
        )
        assertEquals(5, presentation.ansiblexConnections.size)
        assertEquals(listOf("Bridge Range", "second"), presentation.jumpOverlayLabels)
        assertTrue(presentation.isInJumpIntersection)
        assertFalse(presentation.isLoading)
    }

    @Test
    fun `selection changes card and hover cannot replace selected content`() {
        val first = CompactSystemInfoPresentationBuilder.build(
            selectedState(system(2, "1DQ1-A"), hoveredSystemId = 90),
            RoutePlannerUiState(),
            JumpOverlayUiState(),
        )
        val sameSelectionDifferentHover = CompactSystemInfoPresentationBuilder.build(
            selectedState(system(2, "1DQ1-A"), hoveredSystemId = 91),
            RoutePlannerUiState(),
            JumpOverlayUiState(),
        )
        val second = CompactSystemInfoPresentationBuilder.build(
            selectedState(system(9, "T5ZI-S"), hoveredSystemId = 2),
            RoutePlannerUiState(),
            JumpOverlayUiState(),
        )

        assertEquals(first, sameSelectionDifferentHover)
        assertEquals("1DQ1-A", first?.title)
        assertEquals("T5ZI-S", second?.title)
        assertEquals(9, second?.selectedSystemId)
    }

    @Test
    fun `matching extension state is appended without replacing core information`() {
        val extension = SystemInfoSection(
            sectionId = "fixture",
            title = "Fixture Information",
            fields = listOf(SystemInfoField("source", "Source", "Feature Pack")),
        )

        val presentation = assertNotNull(
            CompactSystemInfoPresentationBuilder.build(
                selectedState(system(2, "1DQ1-A")),
                RoutePlannerUiState(),
                JumpOverlayUiState(),
                systemInfoState = SystemInfoState(2, listOf(extension)),
            ),
        )

        assertEquals("1DQ1-A", presentation.title)
        assertEquals("2", presentation.fields.first { it.label == "System ID" }.value)
        assertEquals(listOf("fixture"), presentation.extensionSections.map { it.sectionId })
    }

    @Test
    fun `stale extension state is not shown after map selection changes`() {
        val stale = SystemInfoState(
            2,
            listOf(SystemInfoSection(
                "fixture",
                "Fixture",
                fields = listOf(SystemInfoField("status", "Status", "Stale")),
            )),
        )

        val presentation = assertNotNull(
            CompactSystemInfoPresentationBuilder.build(
                selectedState(system(9, "T5ZI-S")),
                RoutePlannerUiState(),
                JumpOverlayUiState(),
                systemInfoState = stale,
            ),
        )

        assertTrue(presentation.extensionSections.isEmpty())
    }

    @Test
    fun `selected marker is shown in compact card even while details load`() {
        val marker = Marker.temporary(
            2,
            MarkerDraft.create(name = "Staging", notes = "Form here before moving.", color = MarkerColor.PURPLE),
        )
        val loadingState = MapUiState(selectedSystemId = 2)

        val presentation = assertNotNull(
            CompactSystemInfoPresentationBuilder.build(
                loadingState,
                RoutePlannerUiState(),
                JumpOverlayUiState(),
                marker,
            ),
        )

        assertTrue(presentation.isLoading)
        assertEquals("◇", presentation.marker?.glyph)
        assertEquals("Temporary", presentation.marker?.persistenceLabel)
        assertEquals("Staging", presentation.marker?.name)
        assertEquals("Form here before moving.", presentation.marker?.notes)
        assertEquals(MarkerColor.PURPLE, presentation.marker?.color)
    }

    @Test
    fun `compact marker presentation distinguishes named and unnamed temporary and saved markers`() {
        val draft = MarkerDraft.create(name = "Staging", notes = "Line one\nLine two", color = MarkerColor.GREEN)
        val markers = listOf(
            Marker.temporary(2) to ("◇" to "Temporary"),
            Marker.temporary(2, draft) to ("◇" to "Temporary"),
            Marker.saved(2, MarkerDraft.create(), Instant.EPOCH, Instant.EPOCH) to ("◆" to "Saved"),
            Marker.saved(2, draft, Instant.EPOCH, Instant.EPOCH) to ("◆" to "Saved"),
        )

        markers.forEach { (marker, expected) ->
            val presentation = assertNotNull(
                CompactSystemInfoPresentationBuilder.build(
                    selectedState(system(2, "1DQ1-A")),
                    RoutePlannerUiState(),
                    JumpOverlayUiState(),
                    marker,
                )?.marker,
            )
            assertEquals(expected.first, presentation.glyph)
            assertEquals(expected.second, presentation.persistenceLabel)
            assertEquals(marker.name, presentation.name)
            assertEquals(marker.notes, presentation.notes)
        }
    }

    @Test
    fun `presentation read does not rebuild scene or mutate semantic preferences route or overlays`() {
        val selectedSystem = system(2, "1DQ1-A")
        val region = region()
        val constellation = constellation()
        val scene = MapSceneBuilder().build(
            StaticMapData(listOf(selectedSystem), emptyList(), listOf(region), listOf(constellation)),
            OfficialPosition2DProjection,
        )
        val preferences = AppPreferences.Defaults
        val state = selectedState(selectedSystem).copy(
            scene = scene,
            semanticLabelModes = mapOf(MapProjectionId.OFFICIAL_2D to SemanticLabelMode.CONSTELLATION),
            appPreferences = preferences,
        )
        val routeState = RoutePlannerUiState(ansiblexConnections = listOf(connection(3)))
        val jumpState = JumpOverlayUiState(overlays = listOf(overlay("first", "Range", enabled = true)))

        val presentation = CompactSystemInfoPresentationBuilder.build(state, routeState, jumpState)

        assertNotNull(presentation)
        assertSame(scene, state.scene)
        assertSame(preferences, state.appPreferences)
        assertEquals(SemanticLabelMode.CONSTELLATION, state.semanticLabelModes[MapProjectionId.OFFICIAL_2D])
        assertEquals(listOf(connection(3)), routeState.ansiblexConnections)
        assertEquals(listOf(overlay("first", "Range", enabled = true)), jumpState.overlays)
    }

    @Test
    fun `card is bottom end bounded and below context menu layers`() {
        assertEquals(Alignment.BottomEnd, CompactSystemInfoCardDefaults.alignment)
        assertEquals(300, CompactSystemInfoCardDefaults.maxWidth.value.toInt())
        assertEquals(420, CompactSystemInfoCardDefaults.maxHeight.value.toInt())
        assertEquals(16, CompactSystemInfoCardDefaults.margin.value.toInt())
        assertEquals(12, CompactSystemInfoCardDefaults.contentPadding.value.toInt())
        assertTrue(CompactSystemInfoCardDefaults.zIndex < CONTEXT_DISMISS_Z_INDEX)
        assertTrue(CONTEXT_DISMISS_Z_INDEX < CONTEXT_MENU_Z_INDEX)
    }

    @Test
    fun `card bounds isolate only covered map points`() {
        val bounds = Rect(left = 100f, top = 200f, right = 440f, bottom = 620f)

        assertTrue(bounds.containsPoint(MapPoint(120.0, 220.0)))
        assertFalse(bounds.containsPoint(MapPoint(99.0, 220.0)))
        assertFalse(bounds.containsPoint(MapPoint(120.0, 621.0)))
        assertFalse((null as Rect?).containsPoint(MapPoint(120.0, 220.0)))
    }

    @Test
    fun `narrow card ellipsizes primary text on one line`() = runComposeUiTest {
        val longSubtitle = "An intentionally very long region and constellation name that cannot fit the compact card"
        val longValue = "An intentionally very long sovereignty owner name that must be ellipsized"
        setContent {
            MaterialTheme {
                Box(Modifier.width(600.dp)) {
                    CompactSystemInfoCard(
                        presentation = CompactSystemInfoPresentation(
                            selectedSystemId = 1,
                            title = "A very long selected solar system name that cannot fit",
                            subtitle = longSubtitle,
                            isLoading = false,
                            fields = listOf(CompactInfoField("Owner", longValue)),
                            ansiblexConnections = emptyList(),
                            jumpOverlayLabels = emptyList(),
                            isInJumpIntersection = false,
                            marker = null,
                        ),
                        onBoundsChanged = {},
                    )
                }
            }
        }

        onNodeWithTag(COMPACT_SYSTEM_INFO_CARD_TEST_TAG).assertWidthIsEqualTo(300.dp)
        val labelHeight = onNodeWithText("Owner").fetchSemanticsNode().boundsInRoot.height
        val valueHeight = onNodeWithText(longValue).fetchSemanticsNode().boundsInRoot.height
        assertEquals(labelHeight, valueHeight)
    }

    private fun selectedState(selectedSystem: SolarSystem, hoveredSystemId: Int? = null) = MapUiState(
        selectedSystemId = selectedSystem.id,
        selectedSystemDetails = SolarSystemDetails(
            system = selectedSystem,
            region = region(),
            constellation = constellation(),
            stargates = emptyList(),
        ),
        hoveredSystemId = hoveredSystemId,
    )

    private fun system(id: Int, name: String) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 1,
        name = name,
        securityStatus = -0.39,
        securityClass = null,
        position = UniversePosition(1.0, 2.0, 3.0),
        schematicPosition = SchematicPosition(10.0, 20.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private fun region() = Region(1, "Delve", UniversePosition(0.0, 0.0, 0.0), null)

    private fun constellation() = Constellation(10, 1, "1-A81R", UniversePosition(0.0, 0.0, 0.0), null)

    private fun connection(otherSystemId: Int) = AnsiblexConnection(
        id = "2-$otherSystemId",
        firstSystemId = 2,
        secondSystemId = otherSystemId,
        direction = AnsiblexDirection.BIDIRECTIONAL,
        displayName = null,
        notes = null,
        source = AnsiblexSource.MANUAL,
        sourceBatchId = null,
        enabled = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun overlay(id: String, label: String?, enabled: Boolean) = JumpRangeOverlay(
        id = id,
        originSystemId = 2,
        profile = JumpProfile.manual(7.0),
        reachableSystemIds = setOf(2, 3),
        enabled = enabled,
        label = label,
    )
}
