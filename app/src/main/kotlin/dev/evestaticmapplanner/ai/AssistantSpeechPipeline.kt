package dev.evestaticmapplanner.ai

import dev.evestaticmapplanner.embeddedai.TtsTextChunker
import dev.evestaticmapplanner.embeddedai.TtsTextNormalizer

internal data class AssistantSpeechChunk(
    val messageId: String,
    val chunkId: String,
    val rawStartOffset: Int,
    val rawEndOffset: Int,
    val sequence: Int,
    val text: String,
)

internal data class AssistantSpeechUpdate(
    val chunks: List<AssistantSpeechChunk>,
    val ignoredReason: String? = null,
)

/**
 * Tracks raw cumulative assistant text. Raw offsets advance before normalization so changing Markdown
 * whitespace cannot cause an already-submitted sentence to be submitted again.
 */
internal class AssistantSpeechSubmissionTracker(
    private val maxChunkCodePoints: Int = TtsTextChunker.DEFAULT_MAX_CODE_POINTS,
) {
    private val messages = mutableMapOf<String, MessageProgress>()

    @Synchronized
    fun update(messageId: String, accumulatedRawText: String, complete: Boolean): AssistantSpeechUpdate {
        val progress = messages.getOrPut(messageId) { MessageProgress() }
        if (progress.complete) return AssistantSpeechUpdate(emptyList(), "message-already-complete")
        if (!accumulatedRawText.startsWith(progress.observedRawText)) {
            return AssistantSpeechUpdate(emptyList(), "non-append-only-update")
        }
        progress.observedRawText = accumulatedRawText
        if (progress.suppressed) {
            if (complete) progress.complete = true
            return AssistantSpeechUpdate(emptyList(), "message-suppressed")
        }
        val eligibleEnd = if (complete) {
            accumulatedRawText.length
        } else {
            stableRawBoundary(accumulatedRawText, progress.submittedRawEnd).let { boundary ->
                holdOpenMarkdownTable(accumulatedRawText, progress.submittedRawEnd, boundary)
            }
        }
        if (eligibleEnd <= progress.submittedRawEnd) {
            if (complete) progress.complete = true
            return AssistantSpeechUpdate(emptyList())
        }

        val rawStart = progress.submittedRawEnd
        val rawText = accumulatedRawText.substring(rawStart, eligibleEnd)
        val normalized = TtsTextNormalizer.normalize(rawText)
        val chunks = TtsTextChunker.chunk(normalized, maxChunkCodePoints).map { text ->
            val sequence = progress.nextChunkSequence++
            AssistantSpeechChunk(
                messageId = messageId,
                chunkId = "$messageId:$sequence",
                rawStartOffset = rawStart,
                rawEndOffset = eligibleEnd,
                sequence = sequence,
                text = text,
            )
        }
        progress.submittedRawEnd = eligibleEnd
        if (complete) progress.complete = true
        return AssistantSpeechUpdate(chunks)
    }

    @Synchronized
    fun clear() = messages.clear()

    @Synchronized
    fun suppressIncomplete() {
        messages.values.filterNot(MessageProgress::complete).forEach { it.suppressed = true }
    }

    private fun stableRawBoundary(text: String, start: Int): Int {
        var lastBoundary = start
        var index = start
        while (index < text.length) {
            val character = text[index]
            val decimalPoint = character == '.' && index > 0 && index + 1 < text.length &&
                text[index - 1].isDigit() && text[index + 1].isDigit()
            val currentTokenStart = (index - 1 downTo start)
                .firstOrNull { text[it].isWhitespace() }
                ?.plus(1)
                ?: start
            val urlPeriod = character == '.' &&
                text.regionMatches(currentTokenStart, "http", 0, 4, ignoreCase = true) &&
                index + 1 < text.length && !text[index + 1].isWhitespace()
            if (character == '\n') {
                val candidate = index + 1
                if (inlineMarkdownIsClosed(text.substring(start, candidate))) lastBoundary = candidate
                index++
            } else if (!decimalPoint && !urlPeriod && character in SENTENCE_BOUNDARIES) {
                val stableEnd = stableMarkdownBoundaryEnd(text, start, index)
                if (stableEnd != null) {
                    lastBoundary = stableEnd
                    index = stableEnd
                } else {
                    index++
                }
            } else {
                index++
            }
        }
        return lastBoundary
    }

    private fun stableMarkdownBoundaryEnd(text: String, start: Int, punctuationIndex: Int): Int? {
        var end = punctuationIndex + 1
        while (end < text.length && text[end] in TRAILING_CLOSERS) end++

        if (end < text.length && text[end] == ']') {
            end++
            if (end < text.length && text[end] == '(') {
                val linkEnd = text.indexOf(')', startIndex = end + 1)
                if (linkEnd < 0) return null
                end = linkEnd + 1
            }
        }
        while (end < text.length) {
            val markerLength = MARKDOWN_CLOSERS.firstOrNull { marker -> text.startsWith(marker, end) }?.length
                ?: break
            end += markerLength
        }
        if (!inlineMarkdownIsClosed(text.substring(start, end))) return null
        while (end < text.length && text[end].isWhitespace()) end++
        return end
    }

    private fun inlineMarkdownIsClosed(text: String): Boolean {
        if (PAIRED_MARKDOWN_MARKERS.any { marker -> text.windowed(marker.length).count { it == marker } % 2 != 0 }) {
            return false
        }
        if (text.count { it == '`' } % 2 != 0) return false
        if (text.count { it == '[' } > text.count { it == ']' }) return false
        val lastLinkStart = text.lastIndexOf("](")
        return lastLinkStart < 0 || text.indexOf(')', startIndex = lastLinkStart + 2) >= 0
    }

    private fun holdOpenMarkdownTable(text: String, start: Int, boundary: Int): Int {
        if (boundary <= start) return boundary
        val segment = text.substring(start, boundary)
        val lines = segment.split('\n')
        val lineStarts = mutableListOf(0)
        segment.forEachIndexed { index, character -> if (character == '\n') lineStarts += index + 1 }
        var lineIndex = 0
        while (lineIndex < lines.size) {
            if (!lines[lineIndex].contains('|')) {
                lineIndex++
                continue
            }
            val next = lines.getOrNull(lineIndex + 1)
            if (next == null || next.isBlank()) return start + lineStarts[lineIndex]
            if (TABLE_DIVIDER.matches(next)) {
                val closingBlankLine = (lineIndex + 2 until lines.size).firstOrNull { lines[it].isBlank() }
                    ?: return start + lineStarts[lineIndex]
                val hasTerminatedBlankLine = closingBlankLine < lines.lastIndex
                val hasDataRow = hasTerminatedBlankLine &&
                    (lineIndex + 2 until closingBlankLine).any { lines[it].contains('|') && lines[it].isNotBlank() }
                if (!hasDataRow) return start + lineStarts[lineIndex]
                lineIndex = closingBlankLine + 1
            } else {
                lineIndex++
            }
        }
        return boundary
    }

    private data class MessageProgress(
        var observedRawText: String = "",
        var submittedRawEnd: Int = 0,
        var nextChunkSequence: Int = 1,
        var complete: Boolean = false,
        var suppressed: Boolean = false,
    )

    private companion object {
        val SENTENCE_BOUNDARIES = setOf('.', '!', '?', '。', '！', '？', ';', '；')
        val TRAILING_CLOSERS = setOf('"', '\'', '”', '’', ')', '}')
        val MARKDOWN_CLOSERS = listOf("**", "__", "~~", "*", "_", "`", "~")
        val PAIRED_MARKDOWN_MARKERS = listOf("**", "__", "~~")
        val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")
    }
}

internal fun oneShotSpeechChunks(
    messageId: String,
    markdown: String,
    invocation: Long,
    maxChunkCodePoints: Int = TtsTextChunker.DEFAULT_MAX_CODE_POINTS,
): List<AssistantSpeechChunk> {
    val normalized = TtsTextNormalizer.normalize(markdown)
    return TtsTextChunker.chunk(normalized, maxChunkCodePoints).mapIndexed { index, text ->
        AssistantSpeechChunk(
            messageId = messageId,
            chunkId = "$messageId:manual-$invocation-${index + 1}",
            rawStartOffset = 0,
            rawEndOffset = markdown.length,
            sequence = index + 1,
            text = text,
        )
    }
}
