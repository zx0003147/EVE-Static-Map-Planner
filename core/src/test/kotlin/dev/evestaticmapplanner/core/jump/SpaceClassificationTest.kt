package dev.evestaticmapplanner.core.jump

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SpaceClassificationTest {
    private val classifier = SpaceClassificationClassifier()
    private val policy = JumpEligibilityPolicy(classifier)

    @Test
    fun `raw SDE classes map centrally to semantic space classifications`() {
        assertEquals(SpaceClassification.NEW_EDEN, classifier.classify(jumpTestSystem(30_000_001, effectiveClassId = 7)))
        assertEquals(SpaceClassification.JOVE, classifier.classify(jumpTestSystem(30_000_002, effectiveClassId = 10)))
        assertEquals(SpaceClassification.JOVE, classifier.classify(jumpTestSystem(30_000_003, effectiveClassId = 11)))
        assertEquals(SpaceClassification.POCHVEN, classifier.classify(jumpTestSystem(30_000_004, effectiveClassId = 25)))
        assertEquals(SpaceClassification.WORMHOLE, classifier.classify(jumpTestSystem(31_000_001, effectiveClassId = 6)))
        assertEquals(SpaceClassification.ABYSSAL, classifier.classify(jumpTestSystem(32_000_001, effectiveClassId = 19)))
        assertEquals(SpaceClassification.SPECIAL, classifier.classify(jumpTestSystem(33_000_001)))
    }

    @Test
    fun `Zarzakh origin is ineligible and destination remains unknown`() {
        val zarzakh = jumpTestSystem(SpaceClassificationClassifier.ZARZAKH_SYSTEM_ID)

        assertEquals(SpaceClassification.SPECIAL, classifier.classify(zarzakh))
        assertIs<EligibilityVerdict.Ineligible>(policy.evaluateOrigin(zarzakh))
        assertIs<EligibilityVerdict.Unknown>(policy.evaluateDestination(zarzakh))
    }

    @Test
    fun `unknown destination is safely excluded by candidate provider`() {
        val origin = jumpTestSystem(30_000_001)
        val special = jumpTestSystem(33_000_001, xLy = 1.0)
        val provider = CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(listOf(origin, special)))

        assertEquals(emptySet(), provider.reachableFrom(origin.id, JumpProfile.manual(5.0)).reachableSystemIds)
    }
}
