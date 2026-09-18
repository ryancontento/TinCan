package io.github.ryancontento.tincan.export

import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationExportTest {

    private val conversation = ConversationEntity(
        id = 1,
        title = "Memory bandwidth",
        defaultModelId = "phi4",
        backendId = "srv-abc123",
        systemPrompt = "You are terse.",
        createdAt = 1_000,
        updatedAt = 2_000,
    )

    private val messages = listOf(
        message(1, MessageRole.USER, "Why does bandwidth matter?"),
        message(2, MessageRole.ASSISTANT, "Because weights have to be read every token.", model = "phi4")
            .copy(thinking = "consider arithmetic intensity", promptTokens = 12, completionTokens = 34),
    )

    @Test
    fun markdown_names_the_model_that_produced_each_reply() {
        val document = exportConversation(conversation, messages, ExportFormat.MARKDOWN)

        assertEquals("memory-bandwidth.md", document.fileName)
        assertTrue(document.content.startsWith("# Memory bandwidth"))
        assertTrue(document.content.contains("## You"))
        assertTrue(document.content.contains("## phi4"))
        assertTrue(document.content.contains("Because weights have to be read every token."))
    }

    /** Markdown is the readable transcript; the reasoning trace is not part of it. */
    @Test
    fun markdown_leaves_out_reasoning_but_json_keeps_it() {
        val markdown = exportConversation(conversation, messages, ExportFormat.MARKDOWN).content
        val json = exportConversation(conversation, messages, ExportFormat.JSON).content

        assertFalse(markdown.contains("arithmetic intensity"))
        assertTrue(json.contains("arithmetic intensity"))
        assertTrue(json.contains("\"promptTokens\": 12"))
    }

    /** The key identifies a machine and means nothing outside this install. */
    @Test
    fun no_export_carries_the_server_key() {
        ExportFormat.entries.forEach { format ->
            assertFalse(
                exportConversation(conversation, messages, format).content.contains("srv-abc123"),
                "${format.label} must not leak the server key",
            )
        }
    }

    @Test
    fun an_unfinished_reply_says_so_rather_than_reading_as_complete() {
        val cut = messages.map { it.copy(status = MessageStatus.INCOMPLETE) }
        assertTrue(exportConversation(conversation, cut, ExportFormat.MARKDOWN).content.contains("cut short"))
    }

    @Test
    fun a_title_full_of_punctuation_still_makes_a_filename() {
        assertEquals("what-s-2-2", slugify("What's 2 + 2?"))
        assertEquals("conversation", slugify("???"))
    }

    @Test
    fun empty_rows_are_skipped_so_a_discarded_reply_leaves_no_heading() {
        val withEmpty = messages + message(3, MessageRole.ASSISTANT, "", model = "phi4")
        val markdown = exportConversation(conversation, withEmpty, ExportFormat.MARKDOWN).content
        assertEquals(2, Regex("^## ", RegexOption.MULTILINE).findAll(markdown).count())
    }

    private fun message(id: Long, role: MessageRole, content: String, model: String? = null) = MessageEntity(
        id = id,
        conversationId = 1,
        role = role,
        content = content,
        thinking = null,
        modelId = model,
        backendId = "srv-abc123",
        status = MessageStatus.COMPLETE,
        errorCode = null,
        promptTokens = null,
        completionTokens = null,
        tokensPerSecond = null,
        createdAt = id * 100,
    )
}
