package dev.evestaticmapplanner.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureOverlayRingRenderStateTest {
    @Test
    fun `feature rings remain visible at overview and normal browsing zoom`() {
        val overview = featureOverlayRingRenderState(MapDetailLevel.OVERVIEW)
        val normal = featureOverlayRingRenderState(MapDetailLevel.NORMAL)
        val detail = featureOverlayRingRenderState(MapDetailLevel.DETAIL)

        assertTrue(overview.baseRadiusPx >= 6.5f)
        assertTrue(normal.baseRadiusPx >= overview.baseRadiusPx)
        assertTrue(detail.baseRadiusPx >= normal.baseRadiusPx)
        assertTrue(detail.baseRadiusPx - overview.baseRadiusPx <= 1f)
        assertTrue(overview.strokeWidthPx > detail.strokeWidthPx)
        assertEquals(3.4f, overview.spacingPx)
        assertEquals(overview.spacingPx, normal.spacingPx)
        assertEquals(normal.spacingPx, detail.spacingPx)
    }
}
