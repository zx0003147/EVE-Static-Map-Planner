package dev.evestaticmapplanner.core.repository

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft

interface AnsiblexRepository {
    fun getAll(): List<AnsiblexConnection>

    fun addManual(draft: AnsiblexDraft): AnsiblexConnection

    fun setEnabled(id: String, enabled: Boolean): Boolean

    fun delete(id: String): Boolean

    fun clearImported(): Int

    fun clearAll(): Int
}
