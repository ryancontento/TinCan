package io.github.ryancontento.tincan.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingMarkdownTest {

    @Test
    fun a_single_unfinished_paragraph_is_all_pending() {
        val split = splitStreamingMarkdown("The M1 Pro runs at 200GB")
        assertEquals("", split.settled)
        assertEquals("The M1 Pro runs at 200GB", split.pending)
    }

    @Test
    fun a_completed_paragraph_settles_and_the_next_one_stays_pending() {
        val split = splitStreamingMarkdown("First paragraph.\n\nSecond one still typ")
        assertEquals("First paragraph.\n\n", split.settled)
        assertEquals("Second one still typ", split.pending)
    }

    @Test
    fun settled_plus_pending_always_reconstructs_the_input() {
        // The split must never lose or duplicate a character; the two halves are
        // rendered next to each other and any drift would be visible.
        val samples = listOf(
            "",
            "one",
            "one\n\ntwo",
            "```kt\nval x = 1\n```\n\nafter",
            "a\n\n```\nunclosed\n\nstill inside\n",
            "trailing blanks\n\n\n\n",
        )
        for (sample in samples) {
            val split = splitStreamingMarkdown(sample)
            assertEquals(sample, split.settled + split.pending, "round trip failed for: $sample")
        }
    }

    @Test
    fun a_blank_line_inside_an_open_code_fence_is_not_a_boundary() {
        // This is the case a naive "split at the last blank line" gets wrong.
        // Cutting here would strip the opening fence and render the code as prose.
        val text = "Intro.\n\n```python\ndef f():\n\n    return 1\n"
        val split = splitStreamingMarkdown(text)

        assertEquals("Intro.\n\n", split.settled)
        assertTrue(split.pending.startsWith("```python"), "the whole open block must stay pending")
    }

    @Test
    fun a_closed_code_fence_can_settle_once_a_blank_line_follows_it() {
        val text = "```kt\nval x = 1\n```\n\nNow prose"
        val split = splitStreamingMarkdown(text)

        assertEquals("```kt\nval x = 1\n```\n\n", split.settled)
        assertEquals("Now prose", split.pending)
    }

    @Test
    fun an_indented_fence_inside_a_list_still_counts() {
        val text = "- step one\n\n  ```sh\n  ls\n\n  pwd\n"
        val split = splitStreamingMarkdown(text)
        assertTrue(
            split.pending.contains("```sh"),
            "an indented fence is still a fence; the block must not be split",
        )
    }

    @Test
    fun settling_only_ever_moves_forward_as_text_arrives() {
        // Guards against flicker: if a later chunk could shrink the settled
        // prefix, already-rendered markdown would visibly revert to plain text.
        val chunks = listOf(
            "Intro.",
            "Intro.\n\n",
            "Intro.\n\nSecond",
            "Intro.\n\nSecond para.\n\n",
            "Intro.\n\nSecond para.\n\n```kt\nfun a()",
            "Intro.\n\nSecond para.\n\n```kt\nfun a()\n```\n\ndone",
        )
        var previous = 0
        for (chunk in chunks) {
            val settled = splitStreamingMarkdown(chunk).settled.length
            assertTrue(settled >= previous, "settled prefix shrank at: $chunk")
            previous = settled
        }
    }

    @Test
    fun a_dangling_fence_is_closed_so_truncated_code_still_renders_as_code() {
        val truncated = "Here:\n\n```kt\nfun main() {"
        val closed = closeDanglingFence(truncated)

        assertTrue(closed.endsWith("```"))
        // One opening fence plus the one added: a balanced pair.
        assertEquals(2, closed.lineSequence().count { it.trimStart().startsWith("```") })
    }

    @Test
    fun balanced_fences_are_left_alone() {
        val balanced = "```kt\nval x = 1\n```"
        assertEquals(balanced, closeDanglingFence(balanced))
    }
}
