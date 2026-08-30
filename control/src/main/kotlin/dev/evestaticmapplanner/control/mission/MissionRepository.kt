package dev.evestaticmapplanner.control.mission

interface MissionRepository {
    fun load(): List<Mission>
    fun save(missions: List<Mission>)
}

object InMemoryOnlyMissionRepository : MissionRepository {
    override fun load(): List<Mission> = emptyList()
    override fun save(missions: List<Mission>) = Unit
}
