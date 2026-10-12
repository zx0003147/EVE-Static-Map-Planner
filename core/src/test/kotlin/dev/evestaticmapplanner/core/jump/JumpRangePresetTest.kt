package dev.evestaticmapplanner.core.jump

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JumpRangePresetTest {
    @Test
    fun `presets expose canonical jump distances`() {
        assertEquals(7.0, JumpRangePreset.DREADNOUGHT_CARRIER_FAX.defaultRangeLy)
        assertEquals(7.5, JumpRangePreset.COMMAND_CARRIER.defaultRangeLy)
        assertEquals(8.0, JumpRangePreset.LANCER_DREAD.defaultRangeLy)
        assertEquals(6.0, JumpRangePreset.TITAN_SUPERCARRIER.defaultRangeLy)
        assertEquals(8.0, JumpRangePreset.BLACK_OPS.defaultRangeLy)
        assertEquals(10.0, JumpRangePreset.RORQUAL.defaultRangeLy)
        assertNull(JumpRangePreset.CUSTOM.defaultRangeLy)
    }

    @Test
    fun `presets expose the jump range dropdown order`() {
        assertEquals(
            listOf(
                JumpRangePreset.DREADNOUGHT_CARRIER_FAX,
                JumpRangePreset.COMMAND_CARRIER,
                JumpRangePreset.LANCER_DREAD,
                JumpRangePreset.TITAN_SUPERCARRIER,
                JumpRangePreset.BLACK_OPS,
                JumpRangePreset.RORQUAL,
                JumpRangePreset.CUSTOM,
            ),
            JumpRangePreset.entries,
        )
    }
}
