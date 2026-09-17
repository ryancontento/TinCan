package io.github.ryancontento.tincan.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FencedCodeTest {

    @Test
    fun language_and_body_are_separated() {
        val parsed = parseFencedCode("```kotlin\nval x = 1\nprintln(x)\n```")
        assertEquals("kotlin", parsed.language)
        assertEquals("val x = 1\nprintln(x)", parsed.code)
    }

    @Test
    fun a_fence_with_no_language_reports_none() {
        val parsed = parseFencedCode("```\nplain\n```")
        assertNull(parsed.language)
        assertEquals("plain", parsed.code)
    }

    @Test
    fun only_the_first_word_of_the_info_string_is_the_language() {
        val parsed = parseFencedCode("```kotlin title=Example.kt\nval x = 1\n```")
        assertEquals("kotlin", parsed.language)
    }

    @Test
    fun internal_indentation_is_preserved_exactly() {
        // The copy button's whole value is producing code that still runs, so
        // the body must survive byte-for-byte.
        val source = "```python\ndef f():\n    if True:\n        return 1\n```"
        assertEquals("def f():\n    if True:\n        return 1", parseFencedCode(source).code)
    }

    @Test
    fun a_block_indented_inside_a_list_loses_only_the_list_indent() {
        val source = "  ```sh\n  cd /tmp\n    ls -la\n  ```"
        val parsed = parseFencedCode(source)
        assertEquals("sh", parsed.language)
        assertEquals("cd /tmp\n  ls -la", parsed.code)
    }

    @Test
    fun a_block_still_streaming_has_no_closing_fence_yet() {
        // Half an answer must still render as code rather than as prose.
        val parsed = parseFencedCode("```kotlin\nfun main() {\n    println(")
        assertEquals("kotlin", parsed.language)
        assertEquals("fun main() {\n    println(", parsed.code)
    }

    @Test
    fun blank_lines_inside_a_block_survive() {
        val source = "```\nfirst\n\nsecond\n```"
        assertEquals("first\n\nsecond", parseFencedCode(source).code)
    }

    @Test
    fun an_indented_code_block_has_its_four_spaces_stripped() {
        val parsed = parseFencedCode("    val x = 1\n    val y = 2")
        assertNull(parsed.language)
        assertEquals("val x = 1\nval y = 2", parsed.code)
    }

    @Test
    fun an_empty_block_yields_empty_code_rather_than_failing() {
        assertEquals("", parseFencedCode("```\n```").code)
        assertEquals("", parseFencedCode("").code)
    }
}
