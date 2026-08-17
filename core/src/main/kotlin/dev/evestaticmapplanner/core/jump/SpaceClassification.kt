package dev.evestaticmapplanner.core.jump

import dev.evestaticmapplanner.core.model.SolarSystem

enum class SpaceClassification {
    NEW_EDEN,
    POCHVEN,
    JOVE,
    WORMHOLE,
    ABYSSAL,
    SPECIAL,
}

class SpaceClassificationClassifier {
    fun classify(system: SolarSystem): SpaceClassification = when {
        system.id == ZARZAKH_SYSTEM_ID -> SpaceClassification.SPECIAL
        system.effectiveWormholeClassId in POCHVEN_CLASS_IDS -> SpaceClassification.POCHVEN
        system.effectiveWormholeClassId in JOVE_CLASS_IDS -> SpaceClassification.JOVE
        system.effectiveWormholeClassId in WORMHOLE_CLASS_IDS -> SpaceClassification.WORMHOLE
        system.effectiveWormholeClassId in ABYSSAL_CLASS_IDS -> SpaceClassification.ABYSSAL
        system.effectiveWormholeClassId in NEW_EDEN_CLASS_IDS -> SpaceClassification.NEW_EDEN
        system.effectiveWormholeClassId == null && system.id in NEW_EDEN_SYSTEM_ID_RANGE ->
            SpaceClassification.NEW_EDEN
        else -> SpaceClassification.SPECIAL
    }

    companion object {
        const val ZARZAKH_SYSTEM_ID: Int = 30_100_000

        private val NEW_EDEN_SYSTEM_ID_RANGE = 30_000_000..30_999_999
        private val NEW_EDEN_CLASS_IDS = setOf(7, 8, 9)
        private val JOVE_CLASS_IDS = setOf(10, 11)
        private val WORMHOLE_CLASS_IDS = (1..6).toSet() + (12..18).toSet()
        private val ABYSSAL_CLASS_IDS = (19..23).toSet()
        private val POCHVEN_CLASS_IDS = setOf(25)
    }
}
