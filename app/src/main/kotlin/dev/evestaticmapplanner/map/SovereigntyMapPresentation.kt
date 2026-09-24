package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.core.sovereignty.SovereigntySnapshot
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind
import dev.evestaticmapplanner.core.sovereignty.SystemOwnership
import kotlin.math.abs
import kotlin.math.roundToInt

/** Planner-owned visual projection of Core sovereignty. No Pack presentation payload crosses this boundary. */
internal data class SovereigntyMapEntry(
    val systemId: Int,
    val ownerKey: String,
    val ownerLabel: String,
    val color: Color,
    val emblemReference: PresentationEmblemReference?,
)

internal data class SovereigntyMapPresentation(
    val entries: List<SovereigntyMapEntry>,
    val freshness: SovereigntyFreshness,
) {
    companion object {
        val Empty = SovereigntyMapPresentation(emptyList(), SovereigntyFreshness.UNAVAILABLE)
    }
}

internal object SovereigntyMapPresentationBuilder {
    fun build(snapshot: SovereigntySnapshot, enabled: Boolean = true): SovereigntyMapPresentation {
        if (!enabled || snapshot.freshness == SovereigntyFreshness.UNAVAILABLE) {
            return SovereigntyMapPresentation(emptyList(), snapshot.freshness)
        }
        return SovereigntyMapPresentation(
            entries = snapshot.systemsById.values
                .sortedBy(SystemOwnership::systemId)
                .map(::toMapEntry),
            freshness = snapshot.freshness,
        )
    }

    private fun toMapEntry(ownership: SystemOwnership): SovereigntyMapEntry {
        val ownerKey = ownership.stablePresentationOwnerKey()
        return SovereigntyMapEntry(
            systemId = ownership.systemId,
            ownerKey = ownerKey,
            ownerLabel = ownership.ownerLabel(),
            color = SovereigntyVisualIdentity.colorFor(ownership, ownerKey),
            emblemReference = ownership.allianceId?.takeIf { ownership.ownerKind == SystemOwnerKind.ALLIANCE }?.let { allianceId ->
                PresentationEmblemReference(
                    key = "eve-alliance:$allianceId",
                    url = "https://images.evetech.net/alliances/$allianceId/logo?size=256",
                )
            },
        )
    }
}

private fun SystemOwnership.stablePresentationOwnerKey(): String = when (ownerKind) {
    SystemOwnerKind.ALLIANCE -> "alliance:$allianceId"
    SystemOwnerKind.CORPORATION -> "corporation:$corporationId"
    SystemOwnerKind.FACTION -> "faction:$factionId"
    SystemOwnerKind.UNCLAIMED -> "unclaimed"
    SystemOwnerKind.UNKNOWN -> "unknown-system:$systemId"
}

private fun SystemOwnership.ownerLabel(): String = when (ownerKind) {
    SystemOwnerKind.ALLIANCE -> allianceName ?: "Alliance $allianceId"
    SystemOwnerKind.CORPORATION -> corporationName ?: "Corporation $corporationId"
    SystemOwnerKind.FACTION -> factionName ?: "Faction $factionId"
    SystemOwnerKind.UNCLAIMED -> "Unclaimed"
    SystemOwnerKind.UNKNOWN -> "Unknown"
}

/** Stable presentation metadata belongs to Planner, while Core retains only identity and ownership semantics. */
private object SovereigntyVisualIdentity {
    private val allianceColorOverrides = mapOf(
        1_354_830_081L to Color(0xCCF2C94C),
        99_003_581L to Color(0xCC4D9DE0),
    )
    private val unknownColor = Color(0xCC8EA8BD)

    fun colorFor(ownership: SystemOwnership, ownerKey: String): Color {
        if (ownership.ownerKind == SystemOwnerKind.UNKNOWN || ownership.ownerKind == SystemOwnerKind.UNCLAIMED) {
            return unknownColor
        }
        ownership.allianceId?.let { allianceColorOverrides[it]?.let { color -> return color } }
        val seed = ownership.allianceId?.let(::mixedAllianceId) ?: stableHash(ownerKey)
        return generatedColor(seed)
    }

    // Preserve the former Pack presentation algorithm for Alliance IDs so migration does not recolor territory.
    private fun mixedAllianceId(allianceId: Long): UInt {
        var value = allianceId.toUInt()
        value = (value xor (value shr 16)) * 0x7FEB352Du
        value = (value xor (value shr 15)) * 0x846CA68Bu
        return value xor (value shr 16)
    }

    private fun stableHash(value: String): UInt {
        var hash = FNV_OFFSET_BASIS
        value.forEach { character -> hash = (hash xor character.code.toUInt()) * FNV_PRIME }
        hash = (hash xor (hash shr 16)) * 0x7FEB352Du
        hash = (hash xor (hash shr 15)) * 0x846CA68Bu
        return hash xor (hash shr 16)
    }

    private fun generatedColor(seed: UInt): Color {
        val hue = (seed % 36_000u).toDouble() / 100.0
        val saturation = 0.68 + ((seed shr 16) % 11u).toDouble() / 100.0
        val lightness = 0.60 + ((seed shr 24) % 7u).toDouble() / 100.0
        return hslColor(hue, saturation.coerceAtMost(0.78), lightness.coerceAtMost(0.66))
    }

    private fun hslColor(hueDegrees: Double, saturation: Double, lightness: Double): Color {
        val chroma = (1.0 - abs(2.0 * lightness - 1.0)) * saturation
        val hue = ((hueDegrees % 360.0) + 360.0) % 360.0 / 60.0
        val secondary = chroma * (1.0 - abs(hue % 2.0 - 1.0))
        val (redPrime, greenPrime, bluePrime) = when (hue.toInt()) {
            0 -> Triple(chroma, secondary, 0.0)
            1 -> Triple(secondary, chroma, 0.0)
            2 -> Triple(0.0, chroma, secondary)
            3 -> Triple(0.0, secondary, chroma)
            4 -> Triple(secondary, 0.0, chroma)
            else -> Triple(chroma, 0.0, secondary)
        }
        val match = lightness - chroma / 2.0
        fun channel(value: Double) = ((value + match) * 255.0).roundToInt().coerceIn(0, 255)
        return Color(
            red = channel(redPrime) / 255f,
            green = channel(greenPrime) / 255f,
            blue = channel(bluePrime) / 255f,
            alpha = 0.8f,
        )
    }

    private const val FNV_OFFSET_BASIS = 2_166_136_261u
    private const val FNV_PRIME = 16_777_619u
}
