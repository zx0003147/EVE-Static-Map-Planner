package dev.evestaticmapplanner.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.embeddedai.safeWebUrl

internal sealed interface AssistantMarkdownBlock {
    data class Paragraph(val text: String) : AssistantMarkdownBlock
    data class Heading(val level: Int, val text: String) : AssistantMarkdownBlock
    data class BulletList(val items: List<String>) : AssistantMarkdownBlock
    data class NumberedList(val items: List<String>) : AssistantMarkdownBlock
    data class CodeBlock(val code: String) : AssistantMarkdownBlock
}

@Composable
internal fun AssistantMarkdown(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parseAssistantMarkdown(markdown).forEach { block ->
            when (block) {
                is AssistantMarkdownBlock.Paragraph -> MarkdownInlineText(block.text)
                is AssistantMarkdownBlock.Heading -> MarkdownInlineText(
                    block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is AssistantMarkdownBlock.BulletList -> block.items.forEach { item ->
                    MarkdownListItem(marker = "•", text = item)
                }
                is AssistantMarkdownBlock.NumberedList -> block.items.forEachIndexed { index, item ->
                    MarkdownListItem(marker = "${index + 1}.", text = item)
                }
                is AssistantMarkdownBlock.CodeBlock -> Text(
                    text = block.code,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(EveColors.InputSurface, RoundedCornerShape(5.dp))
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun MarkdownListItem(marker: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(marker, fontWeight = FontWeight.SemiBold)
        MarkdownInlineText(text)
    }
}

@Composable
private fun MarkdownInlineText(text: String, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val annotated = inlineMarkdown(text)
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = annotated,
        style = style,
        onClick = { offset ->
            annotated.getStringAnnotations(ASSISTANT_LINK_TAG, offset, offset)
                .firstOrNull()
                ?.item
                ?.let { safeWebUrl(it) }
                ?.let(uriHandler::openUri)
        },
    )
}

internal fun parseAssistantMarkdown(markdown: String): List<AssistantMarkdownBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    val blocks = mutableListOf<AssistantMarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
            continue
        }
        if (line.trimStart().startsWith("```")) {
            index++
            val code = mutableListOf<String>()
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                code += lines[index++]
            }
            if (index < lines.size) index++
            blocks += AssistantMarkdownBlock.CodeBlock(code.joinToString("\n"))
            continue
        }
        HEADING.matchEntire(line)?.let { match ->
            blocks += AssistantMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2])
            index++
            continue
        }
        if (BULLET.matches(line)) {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val match = BULLET.matchEntire(lines[index]) ?: break
                items += match.groupValues[1]
                index++
            }
            blocks += AssistantMarkdownBlock.BulletList(items)
            continue
        }
        if (NUMBERED.matches(line)) {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val match = NUMBERED.matchEntire(lines[index]) ?: break
                items += match.groupValues[1]
                index++
            }
            blocks += AssistantMarkdownBlock.NumberedList(items)
            continue
        }
        val paragraph = mutableListOf<String>()
        while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines[index])) {
            paragraph += lines[index++]
        }
        if (paragraph.isEmpty()) paragraph += lines[index++]
        blocks += AssistantMarkdownBlock.Paragraph(paragraph.joinToString("\n"))
    }
    return blocks
}

internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString { appendInlineMarkdown(text) }

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    var index = 0
    while (index < text.length) {
        when {
            text[index] == '[' -> {
                val labelEnd = text.indexOf(']', index + 1)
                val targetStart = labelEnd + 1
                val targetEnd = if (labelEnd >= 0 && targetStart < text.length && text[targetStart] == '(') {
                    text.indexOf(')', targetStart + 1)
                } else {
                    -1
                }
                if (targetEnd >= 0) {
                    val label = text.substring(index + 1, labelEnd)
                    val target = safeWebUrl(text.substring(targetStart + 1, targetEnd))
                    if (target != null) {
                        pushStringAnnotation(ASSISTANT_LINK_TAG, target)
                        withStyle(SpanStyle(color = EveColors.PrimaryAccent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                            appendInlineMarkdown(label)
                        }
                        pop()
                    } else {
                        append(text.substring(index, targetEnd + 1))
                    }
                    index = targetEnd + 1
                } else {
                    append(text[index++])
                }
            }
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineMarkdown(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index++])
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end >= 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = INLINE_CODE_BACKGROUND)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index++])
                }
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', index + 1)
                if (end >= 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineMarkdown(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index++])
                }
            }
            else -> append(text[index++])
        }
    }
}

private fun startsBlock(line: String): Boolean =
    line.trimStart().startsWith("```") || HEADING.matches(line) || BULLET.matches(line) || NUMBERED.matches(line)

private val HEADING = Regex("^\\s*(#{1,3})\\s+(.+)$")
private val BULLET = Regex("^\\s*[-+*]\\s+(.+)$")
private val NUMBERED = Regex("^\\s*\\d+[.)]\\s+(.+)$")
private val INLINE_CODE_BACKGROUND = Color(0x332FC7E5)
internal const val ASSISTANT_LINK_TAG = "assistant-http-link"
