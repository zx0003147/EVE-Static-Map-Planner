package dev.evestaticmapplanner.sde

import dev.evestaticmapplanner.data.db.SourceFileAudit
import dev.evestaticmapplanner.sde.io.JsonlReader
import dev.evestaticmapplanner.sde.io.SdeSourceFiles
import dev.evestaticmapplanner.sde.model.SdeConstellationRecord
import dev.evestaticmapplanner.sde.model.SdeRegionRecord
import dev.evestaticmapplanner.sde.model.SdeSolarSystemRecord
import dev.evestaticmapplanner.sde.model.SdeStargateRecord

data class SdeDataSet(
    val regions: Map<Long, SdeRegionRecord>,
    val constellations: Map<Long, SdeConstellationRecord>,
    val solarSystems: Map<Long, SdeSolarSystemRecord>,
    val stargates: Map<Long, SdeStargateRecord>,
    val sourceFiles: List<SourceFileAudit>,
) {
    companion object {
        fun load(files: SdeSourceFiles, reader: JsonlReader = JsonlReader()): SdeDataSet {
            val regions = reader.read<SdeRegionRecord>(files.regions)
            val constellations = reader.read<SdeConstellationRecord>(files.constellations)
            val systems = reader.read<SdeSolarSystemRecord>(files.solarSystems)
            val stargates = reader.read<SdeStargateRecord>(files.stargates)
            return SdeDataSet(
                regions = regions.records.associateUniqueBy("region", SdeRegionRecord::id),
                constellations = constellations.records.associateUniqueBy(
                    "constellation",
                    SdeConstellationRecord::id,
                ),
                solarSystems = systems.records.associateUniqueBy("solar system", SdeSolarSystemRecord::id),
                stargates = stargates.records.associateUniqueBy("stargate", SdeStargateRecord::id),
                sourceFiles = listOf(regions.audit, constellations.audit, systems.audit, stargates.audit),
            )
        }
    }
}

private fun <T> List<T>.associateUniqueBy(kind: String, id: (T) -> Long): Map<Long, T> {
    val result = LinkedHashMap<Long, T>(size)
    for (record in this) {
        val key = id(record)
        require(result.put(key, record) == null) { "Duplicate $kind ID: $key" }
    }
    return result
}
