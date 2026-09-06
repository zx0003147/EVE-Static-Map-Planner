package dev.evestaticmapplanner.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.evestaticmapplanner.feature.api.OverlayImage
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import org.jetbrains.skia.Image
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal data class CharacterPortraitSegment(
    val image: ImageBitmap?,
    val fallbackText: String,
)

internal data class CharacterPortraitStack(
    val segments: List<CharacterPortraitSegment>,
    val overflowCount: Int,
)

/** Shared Host-owned portrait presentation used by the main map and Mini-map. */
internal fun presentCharacterPortraits(
    characters: List<TrackedCharacterSnapshot>,
    maximumVisible: Int = MAX_VISIBLE_PORTRAITS,
): CharacterPortraitStack {
    require(maximumVisible > 0)
    val ordered = characters.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, TrackedCharacterSnapshot::characterName)
            .thenBy(TrackedCharacterSnapshot::characterId),
    )
    return CharacterPortraitStack(
        segments = ordered.take(maximumVisible).map { character ->
            CharacterPortraitSegment(
                image = decodeCharacterPortrait(character.portrait),
                fallbackText = characterPortraitFallback(character.characterName),
            )
        },
        overflowCount = (ordered.size - maximumVisible).coerceAtLeast(0),
    )
}

internal fun decodeCharacterPortrait(portrait: OverlayImage?): ImageBitmap? = portrait?.let { image ->
    runCatching { Image.makeFromEncoded(image.content).toComposeImageBitmap() }.getOrNull()
}

internal fun characterPortraitFallback(characterName: String): String {
    val words = characterName.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.single().take(2).uppercase(Locale.ROOT)
        else -> words.take(2).joinToString("") { it.take(1) }.uppercase(Locale.ROOT)
    }
}

internal fun DrawScope.drawCharacterPortraitDisc(
    stack: CharacterPortraitStack,
    center: Offset,
    radius: Float,
    textMeasurer: TextMeasurer,
    borderColor: Color = Color(0xFFE6F4FF),
    alpha: Float = 1f,
) {
    if (stack.segments.isEmpty()) return
    val safeAlpha = alpha.coerceIn(0f, 1f)
    val portraitRect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
    val count = stack.segments.size
    stack.segments.forEachIndexed { index, segment ->
        val sweep = 360f / count
        val startAngle = -90f + sweep * index
        val clip = if (count == 1) {
            Path().apply { addOval(portraitRect) }
        } else {
            Path().apply {
                moveTo(center.x, center.y)
                arcTo(portraitRect, startAngle, sweep, false)
                close()
            }
        }
        clipPath(clip) {
            val image = segment.image
            if (image != null) {
                val crop = centeredPortraitCrop(image)
                translate(portraitRect.left, portraitRect.top) {
                    drawImage(
                        image = image,
                        srcOffset = crop.first,
                        srcSize = crop.second,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                        alpha = safeAlpha,
                        filterQuality = FilterQuality.High,
                    )
                }
            } else {
                drawCircle(EveColors.SelectedSurface.copy(alpha = safeAlpha), radius, center)
                val angle = (startAngle + sweep / 2f) * PI.toFloat() / 180f
                val labelCenter = if (count == 1) center else Offset(
                    center.x + cos(angle) * radius * 0.42f,
                    center.y + sin(angle) * radius * 0.42f,
                )
                val label = textMeasurer.measure(
                    segment.fallbackText,
                    TextStyle(
                        color = EveColors.PrimaryText.copy(alpha = safeAlpha),
                        fontSize = if (radius <= 10f) 6.sp else 10.sp,
                    ),
                )
                drawText(
                    label,
                    topLeft = labelCenter - Offset(label.size.width / 2f, label.size.height / 2f),
                )
            }
        }
    }
    if (count > 1) {
        portraitSeparatorSegments(center, radius, count).forEach { (start, end) ->
            drawLine(
                color = Color(0xE6FFFFFF).copy(alpha = safeAlpha),
                start = start,
                end = end,
                strokeWidth = 1f,
            )
        }
    }
    drawCircle(
        borderColor.copy(alpha = safeAlpha),
        radius + 0.5f,
        center,
        style = Stroke(1f),
    )
    drawCircle(
        Color(0x66FFFFFF).copy(alpha = 0.4f * safeAlpha),
        (radius - 3f).coerceAtLeast(1f),
        center + Offset(0f, -2f),
        style = Stroke(1f),
    )
    if (stack.overflowCount > 0) {
        val badgeRadius = if (radius <= 10f) 6f else 8f
        val badgeCenter = center + Offset(radius - 1f, -radius + 2f)
        drawCircle(EveColors.InputSurface.copy(alpha = safeAlpha), badgeRadius, badgeCenter)
        drawCircle(Color.White.copy(alpha = safeAlpha), badgeRadius, badgeCenter, style = Stroke(1f))
        val text = textMeasurer.measure(
            "+${stack.overflowCount}",
            TextStyle(Color.White.copy(alpha = safeAlpha), if (radius <= 10f) 7.sp else 9.sp),
        )
        drawText(text, topLeft = badgeCenter - Offset(text.size.width / 2f, text.size.height / 2f))
    }
}

internal fun portraitSeparatorSegments(
    center: Offset,
    radius: Float,
    count: Int,
): List<Pair<Offset, Offset>> {
    if (count <= 1) return emptyList()
    val sweep = 360f / count
    return List(count) { index ->
        val angle = (-90f + sweep * index) * PI.toFloat() / 180f
        center to Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
    }
}

internal fun centeredPortraitCrop(image: ImageBitmap): Pair<IntOffset, IntSize> {
    val side = min(image.width, image.height)
    return IntOffset((image.width - side) / 2, (image.height - side) / 2) to IntSize(side, side)
}

internal const val MAX_VISIBLE_PORTRAITS = 4
