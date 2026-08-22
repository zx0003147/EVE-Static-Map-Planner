package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityContractTest {
    @Test
    fun `service exposes only explicit capability whitelist`() {
        val operations = MapControlService::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(
            setOf(
                "searchSystems", "getSystemInfo", "calculateNormalRoute", "calculateCapitalRoute",
                "getActiveMissions", "getMission", "beginMission", "focusSystem", "showNormalRoute",
                "showCapitalRoute", "removeMissionRoute", "clearMissionRoutes", "showJumpRange",
                "removeJumpRange", "clearMissionJumpRanges", "addMissionMarker", "removeMissionMarker",
                "clearMissionMarkers", "fitMission", "clearMission",
            ),
            operations,
        )
        assertFalse(operations.any { it.equals("execute", true) || it.equals("invoke", true) })
    }

    @Test
    fun `public contract cannot name database view model saved marker or Ansiblex mutation types`() {
        val exposedTypeNames = MapControlService::class.java.declaredMethods.flatMap { method ->
            listOf(method.returnType.name) + method.parameterTypes.map(Class<*>::getName)
        }.joinToString(" ")
        listOf("java.sql", ".data.", "ViewModel", "SavedMarkerRepository", "AnsiblexRepository").forEach {
            assertFalse(exposedTypeNames.contains(it), "Forbidden public type was exposed: $it")
        }
        val commandNames = listOf(
            BeginMissionCommand::class,
            FocusSystemCommand::class,
            ShowNormalRouteCommand::class,
            ShowCapitalRouteCommand::class,
            ShowJumpRangeCommand::class,
            AddMissionMarkerCommand::class,
        ).mapNotNull { it.simpleName }
        assertTrue(commandNames.none { it.contains("Saved", true) || it.contains("AnsiblexMutation", true) })
        assertEquals(6, MissionMarkerRole.entries.size)
    }
}
