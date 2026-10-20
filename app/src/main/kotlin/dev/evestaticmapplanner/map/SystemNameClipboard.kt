package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.ProjectedMapScene

internal fun copyContextSystemName(
    scene: ProjectedMapScene,
    contextSystemId: Int,
    setClipboardText: (String) -> Unit,
): Boolean {
    val systemName = scene.nodesById[contextSystemId]?.system?.name ?: return false
    setClipboardText(systemName)
    return true
}
