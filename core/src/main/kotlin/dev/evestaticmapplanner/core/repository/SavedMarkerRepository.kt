package dev.evestaticmapplanner.core.repository

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType

interface SavedMarkerRepository {
    fun getAll(): List<Marker>

    fun create(systemId: Int, draft: MarkerDraft): Marker

    fun update(systemId: Int, draft: MarkerDraft): Marker

    fun delete(systemId: Int): Boolean

    fun getChildren(parentSystemId: Int): List<SavedMarkerChild>

    fun getAllChildren(): Map<Int, List<SavedMarkerChild>>

    fun addChild(parentSystemId: Int, type: SavedMarkerChildType): SavedMarkerChild

    fun removeChild(parentSystemId: Int, childId: String): Boolean
}
