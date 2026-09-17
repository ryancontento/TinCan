package io.github.ryancontento.tincan.chat

/** The language tag and body of a fenced code block. */
data class FencedCode(val language: String?, val code: String)

/**
 * Works on raw source rather than the library AST: simpler, version-proof, and
 * preserves the code byte-for-byte, which is the point of a copy button.
 */
fun parseFencedCode(raw: String): FencedCode {
    val lines = raw.lines()
    if (lines.isEmpty()) return FencedCode(null, "")

    val openIndex = lines.indexOfFirst { it.trimStart().startsWith(FENCE) }
    if (openIndex == -1) {
        // Indented block: four spaces or a tab, no fence, no language.
        return FencedCode(null, lines.joinToString("\n") { it.removePrefix("    ").removePrefix("\t") }.trimEnd())
    }

    val opener = lines[openIndex].trimStart()
    // The info string can carry more than a language; take the first word.
    val language = opener.removePrefix(FENCE).trim().substringBefore(' ').takeIf { it.isNotEmpty() }

    val closeIndex = lines.indexOfLast { it.trimStart().startsWith(FENCE) }
    // A streaming block has no closing fence yet.
    val bodyEnd = if (closeIndex > openIndex) closeIndex else lines.size

    // Strip only the list indent the fence itself carries.
    val indent = lines[openIndex].takeWhile { it == ' ' }.length
    val body = lines.subList(openIndex + 1, bodyEnd)
        .joinToString("\n") { line -> line.drop(minOf(indent, line.takeWhile { it == ' ' }.length)) }

    return FencedCode(language, body.trimEnd())
}

private const val FENCE = "```"
