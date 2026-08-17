package dev.evestaticmapplanner.core.jump

import dev.evestaticmapplanner.core.model.SolarSystem

sealed interface EligibilityVerdict {
    data object Eligible : EligibilityVerdict
    data class Ineligible(val reason: String) : EligibilityVerdict
    data class Unknown(val reason: String) : EligibilityVerdict
}

class JumpEligibilityPolicy(
    private val classifier: SpaceClassificationClassifier = SpaceClassificationClassifier(),
) {
    fun evaluateOrigin(system: SolarSystem): EligibilityVerdict {
        if (system.id == SpaceClassificationClassifier.ZARZAKH_SYSTEM_ID) {
            return EligibilityVerdict.Ineligible("Jump drives, conduit jumps, and jump bridges cannot be used from Zarzakh")
        }
        return when (classifier.classify(system)) {
            SpaceClassification.NEW_EDEN -> EligibilityVerdict.Eligible
            SpaceClassification.POCHVEN -> EligibilityVerdict.Ineligible("Pochven is excluded by the static jump-drive rules")
            SpaceClassification.JOVE -> EligibilityVerdict.Ineligible("Jove space is excluded by the static jump-drive rules")
            SpaceClassification.WORMHOLE -> EligibilityVerdict.Ineligible("Wormhole space is excluded by the static jump-drive rules")
            SpaceClassification.ABYSSAL -> EligibilityVerdict.Ineligible("Abyssal space is excluded by the static jump-drive rules")
            SpaceClassification.SPECIAL -> EligibilityVerdict.Unknown("No reliable static origin rule is available for this special system")
        }
    }

    fun evaluateDestination(system: SolarSystem): EligibilityVerdict {
        if (system.id == SpaceClassificationClassifier.ZARZAKH_SYSTEM_ID) {
            return EligibilityVerdict.Unknown("No reliable official static rule confirms Zarzakh as a jump-drive destination")
        }
        return when (classifier.classify(system)) {
            SpaceClassification.NEW_EDEN -> if (system.securityStatus < HIGH_SECURITY_THRESHOLD) {
                EligibilityVerdict.Eligible
            } else {
                EligibilityVerdict.Ineligible("Jump-drive destination is high-security space")
            }
            SpaceClassification.POCHVEN -> EligibilityVerdict.Ineligible("Pochven is excluded by the static jump-drive rules")
            SpaceClassification.JOVE -> EligibilityVerdict.Ineligible("Jove space is excluded by the static jump-drive rules")
            SpaceClassification.WORMHOLE -> EligibilityVerdict.Ineligible("Wormhole space is excluded by the static jump-drive rules")
            SpaceClassification.ABYSSAL -> EligibilityVerdict.Ineligible("Abyssal space is excluded by the static jump-drive rules")
            SpaceClassification.SPECIAL -> EligibilityVerdict.Unknown("No reliable static destination rule is available for this special system")
        }
    }

    companion object {
        const val HIGH_SECURITY_THRESHOLD: Double = 0.45
    }
}
