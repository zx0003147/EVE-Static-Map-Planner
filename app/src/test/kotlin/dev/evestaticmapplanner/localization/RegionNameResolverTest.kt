package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.core.map.MapPoint3
import dev.evestaticmapplanner.core.map.Real3DHierarchyAnchor
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.map.real3DRegionLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class RegionNameResolverTest {
    @Test
    fun `English is canonical and Chinese uses official value`() {
        val region = Region(10000060, "Delve", UniversePosition(1.0, 2.0, 3.0), null, "绝地之域")

        assertEquals("Delve", region.name)
        assertEquals("Delve", RegionNameResolver.resolve(region, AppLocale.EN_US))
        assertEquals("绝地之域", RegionNameResolver.resolve(region, AppLocale.ZH_CN))
    }

    @Test
    fun `missing blank and whitespace Chinese names fall back to canonical English`() {
        listOf(null, "", "   ").forEach { zh ->
            assertEquals("Delve", RegionNameResolver.resolve("Delve", zh, AppLocale.ZH_CN))
        }
    }

    @Test
    fun `projected 3D region label changes without changing anchor geometry`() {
        val position = MapPoint3(4.0, 5.0, 6.0)
        val anchor = Real3DHierarchyAnchor(10000060, "Delve", position, 20, "绝地之域")

        assertEquals("Delve", real3DRegionLabel(anchor, AppLocale.EN_US))
        assertEquals("绝地之域", real3DRegionLabel(anchor, AppLocale.ZH_CN))
        assertEquals(position, anchor.position)
        assertEquals(10000060, anchor.id)
        assertEquals(20, anchor.memberCount)
    }
}
