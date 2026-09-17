package dev.evestaticmapplanner.embeddedai

/** Converts display-oriented Markdown into stable plain text suitable for every TTS provider. */
object TtsTextNormalizer {
    fun normalize(markdown: String): String {
        val cleaned = markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { character -> character == '\n' || character == '\t' || !character.isISOControl() }
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]"), "")
        val sourcesHeading = Regex("(?im)^\\s*Sources:\\s*$").find(cleaned)
        val sourcesText = sourcesHeading?.let { cleaned.substring(it.range.last + 1) }.orEmpty()
        val sourceCount = MARKDOWN_SOURCE_LINK.findAll(sourcesText).count()
        val withoutSources = sourcesHeading?.let { cleaned.substring(0, it.range.first) } ?: cleaned
        val withoutCodeBlocks = withoutSources.replace(FENCED_CODE_BLOCK, "\n")
        val lines = withoutCodeBlocks.lines()
        val spokenLines = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            if (isTableStart(lines, index)) {
                val (tableText, nextIndex) = normalizeTable(lines, index)
                if (tableText.isNotBlank()) spokenLines += tableText
                index = nextIndex
            } else {
                normalizeLine(lines[index])?.let(spokenLines::add)
                index++
            }
        }
        val plain = spokenLines.joinToString("\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (sourceCount == 0) return plain
        val sourceNotice = if (plain.containsCjk()) {
            "我找到了${sourceCount}个来源。"
        } else {
            "I found $sourceCount ${if (sourceCount == 1) "source" else "sources"}."
        }
        return listOf(plain, sourceNotice).filter(String::isNotBlank).joinToString("\n")
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean =
        index + 1 < lines.size && parseTableCells(lines[index]).size >= 2 && TABLE_DIVIDER.matches(lines[index + 1])

    private fun normalizeTable(lines: List<String>, start: Int): Pair<String, Int> {
        val headers = parseTableCells(lines[start])
        val rows = mutableListOf<List<String>>()
        var index = start + 2
        while (index < lines.size) {
            val cells = parseTableCells(lines[index])
            if (cells.isEmpty()) break
            rows += cells
            index++
        }
        val chinese = (headers + rows.flatten()).any { it.containsCjk() }
        val spokenRows = rows.mapNotNull { cells ->
            val parts = cells.mapIndexedNotNull { cellIndex, rawValue ->
                val value = normalizeInline(rawValue).naturalizeNegativeNumber(chinese)
                if (value.isBlank()) return@mapIndexedNotNull null
                val header = headers.getOrNull(cellIndex)?.let(::normalizeInline).orEmpty()
                if (header.isNameColumn()) value else {
                    val spokenHeader = header.spokenHeader(chinese)
                    listOf(spokenHeader, value).filter(String::isNotBlank).joinToString(" ")
                }
            }
            parts.takeIf(List<String>::isNotEmpty)?.joinToString(if (chinese) "，" else ", ")
                ?.ensureSentenceEnding(chinese)
        }
        return spokenRows.joinToString("\n") to index
    }

    private fun normalizeLine(line: String): String? {
        val headingStripped = line.replace(HEADING_PREFIX, "")
        val bullet = BULLET_PREFIX.find(headingStripped) != null
        val numbered = NUMBERED_PREFIX.find(headingStripped) != null
        val blockStripped = headingStripped
            .replace(BULLET_PREFIX, "")
            .replace(NUMBERED_PREFIX, "")
            .replace(BLOCK_QUOTE_PREFIX, "")
        val normalized = normalizeInline(blockStripped)
        if (normalized.isBlank()) return ""
        return if (bullet || numbered) normalized.ensureSentenceEnding(normalized.containsCjk()) else normalized
    }

    private fun normalizeInline(value: String): String = value
        .replace(MARKDOWN_IMAGE_OR_LINK, "$1")
        .replace(RAW_URL, "")
        .replace(INLINE_CODE, "$1")
        .replace(BOLD_OR_STRIKE, "$1")
        .replace(ITALIC, "$1")
        .replace(Regex("\\\\([*_`#>~|])"), "$1")
        .replace('|', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    private fun parseTableCells(line: String): List<String> {
        val trimmed = line.trim()
        if (!trimmed.contains('|')) return emptyList()
        return trimmed.trim('|').split('|').map(String::trim)
    }

    private fun String.isNameColumn(): Boolean =
        equals("name", ignoreCase = true) || this == "名称" || this == "名字"

    private fun String.spokenHeader(chinese: Boolean): String = when {
        equals("securityStatus", ignoreCase = true) || equals("security status", ignoreCase = true) ->
            if (chinese) "安全等级" else "security status"
        else -> this
    }

    private fun String.naturalizeNegativeNumber(chinese: Boolean): String = replace(
        Regex("(?<![A-Za-z0-9])-([0-9]+(?:\\.[0-9]+)?)"),
        if (chinese) "负 $1" else "negative $1",
    )

    private fun String.ensureSentenceEnding(chinese: Boolean): String =
        if (lastOrNull() in SENTENCE_ENDINGS) this else this + if (chinese) "。" else "."

    private fun String.containsCjk(): Boolean = any { it.code in 0x3400..0x9FFF }

    private val MARKDOWN_SOURCE_LINK = Regex("(?m)^\\s*[-+]\\s+\\[[^]]+]\\(https?://[^)]+\\)\\s*$")
    private val FENCED_CODE_BLOCK = Regex("(?s)```[^\\n]*\\n?.*?```")
    private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")
    private val HEADING_PREFIX = Regex("^\\s{0,3}#{1,6}\\s+")
    private val BULLET_PREFIX = Regex("^\\s*[-+*]\\s+")
    private val NUMBERED_PREFIX = Regex("^\\s*\\d+[.)]\\s+")
    private val BLOCK_QUOTE_PREFIX = Regex("^\\s*>+\\s?")
    private val MARKDOWN_IMAGE_OR_LINK = Regex("!?\\[([^]]+)]\\((?:https?://|mailto:)[^)]+\\)")
    private val RAW_URL = Regex("https?://\\S+")
    private val INLINE_CODE = Regex("`([^`]+)`")
    private val BOLD_OR_STRIKE = Regex("(?:\\*\\*|__|~~)(.+?)(?:\\*\\*|__|~~)")
    private val ITALIC = Regex("(?<!\\w)[*_]([^*_\\n]+)[*_](?!\\w)")
    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '。', '！', '？', ';', '；', ':', '：')
}

object TtsTextChunker {
    const val DEFAULT_MAX_CODE_POINTS = 500

    fun chunk(text: String, maxCodePoints: Int = DEFAULT_MAX_CODE_POINTS): List<String> {
        require(maxCodePoints > 0)
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        val units = sentenceUnits(normalized).flatMap { splitOversized(it, maxCodePoints) }
        val chunks = mutableListOf<String>()
        var current = ""
        units.forEach { unit ->
            val candidate = if (current.isEmpty()) unit else "$current $unit"
            if (candidate.codePointCount() <= maxCodePoints) {
                current = candidate
            } else {
                if (current.isNotBlank()) chunks += current.trim()
                current = unit
            }
        }
        if (current.isNotBlank()) chunks += current.trim()
        return chunks
    }

    private fun sentenceUnits(text: String): List<String> {
        val units = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val decimalPoint = character == '.' && index > 0 && index + 1 < text.length &&
                text[index - 1].isDigit() && text[index + 1].isDigit()
            if (!decimalPoint && (character in SENTENCE_BOUNDARIES || character == '\n')) {
                var end = index + 1
                while (end < text.length && text[end] in TRAILING_CLOSERS) end++
                text.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(units::add)
                start = end
                index = end
            } else {
                index++
            }
        }
        text.substring(start).trim().takeIf(String::isNotEmpty)?.let(units::add)
        return units
    }

    private fun splitOversized(text: String, maxCodePoints: Int): List<String> {
        if (text.codePointCount() <= maxCodePoints) return listOf(text)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.substring(start)
            if (remaining.codePointCount() <= maxCodePoints) {
                remaining.trim().takeIf(String::isNotEmpty)?.let(pieces::add)
                break
            }
            val hardEnd = text.offsetByCodePoints(start, maxCodePoints)
            val minimumBreak = text.offsetByCodePoints(start, (maxCodePoints * 3 / 5).coerceAtLeast(1))
            var end = hardEnd
            var cursor = hardEnd - 1
            var foundSafeBreak = false
            while (cursor >= minimumBreak) {
                if (text[cursor].isWhitespace() || text[cursor] in SAFE_BREAKS) {
                    end = cursor + 1
                    foundSafeBreak = true
                    break
                }
                cursor--
            }
            if (!foundSafeBreak && hardEnd < text.length && text[hardEnd - 1].isAsciiWordCharacter() &&
                text[hardEnd].isAsciiWordCharacter()
            ) {
                cursor = hardEnd
                while (cursor < text.length && text[cursor].isAsciiWordCharacter()) cursor++
                end = cursor
            }
            text.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(pieces::add)
            start = end
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return pieces
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)

    private fun Char.isAsciiWordCharacter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || isDigit() || this == '_' || this == '-'

    private val SENTENCE_BOUNDARIES = setOf('.', '!', '?', '。', '！', '？', ';', '；')
    private val TRAILING_CLOSERS = setOf('"', '\'', '”', '’', ')', ']', '}')
    private val SAFE_BREAKS = setOf(',', '，', ':', '：', ';', '；', '/', '\\')
}
