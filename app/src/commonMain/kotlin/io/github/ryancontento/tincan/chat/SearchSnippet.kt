package io.github.ryancontento.tincan.chat

/** A window of message text around a match, with the match located inside it. */
data class Snippet(val text: String, val matchStart: Int, val matchLength: Int)

/**
 * Cuts [content] down to the text around the first match of [term].
 *
 * Newlines collapse to spaces so a hit is one line in the list however the
 * message was formatted, and the ellipses are part of the text so the caller
 * does not have to know whether either end was trimmed.
 */
fun snippetAround(content: String, term: String, radius: Int = DEFAULT_RADIUS): Snippet {
    val flat = content.replace(WHITESPACE, " ").trim()
    if (term.isBlank()) return Snippet(flat.take(radius * 2), 0, 0)

    val at = flat.indexOf(term, ignoreCase = true)
    if (at == -1) return Snippet(flat.take(radius * 2), 0, 0)

    val from = (at - radius).coerceAtLeast(0)
    val to = (at + term.length + radius).coerceAtMost(flat.length)
    val head = if (from > 0) ELLIPSIS else ""
    val tail = if (to < flat.length) ELLIPSIS else ""

    return Snippet(
        text = head + flat.substring(from, to) + tail,
        matchStart = head.length + (at - from),
        matchLength = term.length,
    )
}

private val WHITESPACE = Regex("""\s+""")
private const val DEFAULT_RADIUS = 60
private const val ELLIPSIS = "…"
