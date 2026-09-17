package io.github.ryancontento.tincan.chat

/** A streaming reply split into complete blocks and the tail still arriving. */
data class MarkdownSplit(val settled: String, val pending: String)

/**
 * Finds the last point where the markdown is provably complete.
 *
 * Parsing the whole reply per chunk is what makes streaming chat UIs stutter,
 * so the settled prefix is parsed once per paragraph and the tail is drawn as
 * plain text. Fence state is tracked because a blank line inside an open ```
 * block is not a boundary — splitting there renders code as prose.
 */
fun splitStreamingMarkdown(text: String): MarkdownSplit {
    if (text.isEmpty()) return MarkdownSplit("", "")

    var insideFence = false
    var settledEnd = 0
    var offset = 0

    for (line in text.lineSequence()) {
        // Fences are line-anchored; leading whitespace is allowed inside lists.
        if (line.trimStart().startsWith(FENCE)) insideFence = !insideFence

        val lineEnd = minOf(offset + line.length + 1, text.length)   // +1 newline, clamped at the end
        if (!insideFence && line.isBlank() && offset > 0) settledEnd = lineEnd
        offset = lineEnd
    }

    return MarkdownSplit(text.substring(0, settledEnd), text.substring(settledEnd))
}

/** Closes an unterminated fence so a truncated reply still renders as code. */
fun closeDanglingFence(text: String): String {
    val fences = text.lineSequence().count { it.trimStart().startsWith(FENCE) }
    return if (fences % 2 == 1) text.trimEnd() + "\n$FENCE" else text
}

private const val FENCE = "```"
