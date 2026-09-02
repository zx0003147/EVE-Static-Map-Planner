package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedDevice
import dev.evestaticmapplanner.shared.model.SharedIdentity
import dev.evestaticmapplanner.shared.model.SharedMapState
import dev.evestaticmapplanner.shared.model.SharedMarker
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedMarkerSnapshot
import dev.evestaticmapplanner.shared.model.SharedUser
import dev.evestaticmapplanner.shared.model.SharedWorkspace
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMarkerManagerPresentationTest {
    @Test
    fun `empty one and five hundred marker snapshots remain stable`() {
        assertTrue(build(sharedState(markers = emptyList())).rows.isEmpty())
        assertEquals(1, build(sharedState(markers = listOf(sharedMarker(0)))).rows.size)

        val rows = build(sharedState(markers = List(500, ::sharedMarker))).rows
        assertEquals(500, rows.size)
        assertEquals(rows.sortedWith(compareBy({ it.systemName.lowercase() }, { it.systemId }, { it.markerId })), rows)
    }

    @Test
    fun `search matches system marker and tag but not notes`() {
        val marker = sharedMarker(1).copy(name = "Enemy Staging", tags = listOf("danger"), notes = "secretneedle")
        val state = sharedState(markers = listOf(marker))
        val names = mapOf(marker.systemId to "NOL-M9")
        assertEquals(1, build(state, names, "nol").rows.size)
        assertEquals(1, build(state, names, "enemy").rows.size)
        assertEquals(1, build(state, names, "danger").rows.size)
        assertTrue(build(state, names, "secretneedle").rows.isEmpty())
    }

    @Test
    fun `sort modes and remote deletion update from the current snapshot`() {
        val old = sharedMarker(1).copy(name = "Zulu", updatedAt = Instant.parse("2026-09-01T00:00:00Z"))
        val recent = sharedMarker(2).copy(name = "Alpha", updatedAt = Instant.parse("2026-09-02T00:00:00Z"))
        val state = sharedState(markers = listOf(old, recent))
        assertEquals(recent.markerId, build(state, sort = SharedMarkerManagerSort.UPDATED).rows.first().markerId)
        assertEquals(recent.markerId, build(state, sort = SharedMarkerManagerSort.NAME).rows.first().markerId)

        val selected = build(state, selectedMarkerId = old.markerId)
        assertEquals(old.markerId, selected.selected?.markerId)
        val remotelyDeleted = build(sharedState(markers = listOf(recent)), selectedMarkerId = old.markerId)
        assertNull(remotelyDeleted.selected)
    }

    @Test
    fun `viewer and stale editor are read only while online admin can write`() {
        assertFalse(build(sharedState(SharedWorkspaceRole.VIEWER)).canWrite)
        assertFalse(build(sharedState(SharedWorkspaceRole.EDITOR, SharedConnectionState.DEGRADED)).canWrite)
        assertTrue(build(sharedState(SharedWorkspaceRole.ADMIN)).canWrite)
    }

    private fun build(
        state: SharedMapState,
        names: Map<Int, String> = state.snapshot?.markers?.values.orEmpty().associate { it.systemId to "System ${it.systemId}" },
        query: String = "",
        sort: SharedMarkerManagerSort = SharedMarkerManagerSort.SYSTEM,
        selectedMarkerId: String? = null,
    ) = SharedMarkerManagerPresentationBuilder.build(state, names, query, sort, selectedMarkerId)
}

internal fun sharedState(
    role: SharedWorkspaceRole = SharedWorkspaceRole.EDITOR,
    connection: SharedConnectionState = SharedConnectionState.ONLINE,
    markers: List<SharedMarker> = listOf(sharedMarker(0)),
): SharedMapState {
    val workspace = SharedWorkspace(WORKSPACE_ID, "Ops", role, 7, MEMBER_ID)
    return SharedMapState(
        connectionState = connection,
        identity = SharedIdentity(
            SharedUser(USER_ID, "Pilot"),
            workspace,
            SharedDevice(TOKEN_ID, "Laptop", NOW, null, Instant.parse("2026-12-01T00:00:00Z")),
        ),
        workspaces = listOf(workspace),
        selectedWorkspaceId = WORKSPACE_ID,
        snapshot = SharedMarkerSnapshot(WORKSPACE_ID, 7, NOW, markers.associateBy(SharedMarker::markerId)),
        stale = connection == SharedConnectionState.DEGRADED,
    )
}

internal fun sharedMarker(index: Int): SharedMarker {
    val actor = SharedUser(USER_ID, "Pilot")
    return SharedMarker(
        markerId = UUID.nameUUIDFromBytes("marker-$index".toByteArray()).toString(),
        workspaceId = WORKSPACE_ID,
        systemId = 30_000_001 + index,
        name = "Marker $index",
        color = SharedMarkerColor.BLUE,
        tags = listOf("tag$index"),
        notes = "private",
        createdBy = actor,
        updatedBy = actor,
        createdAt = NOW,
        updatedAt = NOW.plusSeconds(index.toLong()),
        version = 1,
    )
}

private const val WORKSPACE_ID = "01991d60-b8a2-7a20-a311-b5114b27c219"
private const val MEMBER_ID = "01991d62-1fcb-70d0-858b-1d65f6ce3cf6"
private const val TOKEN_ID = "01991d6a-74ce-7ef5-8735-4e15444fc980"
private const val USER_ID = "01991d61-745e-7b08-a716-93c039cde2e2"
private val NOW = Instant.parse("2026-09-01T00:00:00Z")
