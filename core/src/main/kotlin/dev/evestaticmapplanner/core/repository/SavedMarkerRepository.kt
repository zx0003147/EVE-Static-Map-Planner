package dev.evestaticmapplanner.core.repository

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft

interface SavedMarkerRepository {
    fun getAll(): List<Marker>

    fun create(systemId: Int, draft: MarkerDraft): Marker

    fun update(systemId: Int, draft: MarkerDraft): Marker

    fun delete(systemId: Int): Boolean
}
