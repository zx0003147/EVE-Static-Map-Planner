package dev.evestaticmapplanner.shared.model

import java.text.Normalizer

class SharedMarkerValidationException(
    val field: String,
    val reason: String,
    override val message: String,
) : IllegalArgumentException(message)

object SharedMarkerValidation {
    const val MAX_NAME_CODE_POINTS = 80
    const val MAX_NOTES_CODE_POINTS = 2_000
    const val MAX_TAGS = 9
    const val MAX_TAG_CODE_POINTS = 64
    val tagPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun normalize(draft: SharedMarkerDraft): SharedMarkerDraft = SharedMarkerDraft(
        name = normalizeName(draft.name),
        color = draft.color,
        tags = normalizeTags(draft.tags),
        notes = normalizeNotes(draft.notes),
    )

    fun normalizeName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty()) invalid("name", "REQUIRED", "Name is required.")
        if (normalized.codePointCount(0, normalized.length) > MAX_NAME_CODE_POINTS) {
            invalid("name", "TOO_LONG", "Name must be 80 characters or fewer.")
        }
        if (normalized.codePoints().anyMatch(::isRejectedMarkerNameCodePoint)) {
            invalid("name", "CONTROL_CHARACTER", "Name contains an unsupported control character.")
        }
        return normalized
    }

    fun normalizeNotes(value: String?): String? {
        if (value == null) return null
        val normalizedLineEndings = value.replace("\r\n", "\n")
        val normalized = Normalizer.normalize(normalizedLineEndings, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty()) return null
        if (normalized.codePointCount(0, normalized.length) > MAX_NOTES_CODE_POINTS) {
            invalid("notes", "TOO_LONG", "Notes must be 2,000 characters or fewer.")
        }
        if (normalized.codePoints().anyMatch(::isRejectedMarkerNotesCodePoint)) {
            invalid("notes", "CONTROL_CHARACTER", "Notes contain an unsupported control character.")
        }
        return normalized
    }

    fun normalizeTags(values: List<String>): List<String> {
        if (values.size > MAX_TAGS) invalid("tags", "TOO_MANY", "Use no more than 9 tags.")
        val unique = linkedSetOf<String>()
        values.forEach { value ->
            if (!tagPattern.matches(value)) {
                invalid(
                    "tags",
                    "INVALID_TAG",
                    "Tags must be lowercase and use only letters, numbers, dots, underscores, or hyphens.",
                )
            }
            if (!unique.add(value)) invalid("tags", "DUPLICATE_TAG", "Each tag may be used only once.")
        }
        return unique.toList()
    }

    private fun invalid(field: String, reason: String, message: String): Nothing =
        throw SharedMarkerValidationException(field, reason, message)

    private fun isRejectedMarkerNameCodePoint(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) ||
            Character.getType(codePoint) == Character.LINE_SEPARATOR.toInt() ||
            Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR.toInt()

    private fun isRejectedMarkerNotesCodePoint(codePoint: Int): Boolean = when {
        codePoint == '\n'.code || codePoint == '\t'.code -> false
        Character.isISOControl(codePoint) -> true
        Character.getType(codePoint) == Character.LINE_SEPARATOR.toInt() -> true
        Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> false
    }
}
