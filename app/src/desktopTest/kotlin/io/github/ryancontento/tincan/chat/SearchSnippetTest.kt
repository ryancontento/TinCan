package io.github.ryancontento.tincan.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchSnippetTest {

    @Test
    fun the_match_is_located_within_the_text_that_is_returned() {
        val snippet = snippetAround("the quick brown fox jumps", "brown", radius = 4)
        val matched = snippet.text.substring(snippet.matchStart, snippet.matchStart + snippet.matchLength)
        assertEquals("brown", matched)
    }

    /** The offset has to account for the leading ellipsis, or the bolding slips. */
    @Test
    fun a_trimmed_start_still_points_at_the_match() {
        val snippet = snippetAround("a".repeat(200) + " needle " + "b".repeat(200), "needle")
        val matched = snippet.text.substring(snippet.matchStart, snippet.matchStart + snippet.matchLength)
        assertEquals("needle", matched)
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
    }

    @Test
    fun a_short_message_is_returned_whole_with_no_ellipses() {
        val snippet = snippetAround("hello there", "there")
        assertEquals("hello there", snippet.text)
    }

    @Test
    fun matching_ignores_case_but_the_snippet_keeps_the_original() {
        val snippet = snippetAround("Ollama runs locally", "ollama")
        assertTrue(snippet.text.startsWith("Ollama"))
        assertEquals(0, snippet.matchStart)
    }

    @Test
    fun newlines_collapse_so_a_hit_is_one_line() {
        val snippet = snippetAround("first line\n\n  second line", "second")
        assertFalse(snippet.text.contains("\n"))
        assertEquals("first line second line", snippet.text)
    }
}
