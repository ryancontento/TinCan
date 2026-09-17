package io.github.ryancontento.tincan.chat

/** The language tag and body of a fenced code block. */
data class FencedCode(val language: String?, val code: String)

/**
 * Pulls the language and body out of a raw fenced block.
 *
 * Works on the raw source text rather than the parser's AST on purpose. The
 * markdown library hands each component the full content plus the node's
 * offsets, so slicing the original string is both simpler and immune to the
 * library reshaping its AST between versions — and it preserves the code
 * byte-for-byte, which matters when the whole point is a copy button.
 */
fun parseFencedCode(raw: String): FencedCode {
    val lines = raw.lines()
    if (lines.isEmpty()) return FencedCode(null, "")

    val openIndex = lines.indexOfFirst { it.trimStart().startsWith(FENCE) }
    if (openIndex == -1) {
        // An indented code block: four spaces or a tab, no fence, no language.
        return FencedCode(null, lines.joinToString("\n") { it.removePrefix("    ").removePrefix("\t") }.trimEnd())
    }

    val opener = lines[openIndex].trimStart()
    // The info string can carry more than a language ("kotlin title=x"), and
    // only the first word is the language.
    val language = opener.removePrefix(FENCE).trim().substringBefore(' ').takeIf { it.isNotEmpty() }

    val closeIndex = lines.indexOfLast { it.trimStart().startsWith(FENCE) }
    // A block still streaming has no closing fence yet, so everything after the
    // opener is the body.
    val bodyEnd = if (closeIndex > openIndex) closeIndex else lines.size

    // The opening fence may be indented (inside a list item); the body carries
    // the same indent and it is not part of the code.
    val indent = lines[openIndex].takeWhile { it == ' ' }.length
    val body = lines.subList(openIndex + 1, bodyEnd)
        .joinToString("\n") { line -> line.drop(minOf(indent, line.takeWhile { it == ' ' }.length)) }

    return FencedCode(language, body.trimEnd())
}

private const val FENCE = "```"
