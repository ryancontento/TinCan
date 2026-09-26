package io.github.ryancontento.tincan.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.ryancontento.tincan.ui.TinCanTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one end-to-end check that a reply arriving in chunks actually reaches the screen. */
@OptIn(ExperimentalTestApi::class)
class StreamingRenderTest {

    @Test
    fun a_reply_renders_as_it_streams_and_settles_into_markdown() = runComposeUiTest {
        var text by mutableStateOf("")
        var streaming by mutableStateOf(true)
        setContent {
            TinCanTheme { MessageContent(text = text, isStreaming = streaming) }
        }

        // An unfinished paragraph is drawn as plain text straight away.
        text = "The first para"
        waitForIdle()
        onNodeWithText("The first para", substring = true).assertIsDisplayed()

        // Once a code block is complete it renders as one, with its copy button.
        text = "The first paragraph.\n\n```kotlin\nval x = 1\n```\n\nStill arr"
        waitForIdle()
        onNodeWithText("val x = 1", substring = true).assertIsDisplayed()
        onNodeWithText("Copy").assertIsDisplayed()
        onNodeWithText("Still arr", substring = true).assertIsDisplayed()

        // The fence markers are syntax, never text on screen.
        assertEquals(0, onAllNodesWithText("```", substring = true).fetchSemanticsNodes().size)

        text = "The first paragraph.\n\n```kotlin\nval x = 1\n```\n\nStill arriving, now done."
        streaming = false
        waitForIdle()
        onNodeWithText("Still arriving, now done.", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_reply_cut_off_inside_a_code_block_still_renders_as_code() = runComposeUiTest {
        setContent {
            TinCanTheme { MessageContent(text = "Here:\n\n```python\nprint('hi')", isStreaming = false) }
        }

        onNodeWithText("print('hi')", substring = true).assertIsDisplayed()
        onNodeWithText("Copy").assertIsDisplayed()
    }
}
