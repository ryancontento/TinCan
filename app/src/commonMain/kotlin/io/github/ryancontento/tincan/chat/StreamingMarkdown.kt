package io.github.ryancontento.tincan.chat

/**
 * A reply split into the part that is safe to render as markdown and the part
 * that is still arriving.
 */
data class MarkdownSplit(
    /** Complete blocks. Grows only at paragraph boundaries, so parsing is rare. */
    val settled: String,
    /** Still in flight. Rendered as plain text — parsing it would flicker. */
    val pending: String,
)

/**
 * Splits a streaming reply at the last point where the markdown is known to be
 * complete.
 *
 * Re-parsing the whole message on every chunk is the classic way to make a
 * streaming chat UI stutter: the parse cost grows with the reply while the
 * chunks keep arriving at the same rate. So the settled prefix is parsed once
 * per *paragraph* rather than once per chunk, and the tail is drawn as plain
 * text until it settles.
 *
 * The subtlety is code fences. A naive split at the last blank line will
 * happily cut inside an open ``` block, and half a fence renders as garbage —
 * the opening line vanishes and the code shows up as prose. So this tracks
 * fence state and refuses to treat a blank line inside a block as a boundary.
 * That means a long code block stays plain text until its closing fence
 * arrives, which is correct: an unterminated block genuinely has no meaning yet.
 */
fun splitStreamingMarkdown(text: String): MarkdownSplit {
    if (text.isEmpty()) return MarkdownSplit("", "")

    var insideFence = false
    var settledEnd = 0
    var offset = 0

    for (line in text.lineSequence()) {
        // Fences are line-anchored. Leading whitespace is allowed (a fence can
        // be indented inside a list item), trailing content is the info string.
        if (line.trimStart().startsWith(FENCE)) insideFence = !insideFence

        // +1 for the newline; the final line has none, hence the clamp.
        val lineEnd = minOf(offset + line.length + 1, text.length)

        if (!insideFence && line.isBlank() && offset > 0) settledEnd = lineEnd

        offset = lineEnd
    }

    return MarkdownSplit(
        settled = text.substring(0, settledEnd),
        pending = text.substring(settledEnd),
    )
}

private const val FENCE = "```"

/**
 * Closes an unterminated code fence so a finished-but-truncated reply still
 * renders as code.
 *
 * Only for messages that are done — a reply cut off mid-block by a dropped
 * connection or by Stop. Without this the partial code renders as prose, which
 * is both ugly and misleading about what was received.
 */
fun closeDanglingFence(text: String): String {
    val fences = text.lineSequence().count { it.trimStart().startsWith(FENCE) }
    return if (fences % 2 == 1) text.trimEnd() + "\n$FENCE" else text
}
