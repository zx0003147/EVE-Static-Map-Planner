package dev.evestaticmapplanner.shared

import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedMarkerContextPresentationTest {
    @Test
    fun `online editor can add while viewer has no create action`() {
        val editor = SharedMarkerContextPresentationBuilder.build(null, sharedState(SharedWorkspaceRole.EDITOR))
        assertEquals(SharedMarkerContextAction.ADD, editor.single().action)
        assertTrue(editor.single().enabled)

        assertTrue(SharedMarkerContextPresentationBuilder.build(null, sharedState(SharedWorkspaceRole.VIEWER)).isEmpty())
    }

    @Test
    fun `degraded editor sees read only create state before clicking`() {
        val action = SharedMarkerContextPresentationBuilder.build(
            null,
            sharedState(SharedWorkspaceRole.EDITOR, SharedConnectionState.DEGRADED),
        ).single()
        assertFalse(action.enabled)
        assertTrue(action.label.contains("read-only"))
    }

    @Test
    fun `existing marker becomes edit for online editor and view for viewer`() {
        val marker = sharedMarker(0)
        val editor = SharedMarkerContextPresentationBuilder.build(marker, sharedState(SharedWorkspaceRole.ADMIN)).single()
        val viewer = SharedMarkerContextPresentationBuilder.build(marker, sharedState(SharedWorkspaceRole.VIEWER)).single()
        assertEquals("Shared Marker…", editor.label)
        assertEquals("View Shared Marker…", viewer.label)
        assertTrue(viewer.enabled)
    }
}
