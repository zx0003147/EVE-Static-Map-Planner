package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionMarker
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.core.map.MapSceneBuilder
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.MapViewport
import dev.evestaticmapplanner.core.map.OfficialPosition2DProjection
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.preferences.MarkerPreferences
import dev.evestaticmapplanner.preferences.SavedMarkerAppearancePreferences
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedUser
import java.time.Instant
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMarkerPresentationTest {
    private val system = system(1, "1DQ1-A", 15.0, 20.0)
    private val secondSystem = system(2, "NOL-M9", 25.0, 30.0)
    private val scene = MapSceneBuilder().build(
        StaticMapData(
            systems = listOf(system, secondSystem),
            connections = emptyList(),
            regions = listOf(Region(100, "Delve", UniversePosition(0.0, 0.0, 0.0), null)),
            constellations = listOf(Constellation(10, 100, "1-A81R", UniversePosition(0.0, 0.0, 0.0), null)),
        ),
        OfficialPosition2DProjection,
    )
    private val transform = MapTransform(MapViewport(scene.defaultFitBounds.center, 2.0), MapSize(800.0, 600.0))
    private val geometry = SharedMarkerVisualGeometry(
        baseRingRadiusPx = 17.0,
        localRingClearancePx = 4.0,
        primaryStrokePx = 1.75f,
        secondaryOffsetPx = 2.5,
        secondaryStrokePx = 1f,
        badgeOutwardOffsetPx = 2.5,
        badgeRadiusPx = 5.5f,
        badgeBorderWidthPx = 1f,
        badgeDotRadiusPx = 1.25f,
        badgeLinkWidthPx = 1f,
    )

    @Test
    fun `frozen Shared Marker geometry stays compact and outside the default local ring`() {
        assertEquals(17f, SHARED_MARKER_BASE_RING_RADIUS_DP)
        assertEquals(4f, SHARED_MARKER_LOCAL_RING_CLEARANCE_DP)
        assertEquals(1.75f, SHARED_MARKER_PRIMARY_STROKE_DP)
        assertEquals(2.5f, SHARED_MARKER_SECONDARY_OFFSET_DP)
        assertEquals(1f, SHARED_MARKER_SECONDARY_STROKE_DP)
        assertEquals(2.5f, SHARED_MARKER_BADGE_OUTWARD_OFFSET_DP)
        assertEquals(5.5f, SHARED_MARKER_BADGE_RADIUS_DP)
        assertEquals(11.0, AI_MISSION_MARKER_BASE_OFFSET_PX)
        assertEquals(3.0, AI_MISSION_MARKER_STAGGER_PX)
        assertEquals(7f, AI_MISSION_MARKER_OUTWARD_SPACING_DP)
        assertEquals(17.0, geometry.ringRadius(localSavedRingRadiusPx = 13.0))
        assertEquals(17.0, geometry.ringRadius(localSavedRingRadiusPx = null))
        assertEquals(19f, savedMarkerRingRenderState(SavedMarkerAppearancePreferences.Defaults).visualRadiusDp())
    }

    @Test
    fun `system name anchor follows marker composition while AI alone keeps the bare anchor`() {
        val cases = listOf(
            CompositionCase("bare system", local = false, shared = false, ai = false, expectedOffsetPx = 5.0),
            CompositionCase("Local only", local = true, shared = false, ai = false, expectedOffsetPx = 23.0),
            CompositionCase("Shared only", local = false, shared = true, ai = false, expectedOffsetPx = 24.0),
            CompositionCase("AI only", local = false, shared = false, ai = true, expectedOffsetPx = 5.0),
            CompositionCase("Local plus Shared", local = true, shared = true, ai = false, expectedOffsetPx = 24.0),
            CompositionCase("Shared plus AI", local = false, shared = true, ai = true, expectedOffsetPx = 24.0),
            CompositionCase("Local plus AI", local = true, shared = false, ai = true, expectedOffsetPx = 23.0),
            CompositionCase("Local plus Shared plus AI", local = true, shared = true, ai = true, expectedOffsetPx = 24.0),
        )

        cases.forEach { case ->
            val fixture = systemNameComposition(case.local, case.shared, case.ai)

            assertEquals(
                case.expectedOffsetPx,
                fixture.systemNameLayout.topLeft.x - fixture.systemCenter.x,
                absoluteTolerance = 0.0001,
                message = case.name,
            )
            if (case.ai) {
                assertEquals(18.0, fixture.aiCenter!!.x - fixture.systemCenter.x, absoluteTolerance = 0.0001)
                assertEquals(-18.0, fixture.aiCenter.y - fixture.systemCenter.y, absoluteTolerance = 0.0001)
            }
        }
    }

    @Test
    fun `triple composition keeps base system name outside Local and Shared geometry without moving identities`() {
        val fixture = systemNameComposition(local = true, shared = true, ai = true)
        val shared = fixture.sharedMarker!!
        val obstacles = fixture.visualObstacles!!
        val local = fixture.localMarker!!
        val localLabel = localLabelFixture(
            local = local,
            systemLabelBounds = fixture.systemNameLayout.bounds,
        )
        val sharedOuterExtent = shared.ringRadiusPx + geometry.secondaryOffsetPx + geometry.secondaryStrokePx / 2.0

        assertTrue(fixture.systemNameLayout.bounds.minX >= fixture.systemCenter.x + sharedOuterExtent + 4.0)
        assertTrue(fixture.systemNameLayout.bounds.minX >= fixture.systemCenter.x + 19.0 + 4.0)
        assertTrue(obstacles.screenBounds.none(fixture.systemNameLayout.bounds::intersects))
        assertTrue(localLabel.layout.avoidedSystemName)
        assertFalse(localLabel.layout.bounds.intersects(fixture.systemNameLayout.bounds))
        assertTrue(localLabel.layout.topLeft.y > fixture.systemCenter.y)
        assertEquals(18.0, fixture.aiCenter!!.x - fixture.systemCenter.x, absoluteTolerance = 0.0001)
        assertEquals(-18.0, fixture.aiCenter.y - fixture.systemCenter.y, absoluteTolerance = 0.0001)
        assertTrue(shared.badgeCenter.x > fixture.systemCenter.x)
        assertTrue(shared.badgeCenter.y > fixture.systemCenter.y)
    }

    @Test
    fun `normal hover selected and hover plus selected keep the marker-aware system anchor`() {
        val visibilityByState = mapOf(
            "normal" to systemNameVisibilityIds(listOf(1), emptyList(), null, null),
            "hover" to systemNameVisibilityIds(emptyList(), emptyList(), 1, null),
            "selected" to systemNameVisibilityIds(emptyList(), emptyList(), null, 1),
            "hover plus selected" to systemNameVisibilityIds(emptyList(), emptyList(), 1, 1),
        )
        val existingOffsetByState = mapOf(
            "normal" to SYSTEM_LABEL_OFFSET_PX,
            "hover" to 12.0,
            "selected" to 14.0,
            "hover plus selected" to 14.0,
        )

        visibilityByState.forEach { (state, visibleIds) ->
            val fixture = systemNameComposition(
                local = true,
                shared = true,
                ai = true,
                existingOffsetPx = existingOffsetByState.getValue(state),
            )

            assertEquals(setOf(1), visibleIds, state)
            assertEquals(
                24.0,
                fixture.systemNameLayout.topLeft.x - fixture.systemCenter.x,
                absoluteTolerance = 0.0001,
                message = state,
            )
            assertTrue(
                fixture.systemNameLayout.bounds.minX >
                    fixture.systemCenter.x + fixture.visualObstacles!!.centeredRightExtentPx!!,
                state,
            )
        }
    }

    @Test
    fun `marker-aware system name gap stays in screen space at point seven five two and eight times zoom`() {
        listOf(0.75, 2.0, 8.0).forEach { zoom ->
            val fixture = systemNameComposition(local = true, shared = true, ai = true, zoom = zoom)

            assertEquals(
                24.0,
                fixture.systemNameLayout.topLeft.x - fixture.systemCenter.x,
                absoluteTolerance = 0.0001,
                message = "zoom $zoom",
            )
            assertTrue(
                fixture.systemNameLayout.bounds.minX >= fixture.systemCenter.x + 20.0 + 4.0,
                "zoom $zoom",
            )
        }
    }

    @Test
    fun `shared only projects a deterministic segmented-ring presentation`() {
        val state = presentationState(marker())

        val presented = present(state).single()

        assertEquals(1, presented.marker.systemId)
        assertEquals(17.0, presented.ringRadiusPx)
        assertFalse(presented.hasLocalSavedMarker)
        assertFalse(presented.hasAiMissionMarker)
        assertEquals(listOf("Alliance Staging", "Shared Marker · STAGING"), presented.hoverLines)
        assertTrue(presented.badgeCenter.x > presented.screenCenter.x)
        assertTrue(presented.badgeCenter.y > presented.screenCenter.y)
        assertEquals(
            19.5,
            hypot(
                presented.badgeCenter.x - presented.screenCenter.x,
                presented.badgeCenter.y - presented.screenCenter.y,
            ),
            absoluteTolerance = 0.0001,
        )
    }

    @Test
    fun `local only and AI only do not invent Shared Marker presentation`() {
        val empty = SharedMarkerPresentationState.Empty
        val local = Marker.saved(1, MarkerDraft.create(), Instant.EPOCH, Instant.EPOCH)
        val ai = missionMarker(1)

        assertTrue(present(empty, localMarkers = mapOf(1 to local)).isEmpty())
        assertTrue(present(empty, missionMarkers = listOf(ai)).isEmpty())
    }

    @Test
    fun `system name plus Local plus Shared keeps the Local label clear of names and rings`() {
        val local = Marker.saved(1, MarkerDraft.create(name = "Local Test"), Instant.EPOCH, Instant.EPOCH)

        val presented = present(
            presentationState(marker()),
            localMarkers = mapOf(1 to local),
            localSavedRingRadiusPx = 24.0,
        ).single()

        assertTrue(presented.hasLocalSavedMarker)
        assertEquals(28.0, presented.ringRadiusPx)
        val label = localLabelFixture(local, localSavedRingRadiusPx = 24.0)
        assertTrue(label.layout.avoidedSystemName)
        assertFalse(label.layout.bounds.intersects(label.systemNameBounds))
        assertTrue(
            label.layout.bounds.minY >
                presented.screenCenter.y + presented.ringRadiusPx + geometry.secondaryOffsetPx,
        )
    }

    @Test
    fun `system name plus Local plus Shared plus AI preserves system readability and ownership markers`() {
        val sharedAndAi = present(
            presentationState(marker()),
            missionMarkers = listOf(missionMarker(1)),
        ).single()
        val local = Marker.saved(1, MarkerDraft.create(name = "Local Test"), Instant.EPOCH, Instant.EPOCH)
        val triple = present(
            presentationState(marker()),
            localMarkers = mapOf(1 to local),
            missionMarkers = listOf(missionMarker(1)),
        ).single()
        val label = localLabelFixture(local)
        val aiCenter = missionMarkerBadgeCenter(
            sharedAndAi.screenCenter,
            markerIndex = 0,
            outwardSpacingPx = 7.0,
        )
        val systemNameBounds = systemNameLabelBounds(sharedAndAi.screenCenter, MapSize(44.0, 14.0))
        val aiBadgeBounds = ScreenBounds(
            aiCenter.x - 9.0,
            aiCenter.y - 9.0,
            aiCenter.x + 9.0,
            aiCenter.y + 9.0,
        )

        assertFalse(sharedAndAi.hasLocalSavedMarker)
        assertTrue(sharedAndAi.hasAiMissionMarker)
        assertTrue(triple.hasLocalSavedMarker)
        assertTrue(triple.hasAiMissionMarker)
        assertTrue(label.layout.avoidedSystemName)
        assertFalse(label.layout.bounds.intersects(label.systemNameBounds))
        assertTrue(aiCenter.x > sharedAndAi.screenCenter.x)
        assertTrue(aiCenter.y < sharedAndAi.screenCenter.y)
        assertFalse(aiBadgeBounds.intersects(systemNameBounds))
        assertTrue(triple.badgeCenter.x > triple.screenCenter.x)
        assertTrue(triple.badgeCenter.y > triple.screenCenter.y)
        assertTrue(
            label.layout.bounds.minY >
                triple.screenCenter.y + triple.ringRadiusPx + geometry.secondaryOffsetPx,
        )
    }

    @Test
    fun `selected and hover keep AI upper-right Shared lower-right and Local label below`() {
        val local = Marker.saved(1, MarkerDraft.create(name = "Local Test"), Instant.EPOCH, Instant.EPOCH)
        val interactionVisibility = mapOf(
            "selected" to systemNameVisibilityIds(emptyList(), emptyList(), hoveredSystemId = null, selectedSystemId = 1),
            "hover" to systemNameVisibilityIds(emptyList(), emptyList(), hoveredSystemId = 1, selectedSystemId = null),
        )

        interactionVisibility.forEach { (interaction, visibleSystemNameIds) ->
            val shared = present(
                presentationState(marker()),
                localMarkers = mapOf(1 to local),
                missionMarkers = listOf(missionMarker(1)),
            ).single()
            val aiCenter = missionMarkerBadgeCenter(shared.screenCenter, 0, outwardSpacingPx = 7.0)
            val label = localLabelFixture(local, visibleSystemNameIds = visibleSystemNameIds)

            assertTrue(aiCenter.x > shared.screenCenter.x, "AI must stay right while $interaction")
            assertTrue(aiCenter.y < shared.screenCenter.y, "AI must stay above while $interaction")
            assertTrue(shared.badgeCenter.x > shared.screenCenter.x, "Shared must stay right while $interaction")
            assertTrue(shared.badgeCenter.y > shared.screenCenter.y, "Shared must stay below while $interaction")
            assertTrue(label.layout.topLeft.y > shared.screenCenter.y, "Local label must stay below while $interaction")
            assertFalse(label.layout.bounds.intersects(label.systemNameBounds))
        }
    }

    @Test
    fun `triple-marker spacing is screen-stable at point seven five two and eight times zoom`() {
        val local = Marker.saved(1, MarkerDraft.create(name = "Local Test"), Instant.EPOCH, Instant.EPOCH)

        listOf(0.75, 2.0, 8.0).forEach { zoom ->
            val zoomedTransform = MapTransform(MapViewport(MapPoint(0.0, 0.0), zoom), MapSize(800.0, 600.0))
            val shared = present(
                presentationState(marker()),
                localMarkers = mapOf(1 to local),
                missionMarkers = listOf(missionMarker(1)),
                mapTransform = zoomedTransform,
            ).single()
            val aiCenter = missionMarkerBadgeCenter(shared.screenCenter, 0, outwardSpacingPx = 7.0)
            val label = localLabelFixture(local, mapTransform = zoomedTransform)

            assertEquals(18.0, aiCenter.x - shared.screenCenter.x, absoluteTolerance = 0.0001)
            assertEquals(-18.0, aiCenter.y - shared.screenCenter.y, absoluteTolerance = 0.0001)
            assertEquals(
                19.5,
                hypot(
                    shared.badgeCenter.x - shared.screenCenter.x,
                    shared.badgeCenter.y - shared.screenCenter.y,
                ),
                absoluteTolerance = 0.0001,
            )
            assertFalse(
                label.layout.bounds.intersects(label.systemNameBounds),
                "Local label overlaps the system name at zoom $zoom",
            )
        }
    }

    @Test
    fun `hidden Shared markers retain synchronized presentation data but render nothing`() {
        val visible = presentationState(marker())
        val hidden = visible.copy(isVisible = false)

        assertEquals(1, hidden.markersBySystemId.size)
        assertTrue(present(hidden).isEmpty())
        assertEquals(1, present(visible).size)
    }

    @Test
    fun `stale authentication snapshot remains while clear and workspace replacement are atomic`() {
        val staleState = SharedMapState(
            connectionState = SharedConnectionState.AUTH_REQUIRED,
            selectedWorkspaceId = WORKSPACE_A,
            snapshot = snapshot(WORKSPACE_A, listOf(marker())),
            stale = true,
        )
        val stale = SharedMarkerPresentationAdapter.build(staleState, setOf(1, 2), isVisible = true)
        val cleared = SharedMarkerPresentationAdapter.build(
            SharedMapState(connectionState = SharedConnectionState.FORBIDDEN),
            setOf(1, 2),
            isVisible = true,
        )
        val replacement = SharedMarkerPresentationAdapter.build(
            SharedMapState(
                connectionState = SharedConnectionState.ONLINE,
                selectedWorkspaceId = WORKSPACE_B,
                snapshot = snapshot(WORKSPACE_B, listOf(marker(systemId = 2, markerId = "marker-b"))),
            ),
            setOf(1, 2),
            isVisible = true,
        )

        assertTrue(stale.isStale)
        assertEquals(setOf(1), stale.markersBySystemId.keys)
        assertTrue(cleared.markersBySystemId.isEmpty())
        assertEquals(WORKSPACE_B, replacement.workspaceId)
        assertEquals(setOf(2), replacement.markersBySystemId.keys)
    }

    @Test
    fun `unknown system is skipped without losing known markers or private fields`() {
        val unknown = marker(systemId = 999, markerId = "unknown", name = "Private Name", notes = "Private notes")
        val state = SharedMapState(snapshot = snapshot(WORKSPACE_A, listOf(marker(), unknown)))

        val presentation = SharedMarkerPresentationAdapter.build(state, setOf(1, 2), isVisible = true)

        assertEquals(setOf(1), presentation.markersBySystemId.keys)
        assertEquals(listOf(SkippedSharedMarkerPresentation("unknown", 999)), presentation.skippedUnknownSystems)
        assertNull(presentation.markersBySystemId[999])
    }

    @Test
    fun `known system omitted only by current projection is retained without a fake coordinate`() {
        val omittedSystem = system(3, "Known Remote", 35.0, 40.0).copy(schematicPosition = null)
        val official = MapSceneBuilder().build(
            StaticMapData(
                systems = listOf(system, omittedSystem),
                connections = emptyList(),
                regions = listOf(Region(100, "Delve", UniversePosition(0.0, 0.0, 0.0), null)),
                constellations = listOf(
                    Constellation(10, 100, "1-A81R", UniversePosition(0.0, 0.0, 0.0), null),
                ),
            ),
            OfficialPosition2DProjection,
        )
        val state = SharedMapState(snapshot = snapshot(WORKSPACE_A, listOf(marker(systemId = 3))))
        val presentation = SharedMarkerPresentationAdapter.build(
            state,
            official.nodesById.keys + official.omittedSystemIds,
            isVisible = true,
        )
        val officialTransform = MapTransform(
            MapViewport(official.defaultFitBounds.center, 1.0),
            MapSize(800.0, 600.0),
        )

        assertEquals(setOf(3), presentation.markersBySystemId.keys)
        assertTrue(presentation.skippedUnknownSystems.isEmpty())
        assertTrue(
            SharedMarkerMapPresentationBuilder.build(
                official,
                officialTransform,
                visibleSystemIds = listOf(3),
                state = presentation,
                localMarkersBySystemId = emptyMap(),
                missionMarkers = emptyList(),
                geometry = geometry,
                localSavedRingRadiusPx = 13.0,
            ).isEmpty(),
        )
    }

    @Test
    fun `five hundred markers use one indexed known-system lookup per marker`() {
        val markers = (1..500).map { index -> marker(systemId = index, markerId = "marker-$index") }
        val knownSystems = CountingSet((1..500).toSet())

        val presentation = SharedMarkerPresentationAdapter.build(
            SharedMapState(snapshot = snapshot(WORKSPACE_A, markers)),
            knownSystems,
            isVisible = true,
        )

        assertEquals(500, presentation.markersBySystemId.size)
        assertEquals(500, knownSystems.containsCalls)
    }

    private fun present(
        state: SharedMarkerPresentationState,
        localMarkers: Map<Int, Marker> = emptyMap(),
        missionMarkers: List<MissionMarker> = emptyList(),
        localSavedRingRadiusPx: Double = 13.0,
        mapTransform: MapTransform = transform,
    ) = SharedMarkerMapPresentationBuilder.build(
        scene = scene,
        transform = mapTransform,
        visibleSystemIds = listOf(1, 2),
        state = state,
        localMarkersBySystemId = localMarkers,
        missionMarkers = missionMarkers,
        geometry = geometry,
        localSavedRingRadiusPx = localSavedRingRadiusPx,
    )

    private fun presentationState(marker: SharedMarker) = SharedMarkerPresentationAdapter.build(
        SharedMapState(snapshot = snapshot(WORKSPACE_A, listOf(marker))),
        setOf(1, 2),
        isVisible = true,
    )

    private fun localLabelFixture(
        local: Marker,
        localSavedRingRadiusPx: Double = 13.0,
        mapTransform: MapTransform = transform,
        visibleSystemNameIds: Set<Int> = setOf(1),
        systemLabelBounds: ScreenBounds? = null,
    ): LocalLabelFixture {
        val presented = MarkerMapPresentationBuilder.build(
            scene = scene,
            transform = mapTransform,
            visibleSystemIds = listOf(1),
            markersBySystemId = mapOf(1 to local),
            preferences = MarkerPreferences.Defaults,
            semanticMode = SemanticLabelMode.SYSTEM,
            offsetPx = 10.0,
            systemNameVisibleIds = visibleSystemNameIds,
        ).single()
        val systemLabelSize = MapSize(44.0, 14.0)
        val actualSystemLabelBounds = systemLabelBounds
            ?: systemNameLabelBounds(presented.screenCenter, systemLabelSize)
        return LocalLabelFixture(
            layout = markerNameLabelLayout(
                marker = presented,
                markerLabelSize = MapSize(62.0, 14.0),
                systemLabelBounds = actualSystemLabelBounds,
                markerRadiusPx = localSavedRingRadiusPx,
                rightGapPx = 4.0,
                belowRingGapPx = 10.0,
                collisionPaddingPx = 2.0,
            ),
            systemNameBounds = actualSystemLabelBounds,
        )
    }

    private fun systemNameComposition(
        local: Boolean,
        shared: Boolean,
        ai: Boolean,
        zoom: Double = 2.0,
        existingOffsetPx: Double = SYSTEM_LABEL_OFFSET_PX,
    ): SystemNameCompositionFixture {
        val mapTransform = MapTransform(MapViewport(MapPoint(0.0, 0.0), zoom), MapSize(800.0, 600.0))
        val localMarker = Marker.saved(
            1,
            MarkerDraft.create(name = "Local Test"),
            Instant.EPOCH,
            Instant.EPOCH,
        ).takeIf { local }
        val localMarkers = localMarker?.let { mapOf(1 to it) }.orEmpty()
        val missionMarkers = listOfNotNull(missionMarker(1).takeIf { ai })
        val presentedLocalMarkers = MarkerMapPresentationBuilder.build(
            scene = scene,
            transform = mapTransform,
            visibleSystemIds = listOf(1),
            markersBySystemId = localMarkers,
            preferences = MarkerPreferences.Defaults,
            semanticMode = SemanticLabelMode.SYSTEM,
            offsetPx = 10.0,
            systemNameVisibleIds = setOf(1),
        )
        val presentedSharedMarkers = if (shared) {
            present(
                state = presentationState(marker()),
                localMarkers = localMarkers,
                missionMarkers = missionMarkers,
                mapTransform = mapTransform,
            )
        } else {
            emptyList()
        }
        val visualObstacles = systemNameVisualObstaclesBySystemId(
            localMarkers = presentedLocalMarkers,
            sharedMarkers = presentedSharedMarkers,
            sharedGeometry = geometry,
            localSavedVisualRadiusPx = savedMarkerRingRenderState(
                SavedMarkerAppearancePreferences.Defaults,
            ).visualRadiusDp().toDouble(),
        )[1]
        val center = mapTransform.worldToScreen(scene.nodesById.getValue(1).position)
        val layout = systemNameLabelLayout(
            center = center,
            labelSize = MapSize(44.0, 14.0),
            existingOffsetPx = existingOffsetPx,
            visualObstacles = visualObstacles,
            safetyGapPx = 4.0,
        )
        return SystemNameCompositionFixture(
            systemCenter = center,
            systemNameLayout = layout,
            localMarker = localMarker,
            sharedMarker = presentedSharedMarkers.singleOrNull(),
            aiCenter = missionMarkers.singleOrNull()?.let {
                missionMarkerBadgeCenter(center, markerIndex = 0, outwardSpacingPx = 7.0)
            },
            visualObstacles = visualObstacles,
        )
    }

    private fun snapshot(workspaceId: String, markers: List<SharedMarker>) = SharedMarkerSnapshot(
        workspaceId = workspaceId,
        revision = 7,
        generatedAt = Instant.parse("2026-09-02T02:00:00Z"),
        markers = markers.associateBy(SharedMarker::markerId),
    )

    private fun marker(
        systemId: Int = 1,
        markerId: String = "marker-a",
        name: String = "Alliance Staging",
        notes: String? = "Form here",
    ): SharedMarker {
        val user = SharedUser("user-a", "Alice")
        return SharedMarker(
            markerId = markerId,
            workspaceId = WORKSPACE_A,
            systemId = systemId,
            name = name,
            color = SharedMarkerColor.BLUE,
            tags = listOf("staging", "strategic"),
            notes = notes,
            createdBy = user,
            updatedBy = user,
            createdAt = Instant.parse("2026-09-01T02:00:00Z"),
            updatedAt = Instant.parse("2026-09-02T02:00:00Z"),
            version = 3,
        )
    }

    private fun missionMarker(systemId: Int) = MissionMarker(
        missionId = MissionId("mission"),
        markerId = MissionMarkerId("ai-$systemId"),
        systemId = systemId,
        role = MissionMarkerRole.INFO,
        label = null,
        notes = null,
        colorOverride = null,
    )

    private fun system(id: Int, name: String, x: Double, y: Double) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 100,
        name = name,
        securityStatus = -0.3,
        securityClass = null,
        position = UniversePosition(x, 0.0, y),
        schematicPosition = SchematicPosition(x, y),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private class CountingSet(private val delegate: Set<Int>) : Set<Int> by delegate {
        var containsCalls: Int = 0
            private set

        override fun contains(element: Int): Boolean {
            containsCalls += 1
            return delegate.contains(element)
        }
    }

    private data class LocalLabelFixture(
        val layout: MarkerNameLabelLayout,
        val systemNameBounds: ScreenBounds,
    )

    private data class CompositionCase(
        val name: String,
        val local: Boolean,
        val shared: Boolean,
        val ai: Boolean,
        val expectedOffsetPx: Double,
    )

    private data class SystemNameCompositionFixture(
        val systemCenter: MapPoint,
        val systemNameLayout: SystemNameLabelLayout,
        val localMarker: Marker?,
        val sharedMarker: PresentedSharedMarker?,
        val aiCenter: MapPoint?,
        val visualObstacles: SystemNameVisualObstacles?,
    )

    private companion object {
        const val WORKSPACE_A = "01991d60-b8a2-7a20-a311-b5114b27c219"
        const val WORKSPACE_B = "01991d60-b8a2-7a20-a311-b5114b27c220"
    }
}
